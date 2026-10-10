package it.retrocinema.sisterhappy

import com.lagradost.api.Log
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
 * Punti chiave della v8 (diagnostica sul campo):
 * - RICERCA MONO-SERIE: il plugin risponde solo con la sua serie, mai risultati
 *   estranei del sito (il tester interno dell'app prova il PRIMO risultato di
 *   ricerca e il PRIMO episodio: con risultati estranei testava pagine sbagliate).
 * - EPISODI IN ORDINE NUOVA-STAGIONE-PRIMA: il primo episodio caricato e' quello
 *   della stagione corrente, che ha i link uprot -> MaxStream (il canale
 *   affidabile); le righe vecchie con soli link clicka finiscono in fondo.
 * - COOKIE A MANO: il client HTTP dell'app NON ha un cookie jar (verificato nel
 *   sorgente CloudStream/nicehttp): i cookie ricevuti da ogni pagina vengono
 *   raccolti e rinviati esplicitamente alle richieste successive, come fa il
 *   browser. Senza questo la POST AJAX dei protettori parte "a mani vuote".
 * - REDIRECT A MANO nell'estrattore MaxStream: ogni salto raccoglie i cookie.
 * - ESTRAZIONE GENERICA: se la catena arriva su una pagina sconosciuta, il
 *   plugin la scarica e ricava l'm3u8/mp4 da solo (regex in cascata).
 * - Niente WebView: solo richieste HTTP pure con tetto temporale globale.
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
        private val LEAF_HOSTS = listOf("maxstream", "deltabit", "mixdrop", "mxdrop")

        /** Tempo massimo dell'intera risoluzione di un episodio. */
        private const val DEADLINE_MS = 45_000L

        /** Timeout di ogni singola richiesta HTTP, in SECONDI (l'unita' di nicehttp). */
        private const val HTTP_TIMEOUT_S = 8L

        /** URL verso un host video noto, in qualunque parte del codice HTML. */
        private val KNOWN_HOST_RE = Regex(
            "https?://[\\w.-]*(?:maxstream|deltabit|mixdrop|mxdrop)[\\w.-]*/[^\\s\"'<>]+",
            RegexOption.IGNORE_CASE
        )

        /** File video diretto (m3u8/mp4/mkv) in qualunque parte del codice HTML. */
        private val MEDIA_RE = Regex(
            "https?://[^\\s\"'<>]+\\.(?:m3u8|mp4|mkv)[^\\s\"'<>]*",
            RegexOption.IGNORE_CASE
        )

        /**
         * Anchor "Continue" delle pagine uprot: gestito via jsoup in parseUprotPage
         * (testo anche spaziato "C O N T I N U E"). Nessun DOT_MATCHES_ALL.
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

        /** Tag per i log diagnostici (visibili nel visualizzatore log dell'app). */
        private const val TAG = "SisterHappy"
    }

    private fun navHeaders(referer: String? = null): Map<String, String> {
        val base = mapOf(
            "User-Agent" to SH_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.5",
        )
        return if (referer != null) base + mapOf("Referer" to referer) else base
    }

    /** GET con header da browser, cookie opzionale e timeout corretto; mai lancia, al piu' null. */
    private suspend fun shGet(url: String, referer: String? = null, cookie: String? = null) = runCatching {
        val headers = if (cookie != null) navHeaders(referer) + mapOf("Cookie" to cookie)
        else navHeaders(referer)
        app.get(url, headers = headers, timeout = HTTP_TIMEOUT_S)
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
    //  RICERCA: solo la serie gestita dal plugin
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        // Plugin mono-serie: risponde SOLO con la sua pagina, e solo se la
        // query e' coerente col titolo (lettere, spazi ignorati, cifre escluse:
        // "x factor 20" conta come "xfactor"). Mai risultati estranei del sito:
        // il tester dell'app prova il primo risultato trovato.
        val q = query.lowercase().filter { it.isLetter() }.replace(" ", "")
        val slug = SHOW_PATH.filter { it.isLetter() }
        if (q.isEmpty() || !slug.contains(q)) return emptyList()
        return runCatching { listOf(loadShowCard(mainUrl + SHOW_PATH)) }
            .getOrDefault(emptyList())
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

        // Stagione piu' recente per prima: e' quella che si guarda adesso e
        // contiene i link uprot -> MaxStream (canale affidabile). Il tester
        // dell'app prova il primo episodio caricato: cosi' prova il migliore.
        val episodes = parseEpisodes(content)
            .sortedWith(compareByDescending<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.showStatus = ShowStatus.Ongoing
        }
    }

    /** Scheda leggera per la home e la ricerca: solo titolo e poster. */
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

        // I link vengono risolti in parallelo: ogni catena e' lineare e corta,
        // quindi il tempo totale resta sotto il tetto.
        val results = ordered.amap { link ->
            if (System.currentTimeMillis() >= deadline || link.url.isBlank()) false
            else runCatching {
                resolveAndExtract(link.url, deadline, subtitleCallback, callback)
            }.onFailure { Log.e(TAG, "errore su ${link.url}: ${it.message}") }.getOrDefault(false)
        }
        val ok = results.any { it }
        Log.i(TAG, "loadLinks finito: $ok su ${ordered.size} link")
        return ok
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
            else -> url // host diretto non noto: si prova comunque l'estrazione
        } ?: return false

        Log.i(TAG, "host finale raggiunto: $target")
        extractFinal(target, deadline, subtitleCallback, wrapped)
        return emitted
    }

    /**
     * uprot.net: GET della pagina e lettura dell'anchor "Continue" verso l'host video.
     * Prima la variante /mse/ (senza captcha, trucco noto), poi l'originale /msf/.
     * I cookie ricevuti dalla pagina vengono rinviati alle richieste seguenti
     * (il client dell'app non ha cookie jar). Se il Continue rimanda a un'altra
     * pagina uprot si segue per al massimo 2 salti.
     */
    private suspend fun resolveUprot(url: String, deadline: Long): String? {
        var current = url
        for (hop in 0 until 3) {
            if (System.currentTimeMillis() >= deadline) return null
            val candidates = if (current.contains("/msf/", ignoreCase = true))
                listOf(current.replace("/msf/", "/mse/", ignoreCase = true), current)
            else listOf(current)

            var next: String? = null
            var jar: Map<String, String> = emptyMap()
            for (candidate in candidates) {
                val resp = shGet(candidate, referer = mainUrl + SHOW_PATH, cookie = shCookieHeader(jar))
                if (resp == null) {
                    Log.i(TAG, "uprot: richiesta fallita (DNS/rete/timeout) $candidate")
                    continue
                }
                Log.i(TAG, "uprot: HTTP ${resp.code} su $candidate")
                if (resp.code == 403) {
                    Log.i(TAG, "uprot: blocco 403 (WAF/bot) su $candidate")
                    continue
                }
                jar = jar + resp.cookies
                next = parseUprotPage(resp.text, resp.document)
                if (next == null) {
                    Log.i(TAG, "uprot: nessun Continue/host trovato nella pagina $candidate")
                    continue
                }
                break
            }
            if (next == null) return null
            // Si accetta qualunque destinazione http (anche dominio nuovo):
            // l'estrazione finale generica la sa gestire. Se e' ancora uprot si continua.
            if (!next.contains("uprot.net", ignoreCase = true)) return next
            current = next
        }
        return null
    }

    /**
     * Parsing della pagina uprot. Prima via jsoup: anchor con testo "Continue"
     * (anche con lettere spaziate "C O N T I N U E", come su MammaMia) — vale
     * l'ULTIMO anchor Continue, come fa MammaMia. Poi file video diretto nel
     * sorgente, regex grezza, URL host noto, anchor generici, redirect JS/meta.
     */
    private fun parseUprotPage(html: String, doc: Element): String? {
        var continueHref: String? = null
        for (a in doc.select("a[href]")) {
            val text = a.text().replace(Regex("\\s+"), "").uppercase()
            if (text.contains("CONTINUE")) continueHref = a.attr("abs:href")
        }
        continueHref?.let { href ->
            if (href.startsWith("http")) {
                Log.i(TAG, "uprot: anchor Continue -> $href")
                return href
            }
        }
        // Ricava il video da solo: m3u8/mp4 gia' presenti nel sorgente pagina
        MEDIA_RE.find(html)?.value?.let {
            Log.i(TAG, "uprot: file video diretto nel sorgente")
            return it
        }
        // Riserva: regex grezza sulla stessa riga (page con markup semplice)
        CONTINUE_RE.findAll(html).forEach { m ->
            val href = m.groupValues[1].trim()
            if (href.startsWith("http")) return href
        }
        KNOWN_HOST_RE.find(html)?.value?.let {
            Log.i(TAG, "uprot: URL host noto nel sorgente -> $it")
            return it
        }
        for (a in doc.select("a[href]")) {
            val href = a.attr("abs:href")
            if (href.startsWith("http") && isLeafHost(href)) {
                Log.i(TAG, "uprot: anchor host noto -> $href")
                return href
            }
        }
        return htmlRedirect(html)
    }

    /**
     * clicka.cc: la pagina HTML e' spesso dietro Cloudflare, ma il flusso
     * browser e' replicabile: 1) GET della pagina per raccogliere i cookie
     * (il client dell'app non ha cookie jar, vanno rinviati a mano);
     * 2) POST /ajax/linkEmbedView.php con id=<token> (ultimo segmento del
     * path) e i cookie della pagina -> JSON {"data":{"value":"<url>"}};
     * 3) fallback /ajax/linkView.php. Se "value" non e' un URL si cerca
     * dentro redirect o file video.
     */
    private suspend fun resolveClicka(url: String, deadline: Long): String? {
        val id = url.trimEnd('/').substringAfterLast('/')
        if (id.isBlank()) return null

        // 1) GET della pagina: raccoglie i cookie e a volte contiene gia' la destinazione
        var jar: Map<String, String> = emptyMap()
        val page = shGet(url, referer = mainUrl + SHOW_PATH)
        if (page == null) {
            Log.i(TAG, "clicka: GET pagina fallito (DNS/rete/timeout) $url")
        } else {
            Log.i(TAG, "clicka: GET pagina HTTP ${page.code}")
            jar = page.cookies
            if (page.code == 200) {
                KNOWN_HOST_RE.find(page.text)?.value?.let { return it }
                MEDIA_RE.find(page.text)?.value?.let { return it }
                htmlRedirect(page.text)?.let { return it }
            }
        }

        // 2) AJAX con i cookie della pagina, come fa il browser
        for (endpoint in listOf("linkEmbedView.php", "linkView.php")) {
            if (System.currentTimeMillis() >= deadline) return null
            val body = "id=$id&ref=".toRequestBody(
                "application/x-www-form-urlencoded; charset=UTF-8".toMediaTypeOrNull()
            )
            val headers = navHeaders(null) + mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Origin" to "https://clicka.cc",
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest",
            ) + (shCookieHeader(jar)?.let { mapOf("Cookie" to it) } ?: emptyMap())
            val resp = runCatching {
                app.post(
                    "https://clicka.cc/ajax/$endpoint",
                    headers = headers,
                    requestBody = body,
                    timeout = HTTP_TIMEOUT_S,
                )
            }.getOrNull()
            if (resp == null) {
                Log.i(TAG, "clicka AJAX: richiesta fallita $endpoint")
                continue
            }
            Log.i(TAG, "clicka AJAX: HTTP ${resp.code} da $endpoint (cookie: ${jar.size})")
            if (resp.code != 200) continue
            // Se Cloudflare intercetta la POST la risposta e' HTML, non JSON:
            // JSONObject lancia e si passa all'endpoint successivo.
            val value = runCatching {
                JSONObject(resp.text).optJSONObject("data")?.optString("value") ?: ""
            }.getOrDefault("")
            if (value.startsWith("http")) return value
            if (value.isNotBlank()) {
                htmlRedirect(value)?.let { return it }
                MEDIA_RE.find(value)?.value?.let { return it }
            }
        }
        return null
    }

    /** Redirect dentro l'HTML: meta refresh o location.href/location.replace. */
    private fun htmlRedirect(html: String): String? {
        META_REFRESH_RE.find(html)?.groupValues?.get(1)?.trim()?.let { if (it.startsWith("http")) return it }
        JS_LOCATION_RE.find(html)?.groupValues?.get(1)?.trim()?.let { if (it.startsWith("http")) return it }
        return null
    }

    /** Manda l'URL finale all'estrattore dell'host giusto (o all'estrazione generica). */
    private suspend fun extractFinal(
        url: String,
        deadline: Long,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // File video diretto: emesso subito, senza estrattore
        if (emitDirectLink(url, callback)) return
        when {
            url.contains("maxstream", ignoreCase = true) ->
                MaxStreamExtractor(deadline).getUrl(url, mainUrl, subtitleCallback, callback)
            url.contains("mixdrop", ignoreCase = true) ||
                url.contains("mxdrop", ignoreCase = true) ->
                MixDropExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            url.contains("deltabit", ignoreCase = true) ->
                DeltaBitExtractor().getUrl(url, mainUrl, subtitleCallback, callback)
            else -> {
                // Host non noto: prova gli estrattori registrati dall'app, poi l'estrazione generica
                val handled = runCatching {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }.getOrDefault(false)
                if (!handled) selfExtract(url, callback)
            }
        }
    }

    /**
     * Estrazione generica ("ricava i m3u8 da solo"): scarica la pagina finale e
     * cerca al suo interno il file video con le stesse regex a cascata degli
     * estrattori dedicati. Copre i domini nuovi o cambiati senza toccare il codice.
     */
    private suspend fun selfExtract(url: String, callback: (ExtractorLink) -> Unit) {
        val resp = shGet(url, referer = mainUrl + SHOW_PATH)
        if (resp == null) {
            Log.i(TAG, "selfExtract: richiesta fallita $url")
            return
        }
        Log.i(TAG, "selfExtract: HTTP ${resp.code} su $url")
        if (resp.code != 200) return
        val body = resp.text
        shFindM3u8(body)?.let {
            Log.i(TAG, "selfExtract: m3u8 ricavato dalla pagina")
            emitDirectLink(it, callback)
            return
        }
        MEDIA_RE.find(body)?.value?.let { emitDirectLink(it, callback) }
    }

    /** Se l'URL e' un file video diretto lo emette senza estrattore; dice se l'ha fatto. */
    private suspend fun emitDirectLink(
        url: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val lower = url.substringBefore('#').substringBefore('?').lowercase()
        val type = when {
            lower.endsWith(".m3u8") -> com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8
            lower.endsWith(".mp4") || lower.endsWith(".mkv") ->
                com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
            else -> return false
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
        return true
    }
}
