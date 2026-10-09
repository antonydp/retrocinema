package it.retrocinema.streamingcommunity

import com.lagradost.cloudstream3.APIHolder.capitalize
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** User-Agent realistico: necessario per la sessione Inertia. */
const val SC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"

/**
 * StreamingCommunity - versione RetroCinema con homepage ESTREMAMENTE ricca.
 *
 * Cambia pochissimo rispetto al sito (stesse API Inertia, stesso player
 * VixCloud/VixSrc) ma la home ha oltre 30 file: slider ufficiali, nuove
 * uscite per anno, righe curate per i gusti di famiglia e tutti i generi.
 * OGNI riga di archivio ha lo scroll infinito (17 pagine da 60 titoli,
 * lo stesso tetto che il sito impone ai clienti web).
 *
 * Basato sul provider "HadEnough" di doGior (GPL-3.0).
 */
class StreamingCommunity : MainAPI() {
    // Domini in ordine di preferenza: il sito cambia spesso, si usa il primo vivo.
    private val domainCandidates = listOf(
        "https://streamingunity.win/",
        "https://streamingunity.vip/",
        "https://streamingcommunityz.red/",
    )

    private var siteRootUrl = domainCandidates[0]
    private var cdnHost = "cdn." + siteRootUrl.toHttpUrl().host
    private var inertiaVersion = ""
    private var decodedXsrfToken = ""
    private val sessionHeaders = mutableMapOf(
        "User-Agent" to SC_UA,
        "Cookie" to "",
        "X-Inertia" to "true",
        "X-Inertia-Version" to "",
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/plain, */*",
    )

    override var mainUrl = siteRootUrl + "it"
    override var name = "StreamingCommunity"
    override var supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Cartoon, TvType.Documentary)
    override val hasMainPage = true

    companion object {
        const val TAG = "SCommunityRetro"
        /** Tetto pagine per riga: oltre la 17 il sito risponde 400. */
        const val MAX_PAGE = 17
        /** Item per pagina dell'archivio: usato per capire se c'e una pagina dopo. */
        const val PAGE_SIZE = 60
    }

    // ------------------------------------------------------------------
    //  HOMEPAGE ESTREMAMENTE RICCA (31 righe, quasi tutte infinite)
    // ------------------------------------------------------------------
    override val mainPage = mainPageOf(
        // --- Slider ufficiali del sito ---
        sliderQuery("top10", "Top 10 di oggi"),
        sliderQuery("trending", "Tendenze di adesso"),
        sliderQuery("latest", "Aggiunti di recente"),
        sliderQuery("upcoming", "In arrivo al cinema"),
        // --- Nuove uscite per anno ---
        archiveQuery("Nuove uscite 2026", year = 2026),
        archiveQuery("Nuove uscite 2025", year = 2025),
        archiveQuery("Nuove uscite 2024", year = 2024),
        // --- Novita per i gusti di casa ---
        archiveQuery("Storie d'amore nuove 2026", genre = 15, year = 2026),
        archiveQuery("Commedie nuove 2026", genre = 12, year = 2026),
        archiveQuery("Famiglia e bambini 2026", genre = 16, year = 2026),
        // --- Le piu belle (ordinate per voto) ---
        archiveQuery("Le storie d'amore piu belle", genre = 15, sort = "score"),
        archiveQuery("Le commedie piu belle", genre = 12, sort = "score"),
        archiveQuery("L'animazione piu amata", genre = 19, sort = "score"),
        archiveQuery("Film per la famiglia piu amati", genre = 16, sort = "score"),
        // --- Tutti i generi (scroll infinito) ---
        archiveQuery("Commedie", genre = 12),
        archiveQuery("Storie d'amore", genre = 15),
        archiveQuery("Famiglia", genre = 16),
        archiveQuery("Animazione", genre = 19),
        archiveQuery("Avventura", genre = 11),
        archiveQuery("Azione", genre = 4),
        archiveQuery("Drammi", genre = 1),
        archiveQuery("Crime", genre = 2),
        archiveQuery("Mistero", genre = 6),
        archiveQuery("Fantascienza", genre = 10),
        archiveQuery("Fantasy", genre = 8),
        archiveQuery("Western", genre = 20),
        archiveQuery("Guerra", genre = 9),
        archiveQuery("Storia", genre = 22),
        archiveQuery("Musical e musica", genre = 14),
        archiveQuery("Documentari", genre = 24),
        archiveQuery("Film TV", genre = 21),
    )

