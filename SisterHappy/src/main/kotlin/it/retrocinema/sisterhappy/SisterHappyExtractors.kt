package it.retrocinema.sisterhappy

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder

/**
 * Header condivisi: SET COMPLETO da browser Chrome desktop, come il
 * fake_headers usato da MammaMia (l'implementazione collaudata a ott. 2026).
 * I protettori (uprot, safego/clicka) filtrano le richieste con header minimi:
 * la v9 con solo UA/Accept riceveva pagine 200 SENZA il link Continue (traccia
 * "u noCONT"). Il set completo e' cio' che le fa passare. Niente
 * Accept-Encoding manuale: OkHttp gestisce da solo la compressione.
 */
fun shFullHeaders(referer: String): Map<String, String> = mutableMapOf(
    "User-Agent" to SH_UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7",
    "Upgrade-Insecure-Requests" to "1",
    "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
    "sec-ch-ua-mobile" to "?0",
    "sec-ch-ua-platform" to "\"Windows\"",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "none",
    "Sec-Fetch-User" to "?1",
    "DNT" to "1",
    "Sec-GPC" to "1",
).also { if (referer.isNotBlank()) it["Referer"] = referer }

/** User-Agent Firefox: MammaMia lo usa per il flusso safego/clicka. */
const val SH_FF_UA =
    "Mozilla/5.0 (X11; Linux x86_64; rv:146.0) Gecko/20100101 Firefox/146.0"

/** Header Cookie da un jar di cookie: il client dell'app NON ha cookie jar
 *  (verificato nel sorgente CloudStream), quindi i cookie vanno rinviati a mano. */
fun shCookieHeader(jar: Map<String, String>): String? =
    if (jar.isEmpty()) null else jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

