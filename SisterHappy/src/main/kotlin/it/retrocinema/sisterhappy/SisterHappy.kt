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
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
 * SisterHappy - estensione dedicata a UNA sola serie TV italiana.
 *
 * La pagina del sito e una classica pagina "serie" WordPress:
 * - sinossi dentro div.entry-content (parte nascosta compresa)
 * - "Link disponibili:" seguito da div.su-accordion con un div.su-spoiler per stagione
 * - dentro ogni stagione, righe di testo separate da <br>:
 *     "15x01 Audizioni 1 - <a href=protettore>MaxStream</a> - <a>DeltaBit</a>"
 * - i link passano da protettori (uprot.net, clicka.cc) che nascondono gli host video
 *
 * Flusso loadLinks (v5, riscritto da capo, SOLO richieste HTTP pure - nessuna WebView):
 * 1. uprot.net  -> GET variante /mse/ poi /msf/ -> anchor "Continue" verso
 *    maxstream.video/uprots/<id> (schema ufficiale ShortLink.unshortenUprot di
 *    CloudStream, usato identico da CB01/Toonitalia) -> estrattore MaxStream.
 * 2. clicka.cc  -> POST /ajax/linkEmbedView.php con id=<token> -> JSON
 *    {"data":{"value":"<url host>"}}; fallback /ajax/linkView.php, poi parse pagina.
 * 3. L'host finale viene passato all'estrattore dedicato (m3u8/mp4) o a loadExtractor.
 *
 * Nota su clicka.cc: e' protetto da Cloudflare (ItalianCloudStream lo salta del tutto);
 * qui si tenta comunque la via AJAX che non carica la pagina HTML, ma il canale
 * PRIMARIO resta uprot -> MaxStream. Nessuna ramificazione di tentativi: ogni link
 * segue UNA catena lineare con tetto temporale globale.
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

        /** Host video finali riconosciuti come "foglia" della catena. */
        private val LEAF_HOSTS = listOf("maxstream", "deltabit", "mixdrop")

        /** Tempo massimo dell'intera risoluzione di un episodio. */
        private const val DEADLINE_MS = 45_000L

        /** Timeout di ogni singola richiesta HTTP, in SECONDI (l'unita' di nicehttp). */
        private const val HTTP_TIMEOUT_S = 8L

        /** Prefissi di pagina del sito che NON sono pagine serie: tolti dalla ricerca. */
        private val NON_SHOW_PREFIXES = listOf(
            "category", "elenco-", "guida-", "richieste", "nuovi-ep", "tag", "author", "page"
        )

        /** URL verso un host video noto, in qualunque parte del codice HTML. */
        private val KNOWN_HOST_RE = Regex(
            "https?://[\\w.-]*(?:maxstream|deltabit|mixdrop)[\\w.-]*/[^\\s\"'<>]+",
            RegexOption.IGNORE_CASE
        )

        /**
         * Anchor "Continue" delle pagine uprot: href catturato nel gruppo 1
         * (schema ufficiale: recloudstream UnshortenUrl.kt, Toonitalia, CB01).
         * Nessun DOT_MATCHES_ALL: il match resta sulla singola riga.
         */
        private val CONTINUE_RE = Regex("""<a[^>]+href="([^"]+)".*?Continue""", RegexOption.IGNORE_CASE)

        private val META_REFRESH_RE = Regex(
            "http-equiv\\s*=\\s*[\"']refresh[\"'][^>]*content\\s*=\\s*[\"'][^\"']*url=([^\"'>]+)",
            RegexOption.IGNORE_CASE
        )
        private val JS_LOCATION_RE = Regex(
            "(?:window\\.)?location(?:\\.href|\\.replace\\(\\s*)?\\s*[=(]\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )
    }

    private fun navHeaders(referer: String? = null): Map<String, String> {
        val base = mapOf(
            "User-Agent" to SH_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.5",
        )
        return if (referer != null) base + mapOf("Referer" to referer) else base
    }

    /** GET con header da browser e timeout corretto; mai lancia, al piu' null. */
    private suspend fun shGet(url: String, referer: String? = null) = runCatching {
        app.get(url, headers = navHeaders(referer), timeout = HTTP_TIMEOUT_S)
    }.getOrNull()

    private fun isLeafHost(url: String): Boolean = LEAF_HOSTS.any { url.contains(it, ignoreCase = true) }

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
    //  LINK: risoluzione lineare protettori -> host video (niente WebView)
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = tryParseJson<ShEpisodeData>(data) ?: return false
        if (episode.links.isEmpty()) return false

        // Il canale affidabile e' uprot -> MaxStream: va per primo.
        // clicka (DeltaBit/MixDrop) e' dietro Cloudflare: si tenta dopo, via AJAX.
        val ordered = episode.links.sortedBy { link ->
            when {
                link.url.contains("uprot.net", ignoreCase = true) -> 0
                else -> 1
            }
        }
        val deadline = System.currentTimeMillis() + DEADLINE_MS

        // I link vengono risolti in parallelo: ogni catena e' lineare e corta
        // (max 3 richieste), quindi il tempo totale resta sotto il tetto.
        val results = ordered.amap { link ->
            if (System.currentTimeMillis() >= deadline || link.url.isBlank()) false
            else runCatching {
                resolveAndExtract(link.url, deadline, subtitleCallback, callback)
            }.getOrDefault(false)
        }
        return results.any { it }
    }

    /** Risolve l'eventuale protettore con una catena lineare e delega all'estrattore finale. */
    private suspend fun resolveAndExtract(
        url: String,
        deadline: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false
        val wrapped: (ExtractorLink) -> Unit = { l ->
            emitted = true
            callback(l)
        }

        val target = when {
            isLeafHost(url) -> url
            url.contains("uprot.net", ignoreCase = true) -> resolveUprot(url, deadline)
            url.contains("clicka.cc", ignoreCase = true) ||
                url.contains("safego.cc", ignoreCase = true) -> resolveClicka(url, deadline)
            else -> url // host diretto non noto: si prova comunque loadExtractor
        } ?: return false

        extractFinal(target, subtitleCallback, wrapped)
        return emitted
    }

    /**
     * uprot.net: GET della pagina e lettura dell'anchor "Continue" verso l'host video.
     * Prima la variante /mse/ (senza captcha, trucco noto), poi l'originale /msf/.
     * Se il Continue rimanda a un'altra pagina uprot si segue per al massimo 2 salti.
     */
    private suspend fun resolveUprot(url: String, deadline: Long): String? {
        var current = url
        for (hop in 0 until 3) {
            if (System.currentTimeMillis() >= deadline) return null
            val candidates = if (current.contains("/msf/", ignoreCase = true))
                listOf(current.replace("/msf/", "/mse/", ignoreCase = true), current)
            else listOf(current)

            var next: String? = null
            for (candidate in candidates) {
                val resp = shGet(candidate, referer = mainUrl + SHOW_PATH) ?: continue
                next = parseUprotPage(resp.text) ?: continue
                break
            }
            if (next == null) return null
            if (!next.contains("uprot.net", ignoreCase = true)) return next
            current = next
        }
        return null
    }

    /** Parsing della pagina uprot: anchor Continue, poi URL host noto, poi redirect JS/meta. */
    private fun parseUprotPage(html: String): String? {
        CONTINUE_RE.findAll(html).forEach { m ->
            val href = m.groupValues[1].trim()
            if (href.startsWith("http") && (isLeafHost(href) || href.contains("uprot.net", true))) {
                return href
            }
        }
        KNOWN_HOST_RE.find(html)?.value?.let { return it }
        return htmlRedirect(html)
    }

    /**
     * clicka.cc: stessa piattaforma di stayonline. La pagina HTML e' dietro
     * Cloudflare, ma gli endpoint AJAX rispondono in JSON: POST con id=<token>
     * (ultimo segmento del path) e ritorna {"data":{"value":"<url finale>"}}.
     * Fallback: endpoint linkView.php, poi GET della pagina con parse.
     */
    private suspend fun resolveClicka(url: String, deadline: Long): String? {
        val id = url.trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return null

        for (endpoint in listOf("linkEmbedView.php", "linkView.php")) {
            if (System.currentTimeMillis() >= deadline) return null
            val body = "id=$id&ref=".toRequestBody(
                "application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull()
            )
            val resp = runCatching {
                app.post(
                    "https://clicka.cc/ajax/$endpoint",
                    headers = mapOf(
                        "User-Agent" to SH_UA,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Accept-Language" to "it-IT,it;q=0.9,en;q=0.8",
                        "Origin" to "https://clicka.cc",
                        "Referer" to url,
                        "X-Requested-With" to "XMLHttpRequest",
                    ),
                    requestBody = body,
                    timeout = HTTP_TIMEOUT_S,
                )
            }.getOrNull() ?: continue
            if (resp.code != 200) continue
            // Se Cloudflare intercetta la POST la risposta e' HTML, non JSON:
            // JSONObject lancia e si passa all'endpoint successivo.
            val value = runCatching {
                JSONObject(resp.text).optJSONObject("data")?.optString("value") ?: ""
            }.getOrDefault("")
            if (value.startsWith("http")) return value
        }

        // Ultima spiaggia: GET della pagina (redirect seguiti) e parse del corpo.
        if (System.currentTimeMillis() >= deadline) return null
        val page = shGet(url, referer = mainUrl + SHOW_PATH) ?: return null
        KNOWN_HOST_RE.find(page.text)?.value?.let { return it }
        return htmlRedirect(page.text)
    }

    /** Redirect dentro l'HTML: meta refresh o location.href/location.replace. */
    private fun htmlRedirect(html: String): String? {
        META_REFRESH_RE.find(html)?.groupValues?.get(1)?.trim()?.let { if (it.startsWith("http")) return it }
        JS_LOCATION_RE.find(html)?.groupValues?.get(1)?.trim()?.let { if (it.startsWith("http")) return it }
        return null
    }

    /** Manda l'URL finale all'estrattore dell'host giusto (o a quelli dell'app). */
    private suspend fun extractFinal(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        when {
            url.contains("maxstream", ignoreCase = true) ->
                MaxStreamExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            url.contains("mixdrop", ignoreCase = true) ->
                MixDropExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            url.contains("deltabit", ignoreCase = true) ->
                DeltaBitExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            else -> {
                // Host non noto: prova gli estrattori registrati dall'app, poi il file diretto
                val handled = runCatching {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }.getOrDefault(false)
                if (!handled) emitDirectLink(url, callback)
            }
        }
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
}