    // ------------------------------------------------------------------
    //  Sessione Inertia (version + cookie XSRF) con failover dei domini
    // ------------------------------------------------------------------
    private suspend fun setupHeaders() {
        val candidates = listOf(siteRootUrl) + domainCandidates.filter { it != siteRootUrl }
        for (root in candidates) {
            val probe = runCatching {
                app.get(root + "it/archive", headers = mapOf("User-Agent" to SC_UA))
            }.getOrNull() ?: continue
            if (probe.code !in 200..299) continue

            val dataPage = runCatching { probe.document.select("#app").attr("data-page") }
                .getOrNull().orEmpty()
            val version = dataPage
                .substringAfter("\"version\":\"", "")
                .substringBefore("\"")
            if (version.isBlank()) continue

            // Dominio vivo: lo adottiamo (anche cdn e mainUrl)
            siteRootUrl = root
            cdnHost = "cdn." + root.toHttpUrl().host
            mainUrl = root + "it"

            val cookieJar = linkedMapOf<String, String>()
            probe.cookies.forEach { (k, v) -> cookieJar[k] = v }

            val csrf = runCatching {
                app.get(
                    root + "sanctum/csrf-cookie",
                    headers = mapOf(
                        "User-Agent" to SC_UA,
                        "Referer" to "$mainUrl/",
                        "X-Requested-With" to "XMLHttpRequest",
                    )
                )
            }.getOrNull()
            csrf?.cookies?.forEach { (k, v) -> cookieJar[k] = v }

            sessionHeaders["Cookie"] = cookieJar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            decodedXsrfToken = cookieJar["XSRF-TOKEN"]
                ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
                ?: ""
            inertiaVersion = version
            sessionHeaders["X-Inertia-Version"] = version
            return
        }
    }

    private fun sliderHeaders(): Map<String, String> = mapOf(
        "User-Agent" to SC_UA,
        "Cookie" to (sessionHeaders["Cookie"] ?: ""),
        "X-Requested-With" to "XMLHttpRequest",
        "X-XSRF-TOKEN" to decodedXsrfToken,
        "Referer" to "$mainUrl/",
        "Accept" to "application/json, text/plain, */*",
        "Content-Type" to "application/json",
        "Origin" to siteRootUrl.removeSuffix("/"),
    )

    private suspend fun ensureSession() {
        if (sessionHeaders["Cookie"].isNullOrBlank() || inertiaVersion.isBlank()) {
            setupHeaders()
        }
    }

    // ------------------------------------------------------------------
    //  Parser tolleranti: JSON paginator oppure HTML Inertia (data-page)
    // ------------------------------------------------------------------
    private fun isHtmlPayload(payload: String): Boolean {
        val trimmed = payload.trimStart()
        return trimmed.startsWith("<") || trimmed.contains("<!DOCTYPE", ignoreCase = true)
    }

    private fun extractInertiaPageJson(html: String): String? {
        val dataPageRaw = org.jsoup.Jsoup.parse(html).selectFirst("#app")?.attr("data-page")
        if (dataPageRaw.isNullOrBlank()) return null
        return Parser.unescapeEntities(dataPageRaw, true)
    }

    /** Estrae la lista di titoli da una risposta dell'archivio (JSON o HTML). */
    private fun parseArchiveTitles(payload: String): List<ScTitle> {
        if (payload.isBlank()) return emptyList()
        if (isHtmlPayload(payload)) {
            val json = extractInertiaPageJson(payload) ?: return emptyList()
            return runCatching { parseJson<ScInertiaResponse>(json) }
                .getOrNull()?.props?.titles ?: emptyList()
        }
        // JSON paginator diretto: {current_page, data, last_page}
        tryParseJson<ScPaginator>(payload)?.data?.let { return it }
        // A volte Inertia risponde con l'oggetto props completo
        return runCatching { parseJson<ScInertiaResponse>(payload) }
            .getOrNull()?.props?.titles ?: emptyList()
    }

    private fun searchResponseBuilder(listJson: List<ScTitle>): List<SearchResponse> =
        listJson.filter { it.type == "movie" || it.type == "tv" }.map { title ->
            val url = "$mainUrl/titles/${title.id}-${title.slug}"
            val poster = title.getPoster()?.let { "https://$cdnHost/images/$it" }
            if (title.type == "tv") {
                newTvSeriesSearchResponse(title.name, url) { posterUrl = poster }
            } else {
                newMovieSearchResponse(title.name, url) { posterUrl = poster }
            }
        }