/** Catena di regex m3u8 (stessa priorita' di MammaMia: sources/src -> file|src|url -> qualunque). */
fun shFindM3u8(body: String): String? =
    Regex("sources:\\s*\\[\\s*\\{\\s*src:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)\"")
        .find(body)?.groupValues?.get(1)
        ?: Regex(
            "(?:file|src|url)\\s*[:=]\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)
        ?: Regex("https?://[^\"'<>\\s]+\\.m3u8[^\"'<>\\s]*")
            .find(body)?.value

/**
 * MaxStream. Due forme supportate:
 * 1. /uprots/<id> (link ottenuto da uprot): catena di redirect seguita A MANO
 *    (allowRedirects=false): ogni salto raccoglie i cookie e li rinvia al
 *    successivo, come fa il browser (uprots -> watchfree -> player); fallback:
 *    ricostruzione /emvvv/<id> dal path watchfree (trucco MammaMia) o iframe
 *    maxstream;
 * 2. pagina player legacy con script "eval(function(p,a,c,k,e,d)...)" da
 *    spacchettare (logica da provider CB01 GPL di DieGon7771).
 */
class MaxStreamExtractor(
    private val deadline: Long = 0L,
    private val note: (String) -> Unit = {},
) : ExtractorApi() {
    override var name = "MaxStream"
    override var mainUrl = "https://maxstream.video/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (url.contains("/uprots/", ignoreCase = true)) {
            extractFromUprots(url, callback)
        } else {
            extractLegacy(url, referer, callback)
        }
    }

    /** Catena moderna: uprots -> (redirect a mano con cookie) -> watchfree -> player -> m3u8. */
    private suspend fun extractFromUprots(url: String, callback: (ExtractorLink) -> Unit) {
        var current = url
        var jar: MutableMap<String, String> = mutableMapOf()
        var body: String? = null
        for (hop in 0 until 6) {
            if (deadline > 0 && System.currentTimeMillis() > deadline) {
                Log.d(name, "uprots: deadline raggiunta al salto $hop")
                return
            }
            val headers = shFullHeaders(referer = "https://uprot.net/").toMutableMap()
            shCookieHeader(jar)?.let { headers["Cookie"] = it }
            val resp = runCatching {
                app.get(current, headers = headers, timeout = 12, allowRedirects = false)
            }.getOrNull() ?: run { note("ms RETE"); return }
            jar.putAll(resp.cookies)
            when {
                resp.code in 300..399 -> {
                    val loc = resp.headers.values("location").firstOrNull()?.trim()
                    if (loc.isNullOrEmpty()) {
                        Log.d(name, "uprots: redirect senza Location da $current")
                        note("ms noLoc")
                        return
                    }
                    current = runCatching { URI(current).resolve(loc).toString() }.getOrNull()
                        ?: run { note("ms badLoc"); return }
                }
                resp.code == 200 -> {
                    body = resp.text
                    break
                }
                else -> {
                    Log.d(name, "uprots GET status ${resp.code} su $current")
                    note("ms ${resp.code}")
                    return
                }
            }
        }
        val finalBody = body ?: run {
            Log.d(name, "uprots: nessuna pagina 200 nella catena")
            note("ms no200")
            return
        }
        val finalUrl = current

        // Caso comune: m3u8 gia' nel body finale della catena
        shFindM3u8(finalBody)?.let { emit(it, callback); return }

        // Fallback: watchfree/<x>/<y>/ -> /emvvv/<y> (pagina player reale);
        // variante a segmento singolo watchfree/<x> -> /emvvv/<x>.
        val watchfreeUrl = if (finalUrl.contains("watchfree/")) finalUrl
        else Regex("https?://[\\w.-]*maxstream\\.video/watchfree/[^\\s'\"<>]+", RegexOption.IGNORE_CASE)
            .find(finalBody)?.value
        if (watchfreeUrl != null) {
            val parts = watchfreeUrl.substringAfter("watchfree/").trimEnd('/').split('/')
            val playerUrls = mutableListOf<String>()
            if (parts.size >= 2 && parts[1].isNotBlank()) {
                playerUrls.add("https://maxstream.video/emvvv/${parts[1]}")
            }
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                playerUrls.add("https://maxstream.video/emvvv/${parts[0]}")
            }
            for (playerUrl in playerUrls.distinct()) {
                Log.d(name, "provo player $playerUrl")
                val playerResp = runCatching {
                    app.get(playerUrl, headers = shFullHeaders(referer = finalUrl), timeout = 12)
                }.getOrNull() ?: run { note("p RETE"); continue }
                if (playerResp.code != 200) {
                    Log.d(name, "player HTTP ${playerResp.code}")
                    note("p ${playerResp.code}")
                    continue
                }
                shFindM3u8(playerResp.text)?.let { emit(it, callback); return }
                unpackSource(playerResp.text)?.let { emit(it, callback); return }
            }
        }

        // Fallback: iframe maxstream nel body finale
        Regex(
            "<iframe[^>]+src=['\"](https?://[^'\"]*maxstream[^'\"]*?/[a-z0-9_-]+/[a-z0-9]+)['\"]",
            RegexOption.IGNORE_CASE
        ).find(finalBody)?.groupValues?.get(1)?.let { iframe ->
            val iframeResp = runCatching {
                app.get(iframe, headers = shFullHeaders(referer = finalUrl), timeout = 12)
            }.getOrNull()
            if (iframeResp != null && iframeResp.code == 200) {
                shFindM3u8(iframeResp.text)?.let { emit(it, callback); return }
                unpackSource(iframeResp.text)?.let { emit(it, callback); return }
            } else {
                note("if ${iframeResp?.code ?: 0}")
            }
        }

        // Ultima spiaggia: script packed nella pagina finale
        unpackSource(finalBody)?.let { emit(it, callback) } ?: run { note("nosrc") }
    }

    /** Forma legacy: pagina player con evalpacked (vecchio formato). */
    private suspend fun extractLegacy(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = runCatching {
            app.get(url, headers = shFullHeaders(referer = referer ?: mainUrl), timeout = 12)
        }.getOrNull() ?: run { note("RETE"); return }
        val body = response.body.string()

        val src = shFindM3u8(body) ?: unpackSource(body)
        if (src.isNullOrBlank()) {
            Log.d(name, "Nessuna sorgente trovata in $url")
            note("nosrc")
            return
        }
        emit(src, callback)
    }

    /** Spacchetta l'evalpacked se presente, poi cerca src:/file:. */
    private fun unpackSource(body: String): String? {
        val candidates = mutableListOf<String>()
        if (body.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching {
                val script = "eval(function(p,a,c,k,e,d)" +
                    body.substringAfter("eval(function(p,a,c,k,e,d)")
                        .substringBefore(")))") + ")))"
                candidates.add(getAndUnpack(script))
            }
        }
        candidates.add(body)
        for (candidate in candidates) {
            Regex("src\\s*:\\s*[\"']([^\"']+)[\"']").find(candidate)
                ?.groupValues?.get(1)?.let { return it }
            Regex("file\\s*:\\s*[\"']([^\"']+)[\"']").find(candidate)
                ?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    private suspend fun emit(src: String, callback: (ExtractorLink) -> Unit) {
        note("EMIT")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = src,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "https://maxstream.video/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

/**
 * DeltaBit (e variante Turbovid): flusso COMPLETO MammaMia deltabit.py
 * (aggiornato ott. 2026), che e' quello che oggi funziona nel browser:
 * 1) GET con Range: bytes=0-0 e redirect A MANO (cookie raccolti a ogni
 *    salto: il sito rinvia spesso a un nuovo path dello stesso host);
 * 2) GET della pagina reale con Referer https://safego.cc/;
 * 3) lettura di TUTTI i campi <input> nascosti (hash, fname, op, id, ...);
 * 4) attesa 2,5 s (deltabit) o 5 s (turbovid): senza attesa il POST fallisce;
 * 5) POST della stessa pagina con i campi + imhuman + referer: la risposta
 *    contiene sources: ["....m3u8"].
 * Fallback: regex dirette sulla pagina (comportamento v8/v9).
 */
class DeltaBitExtractor(
    private val deadline: Long = 0L,
    private val note: (String) -> Unit = {},
) : ExtractorApi() {
    override var name = "DeltaBit"
    override var mainUrl = "https://deltabit.co/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val turbovid = url.contains("turbovid", ignoreCase = true)

        // 1) catena di redirect a mano con cookie e Range (header MammaMia)
        var current = url
        val jar = mutableMapOf<String, String>()
        for (hop in 0 until 4) {
            if (deadline > 0 && System.currentTimeMillis() > deadline) {
                note("db deadline")
                return
            }
            val headers = shFullHeaders(referer = referer ?: "https://safego.cc/").toMutableMap()
            headers["Range"] = "bytes=0-0"
            shCookieHeader(jar)?.let { headers["Cookie"] = it }
            val resp = runCatching {
                app.get(current, headers = headers, timeout = 12, allowRedirects = false)
            }.getOrNull() ?: run { note("db RETE"); return }
            jar.putAll(resp.cookies)
            when {
                resp.code in 300..399 -> {
                    val loc = resp.headers.values("location").firstOrNull()?.trim()
                    if (loc.isNullOrEmpty()) {
                        Log.d(name, "redirect senza Location da $current")
                        note("db noLoc")
                        return
                    }
                    current = runCatching { URI(current).resolve(loc).toString() }
                        .getOrNull() ?: run { note("db badLoc"); return }
                }
                resp.code == 200 -> break // pagina raggiunta: si prosegue col GET player
                else -> {
                    Log.d(name, "GET status ${resp.code} su $current")
                    note("db ${resp.code}")
                    return
                }
            }
        }

        // 2) GET della pagina reale con Referer safego (header MammaMia)
        val headers2 = shFullHeaders(referer = "https://safego.cc/").toMutableMap()
        shCookieHeader(jar)?.let { headers2["Cookie"] = it }
        val page = runCatching {
            app.get(current, headers = headers2, timeout = 12)
        }.getOrNull() ?: run { note("db RETE2"); return }
        jar.putAll(page.cookies)
        if (page.code != 200) {
            Log.d(name, "pagina player HTTP ${page.code} su $current")
            note("db p${page.code}")
            return
        }
        val doc = page.document

        // Sorgente gia' nella pagina (fallback veloce, pagine semplici)
        shFindM3u8(page.text)?.let { emit(it, current, callback); return }
        findOtherSource(page.text)?.let { emit(it, current, callback); return }

        // 3) campi nascosti della pagina
        val fields = LinkedHashMap<String, String>()
        doc.select("input").forEach { el ->
            val nm = el.attr("name")
            if (nm.isNotBlank()) fields[nm] = el.attr("value")
        }
        if (fields.isEmpty()) {
            Log.d(name, "nessun form player in $current")
            note("db noform")
            return
        }

        // 4) imhuman + referer e attesa obbligatoria
        fields["imhuman"] = if (turbovid) "Proceed to video" else ""
        fields["referer"] = current
        note(if (turbovid) "db w5" else "db w2")
        delay(if (turbovid) 5_000L else 2_500L)
        if (deadline > 0 && System.currentTimeMillis() > deadline) {
            note("db deadline")
            return
        }

        // 5) POST della stessa pagina con i campi + cookie
        val host = runCatching { URI(current).host }.getOrDefault("deltabit.co")
        val form = fields.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val postHeaders = shFullHeaders(referer = current).toMutableMap()
        postHeaders["Origin"] = "https://$host"
        postHeaders["Content-Type"] = "application/x-www-form-urlencoded"
        postHeaders["Sec-Fetch-Site"] = "same-origin"
        shCookieHeader(jar)?.let { postHeaders["Cookie"] = it }
        val post = runCatching {
            app.post(
                current,
                headers = postHeaders,
                requestBody = form.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()),
                timeout = 12,
            )
        }.getOrNull() ?: run { note("db pRETE"); return }
        Log.d(name, "POST player HTTP ${post.code} su $current")
        note("db p${post.code}")
        if (post.code != 200) return

        // La risposta contiene sources: ["https://....m3u8"] (formato MammaMia)
        Regex("""sources:\s*\["([^"]+)""").find(post.text)
            ?.groupValues?.get(1)?.let { emit(it, current, callback); return }
        shFindM3u8(post.text)?.let { emit(it, current, callback); return }
        findOtherSource(post.text)?.let { emit(it, current, callback); return }
        note("nosrc")
    }

    private suspend fun emit(
        link: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        note("EMIT")
        val isHls = link.substringBefore('?').lowercase().endsWith(".m3u8")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun findOtherSource(body: String): String? {
        val texts = mutableListOf<String>()
        if (body.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching {
                val script = "eval(function(p,a,c,k,e,d)" +
                    body.substringAfter("eval(function(p,a,c,k,e,d)")
                        .substringBefore(")))") + ")))"
                texts.add(getAndUnpack(script))
            }
        }
        texts.add(body)
        for (text in texts) {
            Regex("file\\s*:\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
            Regex("sources\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
            Regex("<source[^>]+src\\s*=\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
        }
        Regex("https?://[^\"'<>\\s]+\\.(?:m3u8|mp4)[^\"'<>\\s]*").find(body)?.value?.let { return it }
        return null
    }
}

/**
 * MixDrop: pagina /e/<id> con script packed contenente wurl="https://...m3u8".
 * Estrazione via HTTP leggera (niente WebView): unpack + regex di riserva.
 */
class MixDropExtractor(private val note: (String) -> Unit = {}) : ExtractorApi() {
    override var name = "MixDrop"
    override var mainUrl = "https://mixdrop.top/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Le pagine player sono sotto /e/; /f/ e la pagina di download
        val playerUrl = url.replace("/f/", "/e/")
        val response = runCatching {
            app.get(
                playerUrl,
                headers = shFullHeaders(referer = referer ?: mainUrl),
                timeout = 12,
            )
        }.getOrNull() ?: run { note("RETE"); return }
        val body = response.body.string()

        val link = findVideo(body)
        if (link.isNullOrBlank()) {
            Log.d(name, "Nessuna sorgente trovata in $playerUrl")
            note("nosrc")
            return
        }
        note("EMIT")
        val isHls = link.substringBefore('?').lowercase().endsWith(".m3u8")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun findVideo(body: String): String? {
        val texts = mutableListOf<String>()
        if (body.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching {
                val script = "eval(function(p,a,c,k,e,d)" +
                    body.substringAfter("eval(function(p,a,c,k,e,d)")
                        .substringBefore(")))") + ")))"
                texts.add(getAndUnpack(script))
            }
        }
        texts.add(body)
        for (text in texts) {
            Regex("wurl\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
            Regex("file\\s*:\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
        }
        Regex("https?://[^\"'<>\\s]+\\.(?:m3u8|mp4)[^\"'<>\\s]*").find(body)?.value?.let { return it }
        return null
    }
}
