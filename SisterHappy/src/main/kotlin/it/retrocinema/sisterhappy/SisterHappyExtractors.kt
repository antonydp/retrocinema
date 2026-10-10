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

/**
 * Header condivisi, MINIMI come nelle implementazioni collaudate (estrattore
 * Maxstream ufficiale di CloudStream, CB01 di doGior, Toonitalia): solo
 * User-Agent, Accept, Accept-Language e Referer. Header Sec-Fetch-* in piu'
 * non aiutano e a volte buckiano i WAF.
 */
fun shFullHeaders(referer: String): Map<String, String> = mapOf(
    "User-Agent" to SH_UA,
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
    "Referer" to referer,
)

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
 * 1. /uprots/<id> (link ottenuto da uprot): GET con redirect seguiti (uprots ->
 *    watchfree -> player) e m3u8 nel body finale; fallback: ricostruzione
 *    /emvvv/<id> dal path watchfree (trucco MammaMia) o iframe maxstream;
 * 2. pagina player legacy con script "eval(function(p,a,c,k,e,d)...)" da
 *    spacchettare (logica da provider CB01 GPL di DieGon7771).
 */
class MaxStreamExtractor : ExtractorApi() {
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

    /** Catena moderna: uprots -> (redirect) -> watchfree -> player -> m3u8. */
    private suspend fun extractFromUprots(url: String, callback: (ExtractorLink) -> Unit) {
        val headers = shFullHeaders(referer = "https://uprot.net/")
        val response = runCatching {
            app.get(url, headers = headers, timeout = 15)
        }.getOrNull() ?: return
        if (response.code != 200) {
            Log.d(name, "uprots GET status ${response.code} url finale ${runCatching { response.url }.getOrDefault(url)}")
            return
        }
        val body = response.text
        val finalUrl = runCatching { response.url }.getOrDefault(url)

        // Caso comune: m3u8 gia' nel body finale della catena
        shFindM3u8(body)?.let { emit(it, callback); return }

        // Fallback: watchfree/<x>/<y>/ -> /emvvv/<y> (pagina player reale);
        // variante a segmento singolo watchfree/<x> -> /emvvv/<x>.
        val watchfreeUrl = if (finalUrl.contains("watchfree/")) finalUrl
        else Regex("https?://[\\w.-]*maxstream\\.video/watchfree/[^\\s'\"<>]+", RegexOption.IGNORE_CASE)
            .find(body)?.value
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
                }.getOrNull() ?: continue
                if (playerResp.code != 200) {
                    Log.d(name, "player HTTP ${playerResp.code}")
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
        ).find(body)?.groupValues?.get(1)?.let { iframe ->
            val iframeResp = runCatching {
                app.get(iframe, headers = shFullHeaders(referer = finalUrl), timeout = 12)
            }.getOrNull()
            if (iframeResp != null && iframeResp.code == 200) {
                shFindM3u8(iframeResp.text)?.let { emit(it, callback); return }
                unpackSource(iframeResp.text)?.let { emit(it, callback); return }
            }
        }

        // Ultima spiaggia: script packed nella pagina finale
        unpackSource(body)?.let { emit(it, callback) }
    }

    /** Forma legacy: pagina player con evalpacked (vecchio formato). */
    private suspend fun extractLegacy(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = runCatching {
            app.get(url, headers = shFullHeaders(referer = referer ?: mainUrl), timeout = 12)
        }.getOrNull() ?: return
        val body = response.body.string()

        val src = shFindM3u8(body) ?: unpackSource(body)
        if (src.isNullOrBlank()) {
            Log.d(name, "Nessuna sorgente trovata in $url")
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
 * DeltaBit: pagina player (domini deltabit vari, finali della catena clicka/adelta)
 * con sorgente dentro jwplayer o script packed. Estrazione tollerante.
 */
class DeltaBitExtractor : ExtractorApi() {
    override var name = "DeltaBit"
    override var mainUrl = "https://deltabit.co/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = runCatching {
            app.get(url, headers = shFullHeaders(referer = referer ?: mainUrl), timeout = 12)
        }.getOrNull() ?: return
        val body = response.body.string()

        val link = shFindM3u8(body)
            ?: findOtherSource(body)
        if (link.isNullOrBlank()) {
            Log.d(name, "Nessuna sorgente trovata in $url")
            return
        }
        val isHls = link.substringBefore('?').lowercase().endsWith(".m3u8")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = url
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
class MixDropExtractor : ExtractorApi() {
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
        }.getOrNull() ?: return
        val body = response.body.string()

        val link = findVideo(body)
        if (link.isNullOrBlank()) {
            Log.d(name, "Nessuna sorgente trovata in $playerUrl")
            return
        }
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