    // ------------------------------------------------------------------
    //  HOMEPAGE
    // ------------------------------------------------------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val current = page.coerceAtLeast(1)
        val query = try {
            parseJson<ArchiveQuery>(request.data)
        } catch (e: Exception) {
            null
        } ?: return null

        ensureSession()

        return when (query.kind) {
            "slider" -> {
                val sliderName = query.slider ?: return null
                val body = "{\"sliders\":[{\"name\":\"$sliderName\",\"genre\":null}]}"
                val response = app.post(
                    "${siteRootUrl}api/sliders/fetch?lang=it",
                    headers = sliderHeaders(),
                    requestBody = body.toRequestBody()
                )
                val payload = response.body.string()
                if (isHtmlPayload(payload)) return null
                val slider = runCatching { parseJson<List<ScSlider>>(payload) }
                    .getOrNull()?.firstOrNull() ?: return null
                val items = searchResponseBuilder(slider.titles)
                if (items.isEmpty()) return null
                // Etichetta italiana nostra al posto di quella inglese del sito
                newHomePageResponse(
                    HomePageList(query.label, items, isHorizontalImages = false),
                    hasNext = false
                )
            }
            "archive" -> {
                val params = mutableMapOf(
                    "page" to current.toString(),
                    "lang" to "it",
                )
                query.genre?.let { params["genre[]"] = it.toString() }
                query.year?.let { params["year"] = it.toString() }
                query.sort?.let { params["sort"] = it }

                val response = app.get(
                    "${siteRootUrl}it/archive",
                    params = params,
                    headers = sliderHeaders(),
                )
                val titles = parseArchiveTitles(response.body.string())
                val items = searchResponseBuilder(titles)
                if (items.isEmpty()) return null
                val hasNext = titles.size >= PAGE_SIZE && current < MAX_PAGE
                newHomePageResponse(
                    HomePageList(query.label, items),
                    hasNext = hasNext
                )
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------
    //  RICERCA (paginata)
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "$mainUrl/search",
            params = mapOf("q" to query),
            headers = mapOf("User-Agent" to SC_UA),
        ).body.string()
        return searchResponseBuilder(parseArchiveTitles(response))
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val params = mutableMapOf("q" to query)
        if (page > 1) params["page"] = page.toString()
        val response = app.get(
            "$mainUrl/search",
            params = params,
            headers = mapOf("User-Agent" to SC_UA),
        ).body.string()
        val titles = parseArchiveTitles(response)
        val items = searchResponseBuilder(titles)
        val hasNext = titles.size >= PAGE_SIZE && page < MAX_PAGE
        return newSearchResponseList(items, hasNext = hasNext)
    }

    // ------------------------------------------------------------------
    //  DETTAGLIO TITOLO
    // ------------------------------------------------------------------
    private suspend fun getPoster(title: ScTitleProp): String? {
        // Prima il poster CDN del sito (veloce), poi TMDB come riserva
        title.getPosterImageId()?.let { return "https://$cdnHost/images/$it" }
        if (title.tmdbId != null) {
            val tmdbUrl = "https://www.themoviedb.org/${title.type}/${title.tmdbId}"
            val resp = app.get(tmdbUrl).document
            val img = resp.select("img.poster.w-full").attr("srcset").split(", ").last()
            if (img.isNotBlank()) return img
        }
        return title.getBackgroundImageId()?.let { "https://$cdnHost/images/$it" }
    }

    private fun getActualUrl(url: String): String =
        if (!url.contains(mainUrl)) {
            val host = mainUrl.toHttpUrl().host
            val replacingValue =
                if (url.contains("/it/") || url.contains("/en/")) host else "$host/it"
            url.replace(url.toHttpUrl().host, replacingValue)
        } else {
            url
        }

