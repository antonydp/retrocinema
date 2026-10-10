package it.retrocinema.sisterhappy

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** User-Agent realistico da browser desktop. */
const val SH_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** Un link di un episodio: url del protettore (o diretto) + etichetta dell'host letta dalla pagina. */
data class ShLink(
    val url: String = "",
    val host: String = ""
)

/** Dati di un episodio salvati nel campo data: stagione, nome e lista dei link disponibili. */
data class ShEpisodeData(
    val season: Int = 0,
    val name: String = "",
    val links: List<ShLink> = emptyList()
)

/**
 * Tetto rigido per una singola loadLinks: senza limite i tentativi di riserva
 * (anchor generici, varianti mse/msf, secondo pass) possono catenare decine di
 * richieste e l'app resta "in caricamento" per minuti. Ogni URL viene richiesto
 * AL MASSIMO UNA VOLTA per episodio e si rispettano scadenza e conteggio.
 */
private class ShBudget(val maxRequests: Int, val deadline: Long) {
    var used = 0
    val visited = mutableSetOf<String>()

    fun can(): Boolean = used < maxRequests && System.currentTimeMillis() < deadline

    /** Registra l'URL: false se era gia' stato richiesto (da non richiedere di nuovo). */
    fun mark(url: String): Boolean = visited.add(url.substringBefore('#'))
}

/**
 * SisterHappy - estensione dedicata a UNA sola serie TV italiana.
 *
 * La pagina del sito e una classica pagina "serie" WordPress:
 * - sinossi dentro div.entry-content (parte nascosta compresa)
 * - "Link disponibili:" seguito da div.su-accordion con un div.su-spoiler per stagione
 * - dentro ogni stagione, righe di testo separate da <br>:
 *     "15x01 Audizioni 1 - <a href=protettore>MaxStream</a> - <a>DeltaBit</a>"
 * - i link passano da protettori (uprot.net, clicka.cc) che nascondono gli host video
 *
 * Il flusso: load() elenca stagioni ed episodi; loadLinks() risolve ogni protettore
 * seguendo la catena reale (uprot -> maxstream/uprots, clicka -> safego -> adelta ->
 * deltabit) e delega all'estrattore dell'host finale.
 */
class SisterHappy : MainAPI() {
    override var name = "SisterHappy"
    override var mainUrl = "https://eurostream.pics"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override val hasQuickSearch = false

    companion object {
        /** Pagina unica della serie gestita da questo plugin. */
        const val SHOW_PATH = "/x-factor-53/"

        /** Host che mascherano i link veri (protettori, livello captcha incluso). */
        private val PROTECTOR_HOSTS = listOf("uprot.net", "clicka.cc", "safego.cc")

        /** Prefissi di pagina del sito che NON sono pagine serie: tolti dalla ricerca. */
        private val NON_SHOW_PREFIXES = listOf(
            "category", "elenco-", "guida-", "richieste", "nuovi-ep", "tag", "author", "page"
        )

        /** URL finali riconosciuti come "foglia" (host video o pagina player). */
        private val LEAF_HOSTS = listOf("maxstream", "deltabit", "mixdrop")

        private val UPROTS_RE =
            Regex("https?://[\\w.-]*maxstream\\.video/uprots/[A-Za-z0-9=]+", RegexOption.IGNORE_CASE)
        private val ADELTA_RE =
            Regex("https?://[\\w.-]*clicka\\.cc/adelta/[A-Za-z0-9=]+", RegexOption.IGNORE_CASE)
        private val ANCHOR_RE = Regex(
            "<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }

    /** Bypass Cloudflare riusato per tutte le richieste ai protettori. */
    private val cfKiller = CloudflareKiller()

    private fun navHeaders(referer: String? = null): Map<String, String> {
        val base = mapOf(
            "User-Agent" to SH_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.5",
        )
        return if (referer != null) base + mapOf("Referer" to referer) else base
    }

    /**
     * Header "full browser" (pattern MammaMia): senza Sec-Fetch-*, DNT, Priority e
     * Upgrade-Insecure-Requests la Cloudflare dei protettori risponde 403 anche con
     * fingerprint browser. Sec-Fetch-Site viene allineato al rapporto tra target e referer.
     */
    private fun fullHeaders(referer: String? = null, origin: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to SH_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.5",
            "Sec-GPC" to "1",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "DNT" to "1",
            "Priority" to "u=0, i",
        )
        if (origin != null) headers["Origin"] = origin
        if (referer != null) {
            headers["Referer"] = referer
            val refHost = referer.toHttpUrlOrNull()?.host
            val targetHost = headers["Origin"]?.toHttpUrlOrNull()?.host
            headers["Sec-Fetch-Site"] = if (refHost != null && refHost == targetHost) "same-origin" else "cross-site"
        }
        return headers
    }

    /** GET senza redirect, solo se il budget lo consente e l'URL non e' gia' stato chiesto. */
    private suspend fun getNoRedirect(url: String, headers: Map<String, String>, budget: ShBudget) =
        if (!budget.can() || !budget.mark(url)) null
        else {
            budget.used++
            runCatching {
                app.get(url, allowRedirects = false, headers = headers, timeout = 10_000, interceptor = cfKiller)
            }.getOrNull()
        }

    // ------------------------------------------------------------------
    //  HOME: una sola riga con la scheda della serie
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val card = loadShowCard(mainUrl + SHOW_PATH)
        return newHomePageResponse(
            HomePageList("In evidenza", listOf(card)),
        )
    }

