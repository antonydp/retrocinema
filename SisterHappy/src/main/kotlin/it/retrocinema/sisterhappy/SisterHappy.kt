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
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
 * (redirect 302 -> form POST -> meta refresh / JS) e delega all'host video finale.
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

        /** Host che mascherano i link veri (protettori). */
        private val PROTECTOR_HOSTS = listOf("uprot.net", "clicka.cc")

        /** Prefissi di pagina del sito che NON sono pagine serie: tolti dalla ricerca. */
        private val NON_SHOW_PREFIXES = listOf(
            "category", "elenco-", "guida-", "richieste", "nuovi-ep", "tag", "author", "page"
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
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        val episode = tryParseJson<ShEpisodeData>(data) ?: return false
        var found = false
        for (link in episode.links) {
            if (link.url.isBlank()) continue
            var emitted = false
            val wrapped: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit = { l ->
                emitted = true
                found = true
                callback(l)
            }
            runCatching {
                resolveAndExtract(link.url, subtitleCallback, wrapped)
            }
            // Un host riuscito basta? No: si provano TUTTI i link dell'episodio,
            // l'app mostra sorgenti multiple e sceglie la migliore.
            if (emitted) continue
        }
        return found
    }

    /** Risolve l'eventuale protettore e delega all'estrattore dell'host finale. */
    private suspend fun resolveAndExtract(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit,
    ) {
        val finalUrl = if (isProtector(url)) resolveProtector(url) ?: return else url
        when {
            finalUrl.contains("maxstream", ignoreCase = true) ->
                MaxStreamExtractor().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
            finalUrl.contains("mixdrop", ignoreCase = true) ->
                MixDropExtractor().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
            finalUrl.contains("deltabit", ignoreCase = true) ->
                DeltaBitExtractor().getUrl(finalUrl, mainUrl, subtitleCallback, callback)
            else -> emitDirectLink(finalUrl, callback)
        }
    }

    /** Se il protettore reindirizza dritto a un file video, lo emette senza estrattore. */
    private suspend fun emitDirectLink(
        url: String,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit,
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

    private fun isProtector(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return PROTECTOR_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Risolutore multi-strategia per i protettori di link (uprot.net, clicka.cc):
     * 1. catena di redirect 302 (i protettori spesso reindirizzano subito all'host);
     * 2. pagina HTML: meta refresh, location.replace / location.href, anchor verso host video;
     * 3. form POST con campi nascosti (pulsante "continua") -> si posta e si ricomincia.
     * Con bypass Cloudflare per la pagina interstiziale.
     */
    private suspend fun resolveProtector(url: String): String? {
        val referer = mainUrl + SHOW_PATH

        // --- Strategia 1: catena di redirect senza seguire i redirect automaticamente
        var current = url
        var hops = 0
        while (hops < 6) {
            val resp = runCatching {
                app.get(
                    current,
                    allowRedirects = false,
                    headers = navHeaders(referer),
                    timeout = 15_000,
                    interceptor = cfKiller,
                )
            }.getOrNull() ?: return null
            val location = resp.headers["location"]
            if (location.isNullOrBlank()) break
            val next = absolutize(current, location)
            if (!isProtector(next)) return next
            current = next
            hops++
        }

        // --- Strategia 2: pagina HTML (meta refresh / JS / anchor / form POST)
        val page = runCatching {
            app.get(
                current,
                headers = navHeaders(referer),
                timeout = 20_000,
                interceptor = cfKiller,
            )
        }.getOrNull() ?: return null
        val html = page.text

        extractDestination(html)?.let { return it }

        // --- Strategia 3: form POST con campi nascosti
        val form = parseHiddenForm(html)
        if (form != null) {
            val (action, fields) = form
            val postUrl = if (action.isBlank()) current else absolutize(current, action)
            val postHeaders = navHeaders(referer).toMutableMap()
            postHeaders["Content-Type"] = "application/x-www-form-urlencoded"
            current.toHttpUrlOrNull()?.let { postHeaders["Origin"] = "${it.scheme}://${it.host}" }
            val postResp = runCatching {
                app.post(
                    postUrl,
                    data = fields,
                    headers = postHeaders,
                    timeout = 20_000,
                    interceptor = cfKiller,
                )
            }.getOrNull() ?: return null
            extractDestination(postResp.text)?.let { return it }
            val loc = postResp.headers["location"]
            if (!loc.isNullOrBlank() && !isProtector(absolutize(postUrl, loc))) {
                return absolutize(postUrl, loc)
            }
        }
        return null
    }

    /** Cerca nel codice HTML la destinazione reale del protettore. */
    private fun extractDestination(html: String): String? {
        // meta refresh: <meta http-equiv="refresh" content="5; url=https://...">
        Regex(
            "http-equiv\\s*=\\s*[\"']refresh[\"'][^>]*content\\s*=\\s*[\"'][^\"']*url=([^\"'>]+)",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()?.let { return it }
        Regex(
            "content\\s*=\\s*[\"'][^\"']*url=([^\"'>]+)[\"'][^>]*http-equiv\\s*=\\s*[\"']refresh[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()?.let { return it }

        // JS: location.replace("...") / location.href = "..." / window.location = "..."
        Regex(
            "(?:window\\.)?location(?:\\.href|\\.replace\\(\\s*)?\\s*[=(]\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)?.trim()?.let { if (it.startsWith("http")) return it }

        // Anchor o URL grezzo verso un host video noto
        val hostRe = Regex(
            "https?://[\\w.-]*(?:maxstream|mixdrop|deltabit)[\\w.-]*/[^\"'<>\\s]*",
            RegexOption.IGNORE_CASE
        )
        hostRe.find(html)?.value?.let { return it }

        return null
    }

    /** Estrae la prima form POST della pagina con i suoi campi nascosti. */
    private fun parseHiddenForm(html: String): Pair<String, Map<String, String>>? {
        val formRe = Regex(
            "<form[^>]*method\\s*=\\s*[\"']post[\"'][^>]*>",
            RegexOption.IGNORE_CASE
        )
        val open = formRe.find(html) ?: return null
        val tag = open.value
        val action = Regex("action\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.trim() ?: ""
        val end = html.indexOf("</form>", open.range.last)
        if (end < 0) return null
        val inner = html.substring(open.range.last, end)
        val fields = mutableMapOf<String, String>()
        Regex(
            "<input[^>]*type\\s*=\\s*[\"']hidden[\"'][^>]*>",
            RegexOption.IGNORE_CASE
        ).findAll(inner).forEach { inputTag ->
            val name = Regex("name\\s*=\\s*[\"']([^\"']+)[\"']")
                .find(inputTag.value)?.groupValues?.get(1) ?: return@forEach
            val value = Regex("value\\s*=\\s*[\"']([^\"']*)[\"']")
                .find(inputTag.value)?.groupValues?.get(1) ?: ""
            fields[name] = value
        }
        if (fields.isEmpty()) return null
        return action to fields
    }

    /** Risolve href relativi rispetto alla pagina corrente. */
    private fun absolutize(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val baseHttpUrl = base.toHttpUrlOrNull() ?: return href
        return baseHttpUrl.resolve(href)?.toString() ?: href
    }
}