    override suspend fun load(url: String): LoadResponse {
        val actualUrl = getActualUrl(url)
        ensureSession()
        val response = app.get(actualUrl, headers = sessionHeaders)
        val responseBody = response.body.string()

        val props = if (isHtmlPayload(responseBody)) {
            val json = extractInertiaPageJson(responseBody)
                ?: throw RuntimeException("StreamingCommunity: pagina titolo non valida")
            parseJson<ScInertiaResponse>(json).props
        } else {
            parseJson<ScInertiaResponse>(responseBody).props
        }
        val title = props.title ?: throw RuntimeException("StreamingCommunity: titolo assente")
        val genres = title.genres.map { it.name.capitalize() }
        val year = title.releaseDate?.substringBefore('-')?.toIntOrNull()
        val related = props.sliders?.getOrNull(0)
        val trailers = title.trailers?.mapNotNull { it.getYoutubeUrl() }
        val poster = getPoster(title)

        if (title.type == "tv") {
            val episodes: List<Episode> = getEpisodes(props)
            return newTvSeriesLoadResponse(
                title.name,
                actualUrl,
                TvType.TvSeries,
                episodes
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = it?.let { id -> "https://$cdnHost/images/$id" } }

                this.tags = genres
                this.episodes = episodes
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)
                if (!trailers.isNullOrEmpty()) {
                    addTrailer(trailers)
                }
            }
        } else {
            val data = ScLoadData(
                "$mainUrl/iframe/${title.id}&canPlayFHD=1",
                "movie",
                title.tmdbId
            )
            return newMovieLoadResponse(
                title.name,
                actualUrl,
                TvType.Movie,
                dataUrl = data.toJson()
            ) {
                this.posterUrl = poster
                title.getBackgroundImageId()
                    .let { this.backgroundPosterUrl = it?.let { id -> "https://$cdnHost/images/$id" } }

                this.tags = genres
                this.year = year
                this.plot = title.plot
                title.age?.let { this.contentRating = "$it+" }
                this.recommendations = related?.titles?.let { searchResponseBuilder(it) }
                this.addActors(title.mainActors?.map { it.name })
                this.addScore(title.score)

                title.imdbId?.let { this.addImdbId(it) }
                title.tmdbId?.let { this.addTMDbId(it.toString()) }

                title.runtime?.let { this.duration = it }
                if (!trailers.isNullOrEmpty()) {
                    addTrailer(trailers)
                }
            }
        }
    }

    private suspend fun getEpisodes(props: ScProps): List<Episode> {
        val episodeList = mutableListOf<Episode>()
        val title = props.title ?: return episodeList

        title.seasons?.forEach { season ->
            val responseEpisodes = mutableListOf<ScEpisode>()
            if (season.id == props.loadedSeason?.id) {
                props.loadedSeason.episodes?.let { responseEpisodes.addAll(it) }
            } else {
                if (inertiaVersion == "") {
                    setupHeaders()
                }
                val url = "$mainUrl/titles/${title.id}-${title.slug}/season-${season.number}"
                val obj = runCatching {
                    parseJson<ScInertiaResponse>(app.get(url, headers = sessionHeaders).body.string())
                }.getOrNull()
                obj?.props?.loadedSeason?.episodes?.let { responseEpisodes.addAll(it) }
            }
            responseEpisodes.forEach { ep ->
                val loadData = ScLoadData(
                    "$mainUrl/iframe/${title.id}?episode_id=${ep.id}&canPlayFHD=1",
                    type = "tv",
                    tmdbId = title.tmdbId,
                    seasonNumber = season.number,
                    episodeNumber = ep.number
                )
                episodeList.add(
                    newEpisode(loadData.toJson()) {
                        this.name = ep.name
                        this.posterUrl = ep.getCover()?.let { "https://$cdnHost/images/$it" }
                        this.description = ep.plot
                        this.episode = ep.number
                        this.season = season.number
                        this.runTime = ep.duration
                    }
                )
            }
        }

        return episodeList
    }

    // ------------------------------------------------------------------
    //  STREAM: iframe SC -> VixCloud (Cloudflare) + fallback VixSrc
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isEmpty()) return false
        val loadData = parseJson<ScLoadData>(data)

        val iframePage = runCatching {
            app.get(
                loadData.url,
                headers = mapOf(
                    "User-Agent" to SC_UA,
                    "Referer" to "$mainUrl/",
                )
            ).document
        }.getOrNull()

        val iframeSrc = iframePage?.select("iframe")?.attr("src").orEmpty()
        if (iframeSrc.isNotBlank()) {
            runCatching {
                VixCloudExtractor().getUrl(
                    url = iframeSrc,
                    referer = siteRootUrl,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }

        // Fallback: vixsrc.to con il tmdb_id (stessa libreria del sito)
        val tmdbId = loadData.tmdbId
        if (tmdbId != null) {
            val vixsrcUrl = if (loadData.type == "movie") {
                "https://vixsrc.to/movie/$tmdbId"
            } else {
                "https://vixsrc.to/tv/$tmdbId/${loadData.seasonNumber}/${loadData.episodeNumber}"
            }
            runCatching {
                VixSrcExtractor().getUrl(
                    url = vixsrcUrl,
                    referer = "https://vixsrc.to/",
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }
        }

        return true
    }
}