    // ------------------------------------------------------------------
    //  RICERCA: motore di ricerca WordPress del sito (pagine serie)
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/",
            params = mapOf("s" to query),
            headers = navHeaders(referer = mainUrl),
        )
        val doc = response.document
        val results = mutableListOf<SearchResponse>()
        doc.select("h2 a[href], h3 a[href]").forEach { a ->
            val href = a.attr("abs:href")
            if (href.isBlank() || !href.startsWith(mainUrl)) return@forEach
            val slug = href.removePrefix(mainUrl).trimStart('/').trimEnd('/').substringBefore('/')
            if (slug.isEmpty() || NON_SHOW_PREFIXES.any { slug.startsWith(it) }) return@forEach
            val title = a.text().trim()
            if (title.isEmpty()) return@forEach
            results.add(
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { }
            )
        }
        return results.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    //  LOAD: la scheda della serie con stagioni ed episodi
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        return loadShow(url)
    }

    private suspend fun loadShow(url: String): com.lagradost.cloudstream3.TvSeriesLoadResponse {
        val doc = app.get(url, headers = navHeaders()).document

        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: "Serie"

        val content = doc.selectFirst("div.entry-content") ?: doc.body()

        // Poster: prima immagine dentro il contenuto
        val poster = content.select("img").firstOrNull()?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }

        // Sinossi: tutto il testo del contenuto PRIMA di "Link disponibili",
        // senza l'accordion degli episodi (parte nascosta della sinossi compresa)
        val plot = extractPlot(content)

        val episodes = parseEpisodes(content)

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.showStatus = ShowStatus.Ongoing
        }
    }

    /** Scheda leggera per la home: solo titolo e poster. */
    private suspend fun loadShowCard(url: String): SearchResponse {
        val doc = app.get(url, headers = navHeaders()).document
        val title = doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: name
        val poster = doc.selectFirst("div.entry-content img")?.attr("abs:src")
            ?.takeIf { it.isNotBlank() }
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    /** Testo della sinossi: copia del contenuto senza accordion/pulsanti, troncato a "Link disponibili". */
    private fun extractPlot(content: Element): String {
        val clone = content.clone()
        clone.select("div.su-accordion, div.su-spoiler, button, script, style").remove()
        var text = clone.wholeText()
        val cut = text.indexOf("Link disponibili")
        if (cut >= 0) text = text.substring(0, cut)
        return text.replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{2,}"), "\n")
            .trim()
    }

    // ------------------------------------------------------------------
    //  EPISODI: parsing di su-spoiler (una stagione ciascuno)
    // ------------------------------------------------------------------

    private fun parseEpisodes(content: Element): List<Episode> {
        val episodes = mutableListOf<Episode>()
        content.select("div.su-spoiler").forEach { spoiler ->
            val seasonTitle = spoiler.selectFirst(".su-spoiler-title")?.text() ?: ""
            val seasonHint = Regex("stagione\\s+(\\d+)", RegexOption.IGNORE_CASE)
                .find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull()
            val body = spoiler.selectFirst(".su-spoiler-content") ?: return@forEach

            // Divide il contenuto della stagione in righe sugli <br>
            val lines = mutableListOf<List<org.jsoup.nodes.Node>>()
            var current = mutableListOf<org.jsoup.nodes.Node>()
            body.childNodes().forEach { node ->
                if (node is Element && node.tagName() == "br") {
                    lines.add(current)
                    current = mutableListOf()
                } else {
                    current.add(node)
                }
            }
            lines.add(current)

            var fallbackEp = 0
            lines.forEach { nodes ->
                val label = StringBuilder()
                val links = mutableListOf<ShLink>()
                var seenAnchor = false
                nodes.forEach { node ->
                    when (node) {
                        is Element -> {
                            if (node.tagName() == "a") {
                                val href = node.attr("abs:href")
                                if (href.isNotBlank()) {
                                    links.add(ShLink(href, node.text().trim()))
                                    seenAnchor = true
                                }
                            } else if (!seenAnchor) {
                                label.append(node.text())
                            }
                        }
                        is TextNode -> if (!seenAnchor) label.append(node.text())
                        else -> {}
                    }
                }
                // Righe senza link = episodi non disponibili sul sito: saltate
                if (links.isNotEmpty()) {
                    var labelStr = label.toString()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .trimStart('-', '\u2013', '\u2014')
                        .trim()
                    var season = 0
                    var epNum = 0
                    val match = Regex("^(\\d+)\\s*[x\u00d7]\\s*(\\d+)\\s*").find(labelStr)
                    if (match != null) {
                        season = match.groupValues[1].toIntOrNull() ?: 0
                        epNum = match.groupValues[2].toIntOrNull() ?: 0
                        labelStr = labelStr.substring(match.range.last + 1).trim()
                            .trimStart('-', '\u2013', '\u2014')
                            .trim()
                    } else {
                        season = seasonHint ?: 0
                        fallbackEp++
                        epNum = fallbackEp
                    }
                    if (labelStr.isBlank()) labelStr = "Episodio $epNum"
                    episodes.add(
                        newEpisode(ShEpisodeData(season, labelStr, links).toJson()) {
                            this.name = labelStr
                            this.season = season
                            this.episode = epNum
                        }
                    )
                }
            }
        }
        return episodes
    }

    // ------------------------------------------------------------------
    //  LINK: risoluzione protettori + host video finali
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = tryParseJson<ShEpisodeData>(data) ?: return false
        // Tetto rigido per TUTTA la risoluzione (primo + secondo pass condividono
        // lo stesso budget): senza limite i tentativi di riserva possono catenare
        // decine di richieste e l'app resta "in caricamento" per minuti.
        val budget = ShBudget(
            maxRequests = 20,
            deadline = System.currentTimeMillis() + 80_000,
        )
        // Il challenge Cloudflare a volte viene risolto solo al secondo colpo:
        // se il primo pass non ha emesso nulla si ritenta con il budget residuo.
        return tryAllLinks(episode.links, subtitleCallback, callback, budget)
            || tryAllLinks(episode.links, subtitleCallback, callback, budget)
    }

    private suspend fun tryAllLinks(
        links: List<ShLink>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        budget: ShBudget,
    ): Boolean {
        var found = false
        for (link in links) {
            if (link.url.isBlank()) continue
            val ok = runCatching {
                resolveAndExtract(link.url, subtitleCallback, callback, budget)
            }.getOrDefault(false)
            if (ok) found = true
        }
        return found
    }

    /** Risolve l'eventuale protettore e delega all'estrattore dell'host finale. */
    private suspend fun resolveAndExtract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        budget: ShBudget,
    ): Boolean {
        if (!budget.can()) return false
        var emitted = false
        val wrapped: (ExtractorLink) -> Unit = { l ->
            emitted = true
            callback(l)
        }
        // 1. Risoluzione HTTP veloce (AJAX / redirect / parsing), poi ultima
        //    spiaggia: WebView invisibile che esegue il JavaScript del protettore
        //    esattamente come fa il browser (challenge Cloudflare, countdown,
        //    redirect JS) e si ferma sul primo host video incontrato.
        val finalUrl = walkProtector(url, 0, budget) ?: webviewResolve(url, budget) ?: return false
        when {
            finalUrl.contains("maxstream", ignoreCase = true) ->
                MaxStreamExtractor().getUrl(finalUrl, mainUrl, subtitleCallback, wrapped)
            finalUrl.contains("mixdrop", ignoreCase = true) ->
                MixDropExtractor().getUrl(finalUrl, mainUrl, subtitleCallback, wrapped)
            finalUrl.contains("deltabit", ignoreCase = true) ->
                DeltaBitExtractor().getUrl(finalUrl, mainUrl, subtitleCallback, wrapped)
            else -> {
                // Host non noto: prova gli estrattori registrati dall'app, poi il link diretto
                val handled = runCatching {
                    loadExtractor(finalUrl, mainUrl, subtitleCallback, wrapped)
                }.getOrDefault(false)
                if (!handled) emitDirectLink(finalUrl, wrapped)
            }
        }
        // 2. Se l'estrattore HTTP dell'host finale non ha emesso nulla (es. il
        //    player richiede JS), WebView profonda fino al file video vero e proprio
        if (!emitted && budget.can()) {
            val direct = webviewResolve(finalUrl, budget, videoOnly = true)
            if (direct != null) emitDirectLink(direct, wrapped)
        }
        return emitted
    }

    /**
     * "Come il browser": una WebView invisibile naviga il link eseguendo TUTTO il
     * JavaScript (challenge Cloudflare, countdown, redirect costruiti via JS) e la
     * navigazione viene fermata alla prima richiesta utile:
     * - videoOnly=false: prima richiesta verso un host video (pagina player)
     * - videoOnly=true: primo file video (.m3u8/.mp4/.mkv) richiesto dal player
     * Se useOkhttp=false la WebView fa tutte le richieste da se', come un browser
     * vero (richiesto per Cloudflare, vedi documentazione WebViewResolver).
     */
    private suspend fun webviewResolve(url: String, budget: ShBudget, videoOnly: Boolean = false): String? {
        if (!budget.can()) return null
        val intercept = if (videoOnly) {
            Regex("\\.m3u8|\\.mp4|\\.mkv", RegexOption.IGNORE_CASE)
        } else {
            Regex("maxstream|deltabit|mixdrop|\\.m3u8|\\.mp4", RegexOption.IGNORE_CASE)
        }
        val resolver = WebViewResolver(
            interceptUrl = intercept,
            useOkhttp = false,
            timeout = 25_000L,
        )
        return runCatching {
            val (finalReq, _) = resolver.resolveUsingWebView(url, referer = mainUrl, requestCallBack = { true })
            finalReq?.url?.toString()
        }.getOrNull()
    }

    /**
     * Cammina la catena dei protettori fino all'URL "foglia" (host video o pagina player).
     * uprot.net -> maxstream.video/uprots/... (o clicka/adelta), clicka.cc -> deltabit,
     * con livello captcha safego.cc quando presente.
     */
    private suspend fun walkProtector(url: String, hops: Int, budget: ShBudget): String? {
        if (hops > 6) return null
        return when {
            LEAF_HOSTS.any { url.contains(it, true) } -> url
            url.contains("uprot.net", true) -> walkUprot(url, hops, budget)
            url.contains("clicka.cc", true) || url.contains("safego.cc", true) -> walkClicka(url, hops, budget)
            else -> url // host diretto non noto: il chiamante prova gli estrattori dell'app
        }
    }

    /**
     * uprot.net: trucco MammaMia "msf -> mse" (la variante /mse/ NON mostra il captcha),
     * ritentando anche l'URL originale /msf/ se la variante non risponde; poi 30x o
     * pagina con anchor CONTINUE verso maxstream/uprots o clicka/adelta.
     */
    private suspend fun walkUprot(url: String, hops: Int, budget: ShBudget): String? {
        val probe = if (url.contains("/msf/")) url.replace("/msf/", "/mse/") else url
        for (candidate in listOf(probe, url).distinct()) {
            attemptUprot(candidate, hops, budget)?.let { return it }
        }
        return null
    }

    private suspend fun attemptUprot(probe: String, hops: Int, budget: ShBudget): String? {
        val headers = fullHeaders(referer = "https://uprot.net/")
        val resp = getNoRedirect(probe, headers, budget) ?: return null

        val location = resp.headers["location"]
        if (!location.isNullOrBlank()) {
            return walkProtector(absolutize(probe, location), hops + 1, budget)
        }

        val html = resp.text
        if (isCaptchaPage(html)) return null
        followContinue(html, probe, hops, budget)?.let { return it }
        // Fallback: primo URL uprots/adelta grezzo nel body
        UPROTS_RE.find(html)?.value?.let { return walkProtector(it, hops + 1, budget) }
        ADELTA_RE.find(html)?.value?.let { return walkProtector(it, hops + 1, budget) }
        extractDestination(html)?.let { return walkProtector(absolutize(probe, it), hops + 1, budget) }
        // Ultima riserva SOLO vicino alla radice: primi anchor generici della
        // pagina (con hops bassi la ramificazione resta limitata dal budget)
        if (hops <= 1) {
            val anchors = ANCHOR_RE.findAll(html)
                .map { it.groupValues[1] }
                .filter { it.startsWith("http") && it != probe }
                .take(2)
                .toList()
            for (href in anchors) {
                walkProtector(href, hops + 1, budget)?.let { return it }
            }
        }
        return null
    }

    /**
     * Via AJAX del protettore clicka.cc (stessa piattaforma di stayonline, viste
     * in produzione nel plugin italiano MultiSite): POST /ajax/linkEmbedView.php
     * con id=<token> risponde JSON {"data":{"value":"<url>"}} con il link FINALE,
     * saltando pagina HTML e challenge. Fallback sull'endpoint /ajax/linkView.php.
     */
    private suspend fun clickaAjax(url: String, budget: ShBudget): String? {
        val id = url.trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return null
        val body = "id=$id&ref=".toRequestBody(
            "application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull()
        )
        for (endpoint in listOf("linkEmbedView.php", "linkView.php")) {
            if (!budget.can() || !budget.mark("https://clicka.cc/ajax/$endpoint?id=$id")) continue
            budget.used++
            val resp = runCatching {
                app.post(
                    "https://clicka.cc/ajax/$endpoint",
                    headers = fullHeaders(referer = url, origin = "https://clicka.cc")
                        .plus(
                            mapOf(
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Sec-Fetch-Dest" to "empty",
                                "Sec-Fetch-Mode" to "cors",
                            )
                        ),
                    requestBody = body,
                    timeout = 10_000,
                    interceptor = cfKiller,
                )
            }.getOrNull() ?: continue
            val value = runCatching {
                JSONObject(resp.text).optJSONObject("data")?.optString("value") ?: ""
            }.getOrDefault("")
            if (value.startsWith("http")) return value
        }
        return null
    }

    /** clicka.cc (e safego.cc): via AJAX, poi 30x verso safego/adelta/deltabit, poi pagina HTML. */
    private suspend fun walkClicka(url: String, hops: Int, budget: ShBudget): String? {
        // 1. AJAX: la risposta e' il link finale (deltabit/mixdrop/maxstream)
        clickaAjax(url, budget)?.let { return walkProtector(it, hops + 1, budget) }

        // 2. GET senza redirect: 30x con Location verso la catena
        val resp = getNoRedirect(url, fullHeaders(referer = url, origin = "https://clicka.cc"), budget)
            ?: return null
        val location = resp.headers["location"]
        if (!location.isNullOrBlank()) {
            val next = absolutize(url, location)
            if (next.contains("safego", ignoreCase = true)) {
                return walkSafego(next, hops, budget)
            }
            return walkProtector(next, hops + 1, budget)
        }

        val html = resp.text
        if (isCaptchaPage(html)) return null
        ADELTA_RE.find(html)?.value?.let { return walkProtector(it, hops + 1, budget) }
        followContinue(html, url, hops, budget)?.let { return it }
        extractDestination(html)?.let { return walkProtector(absolutize(url, it), hops + 1, budget) }
        return null
    }

    /** Livello captcha safego.cc: se l'IP e gia noto, reindirizza o contiene il link adelta. */
    private suspend fun walkSafego(url: String, hops: Int, budget: ShBudget): String? {
        val resp = getNoRedirect(url, fullHeaders(referer = url, origin = "https://safego.cc"), budget)
            ?: return null
        val location = resp.headers["location"]
        if (!location.isNullOrBlank()) {
            return walkProtector(absolutize(url, location), hops + 1, budget)
        }
        val html = resp.text
        if (isCaptchaPage(html)) return null
        ADELTA_RE.find(html)?.value?.let { return walkProtector(it, hops + 1, budget) }
        followContinue(html, url, hops, budget)?.let { return it }
        return null
    }

    /**
     * Anchor con testo CONTINUE: quello reale punta a maxstream/clicka/uprots/adelta
     * (le pagine possono contenere URL esca nascosti, quindi hanno priorita' i filtrati).
     */
    private suspend fun followContinue(html: String, baseUrl: String, hops: Int, budget: ShBudget): String? {
        val filtered = mutableListOf<String>()
        val generic = mutableListOf<String>()
        ANCHOR_RE.findAll(html).forEach { m ->
            val href = m.groupValues[1]
            val text = m.groupValues[2].replace(Regex("\\s+"), "").uppercase()
            if (text.contains("CONTINUE")) {
                if (listOf("maxstream", "clicka", "uprots", "adelta", "safego")
                        .any { href.contains(it, true) }
                ) filtered.add(href) else generic.add(href)
            }
        }
        for (href in filtered + generic) {
            val dest = walkProtector(absolutize(baseUrl, href), hops + 1, budget)
            if (dest != null) return dest
        }
        return null
    }

    /** Pagina captcha (immagine base64 senza nessun link utile): inutile insistere. */
    private fun isCaptchaPage(body: String): Boolean {
        val hasImage = body.contains("data:image", ignoreCase = true)
        val hasTarget = UPROTS_RE.containsMatchIn(body) || ADELTA_RE.containsMatchIn(body)
        return hasImage && !hasTarget
    }

    /** Se la catena finisce dritto su un file video, lo emette senza estrattore. */
    private suspend fun emitDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val lower = url.substringBefore('?').lowercase()
        val type = when {
            lower.endsWith(".m3u8") -> com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
            lower.endsWith(".mp4") || lower.endsWith(".mkv") ->
                com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
            else -> return
        }
        callback(
            com.lagradost.cloudstream3.utils.newExtractorLink(
                source = name,
                name = name,
                url = url,
                type = type,
            ) {
                this.referer = ""
                this.quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
            }
        )
    }

    /** Cerca nel codice HTML una destinazione (meta refresh / JS / anchor verso host noto). */
    private fun extractDestination(html: String): String? {
        Regex(
            "http-equiv\\s*=\\s*[\"']refresh[\"'][^>]*content\\s*=\\s*[\"'][^\"']*url=([^\"'>]+)",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()?.let { return it }
        Regex(
            "content\\s*=\\s*[\"'][^\"']*url=([^\"'>]+)[\"'][^>]*http-equiv\\s*=\\s*[\"']refresh[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()?.let { return it }
        Regex(
            "(?:window\\.)?location(?:\\.href|\\.replace\\(\\s*)?\\s*[=(]\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()?.let { if (it.startsWith("http")) return it }
        Regex(
            "https?://[\\w.-]*(?:maxstream|mixdrop|deltabit|streamtape|voe)[\\w.-]*/[^\"'<>\\s]*",
            RegexOption.IGNORE_CASE
        ).find(html)?.value?.let { return it }
        return null
    }

    /** Risolve href relativi rispetto alla pagina corrente. */
    private fun absolutize(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val baseHttpUrl = base.toHttpUrlOrNull() ?: return href
        return baseHttpUrl.resolve(href)?.toString() ?: href
    }
}
