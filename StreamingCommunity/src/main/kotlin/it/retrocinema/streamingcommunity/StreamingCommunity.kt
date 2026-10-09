package it.retrocinema.streamingcommunity

import android.content.Context
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
import com.lagradost.cloudstream3.MainPageData
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
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/** User-Agent realistico: necessario per la sessione Inertia. */
const val SC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"

/**
 * StreamingCommunity - versione RetroCinema con homepage ordinata e
 * personalizzabile.
 *
 * Cambia pochissimo rispetto al sito (stesse API Inertia, stesso player
 * VixCloud/VixSrc) ma la home e riorganizzata e l'utente puo sceglierla:
 * dal menu impostazioni del plugin (gear in home) puo riordinare e
 * disattivare le sezioni (preset Standard/Famiglia/Solo film/Solo serie).
 * Tutte le righe hanno lo scroll infinito (17 pagine da 60 titoli, lo
 * stesso tetto che il sito impone ai clienti web): anche "Tendenze di
 * adesso" e "Aggiunti di recente" usano le pagine browse ufficiali del
 * sito (/it/browse/trending e /it/browse/latest, paginate), NON piu lo
 * slider API che dava una lista fissa di ~20 titoli.
 *
 * Stabilita: le righe della home vengono caricate IN SEQUENZA
 * (sequentialMainPage) invece che tutte insieme, ogni richiesta riprova
 * da sola e la sessione Inertia e protetta da un lock con refresh forzato
 * a fronte di 403/419/429 (Cloudflare/limite del sito): e quello che
 * faceva sparire alcune righe a intermittenza.
 *
 * Domini: il sito ruota indirizzi di continuo (ott 2026: i vecchi .win/
 * .vip/.red reindirizzano a .fun/.rip). Il plugin prova i domini in ordine
 * e adotta SEMPRE quello finale dopo i redirect (cookie e token restano
 * coerenti); se TUTTI i domini noti muoiono, chiede il dominio aggiornato
 * al portale streaming-community.how (ultima spiaggia: ogni dominio
 * trovato viene validato contro l'app Inertia vera prima dell'uso).
 *
 * Basato sul provider "HadEnough" di doGior (GPL-3.0).
 */
class StreamingCommunity : MainAPI() {
    // Domini in ordine di preferenza: il sito cambia spesso, si usa il primo vivo.
    // .fun e .rip sono i domini attuali (ott 2026: i vecchi reindirizzano qui,
    // confermato da browser e portale); .dog e' nel portale ufficiale. I vecchi
    // restano in fondo come riserva: se rispondono, si segue il redirect e si
    // adotta il dominio finale.
    private val domainCandidates = listOf(
        "https://streamingunity.fun/",
        "https://streamingcommunityz.rip/",
        "https://streamingunity.dog/",
        "https://streamingunity.win/",
        "https://streamingunity.vip/",
        "https://streamingcommunityz.red/",
    )

    private var siteRootUrl = domainCandidates[0]
    private var cdnHost = "cdn." + siteRootUrl.toHttpUrl().host
    private var inertiaVersion = ""
    private var decodedXsrfToken = ""
    private val sessionLock = AtomicBoolean(false)
    private var lastSessionMs = 0L
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
        /** Tentativi per ogni riga della home prima di arrendersi. */
        const val RETRIES = 3
        /** Minimo tra due refresh forzati della sessione (ms). */
        const val REFRESH_MIN_MS = 20_000L

        /** Context messo a disposizione dal plugin al caricamento. */
        @Volatile
        var appContext: Context? = null

        /** Portale che pubblica il dominio aggiornato (ultima spiaggia). */
        const val PORTAL_URL = "https://www.streaming-community.how/"

        /** Minimo tra due controlli del portale (ms): 6 ore. */
        const val PORTAL_MIN_MS = 6 * 60 * 60 * 1000L

        /** Ultimo controllo del portale (evita richieste continue). */
        @Volatile
        private var lastPortalCheckMs = 0L

        /** Sezioni gia lette dalle impostazioni (evita rilisure continue). */
        @Volatile
        private var cachedSections: List<ScSection>? = null

        /** Da chiamare dopo un cambio di impostazioni: ricostruisce la home. */
        fun invalidateSections() {
            cachedSections = null
        }
    }

    // ------------------------------------------------------------------
    //  HOMEPAGE DINAMICA
    //
    //  Ordine e sezioni attive vengono dal menu impostazioni del plugin
    //  (salvate con DataStore); senza scelte utente si usa l'ordine
    //  predefinito (vedi ScSections.DEFAULT):
    //  1. Top 10 serie / Top 10 film: i piu visti (sort=views + filtro
    //     type, verificato live; lo slider ufficiale top10 e misto e un
    //     giorno puo contenere solo serie, quindi non lo usiamo)
    //  2. Tendenze di adesso + Aggiunti di recente: le pagine browse
    //     ufficiali del sito (/it/browse/trending e /it/browse/latest),
    //     pagine da 60 titoli: scroll infinito come le altre righe
    //  3. Generi, tutti ordinati per tendenza (sort=views), scroll infinito
    //  4. Le annate 2026/2025/2024 in fondo
    //
    //  Le righe vengono richieste IN SEQUENZA dall'app
    //  (sequentialMainPage): e la cura principale per il problema per cui
    //  a volte qualche sezione non compariva (troppe richieste in
    //  parallelo scartate da Cloudflare/sito).
    // ------------------------------------------------------------------
    override var sequentialMainPage: Boolean = true

    override val mainPage: List<MainPageData>
        get() {
            val sections = cachedSections ?: ScSections.loadOrder(appContext).also {
                cachedSections = it
            }
            return mainPageOf(*sections.map { it.toPair() }.toTypedArray())
        }

    // ------------------------------------------------------------------
    //  Sessione Inertia (version + cookie XSRF) con failover dei domini
    // ------------------------------------------------------------------
    private suspend fun setupHeaders() {
        val candidates = listOf(siteRootUrl) + domainCandidates.filter { it != siteRootUrl }
        for (root in candidates) {
            if (adoptDomain(root)) return
        }
        // Ultima spiaggia: il portale che pubblica il dominio aggiornato del
        // sito, consultato SOLO quando tutti i domini conosciuti sono morti
        // (al massimo una volta ogni PORTAL_MIN_MS). Ogni dominio trovato
        // viene comunque VALIDATO da adoptDomain: deve servire l'app Inertia
        // vera (data-page con versione), cosi un clone non viene adottato.
        if (System.currentTimeMillis() - lastPortalCheckMs > PORTAL_MIN_MS) {
            lastPortalCheckMs = System.currentTimeMillis()
            for (root in portalCandidates()) {
                if (root !in domainCandidates && root != siteRootUrl && adoptDomain(root)) return
            }
        }
    }

    /** Prova un dominio: se serve l'app Inertia vera lo adotta (con sessione). */
    private suspend fun adoptDomain(root: String): Boolean {
        val probe = runCatching {
            app.get(root + "it/archive", headers = mapOf("User-Agent" to SC_UA))
        }.getOrNull() ?: return false
        if (probe.code !in 200..299) return false

        val dataPage = runCatching { probe.document.select("#app").attr("data-page") }
            .getOrNull().orEmpty()
        val version = dataPage
            .substringAfter("\"version\":\"", "")
            .substringBefore("\"")
        if (version.isBlank()) return false

        // Il dominio puo fare redirect (il sito ruota spesso): adottiamo
        // quello FINALE, cosi cookie e richieste restano coerenti (e non si
        // rifanno redirect ad ogni richiesta).
        val finalRoot = runCatching { probe.url.toHttpUrl().host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { "https://$it/" }
            ?: root

        // Dominio vivo: lo adottiamo (anche cdn e mainUrl)
        siteRootUrl = finalRoot
        cdnHost = "cdn." + finalRoot.toHttpUrl().host
        mainUrl = finalRoot + "it"

        val cookieJar = linkedMapOf<String, String>()
        probe.cookies.forEach { (k, v) -> cookieJar[k] = v }

        val csrf = runCatching {
            app.get(
                finalRoot + "sanctum/csrf-cookie",
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
        return true
    }

    /**
     * Domini candidati letti dal portale che traccia il dominio aggiornato
     * del sito (usato SOLO se tutti i candidati fissi falliscono). Teniamo
     * solo host che contengono "streaming", scartando il portale stesso:
     * poi tutto passa dalla validazione di adoptDomain.
     */
    private suspend fun portalCandidates(): List<String> = runCatching {
        val res = app.get(PORTAL_URL, headers = mapOf("User-Agent" to SC_UA))
        if (res.code !in 200..299) {
            emptyList()
        } else {
            Regex("https?://[A-Za-z0-9.-]*streaming[A-Za-z0-9.-]*\\.[A-Za-z]{2,}")
                .findAll(res.body.string())
                .map { it.value.lowercase() }
                .map { "https://$it/" }
                .distinct()
                .filter { !it.contains("streaming-community.how") }
                .toList()
        }
    }.getOrDefault(emptyList())

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

    private suspend fun ensureSession(force: Boolean = false) {
        val hasSession = !sessionHeaders["Cookie"].isNullOrBlank() && inertiaVersion.isNotBlank()
        if (hasSession && !force) return
        // I refresh forzati non piu di uno ogni REFRESH_MIN_MS: cosi le
        // tante righe della home non rimartellano il sito quando una
        // risposta viene scartata.
        if (force && hasSession && System.currentTimeMillis() - lastSessionMs < REFRESH_MIN_MS) return

        if (!sessionLock.compareAndSet(false, true)) {
            // un'altra richiesta sta gia configurando la sessione: aspetta
            var waited = 0
            while (sessionLock.get() && waited < 10_000) {
                Thread.sleep(120)
                waited += 120
            }
            val nowFresh = !sessionHeaders["Cookie"].isNullOrBlank() && inertiaVersion.isNotBlank()
            if (nowFresh && !force) return
            if (force && nowFresh && System.currentTimeMillis() - lastSessionMs < REFRESH_MIN_MS) return
            if (!sessionLock.compareAndSet(false, true)) return
        }
        try {
            setupHeaders()
            lastSessionMs = System.currentTimeMillis()
        } finally {
            sessionLock.set(false)
        }
    }

    /**
     * Se il sito ha cambiato dominio a sessione aperta (la risposta arriva
     * da un host diverso da quello richiesto), adotta il dominio finale:
     * le richieste successive vanno dritte, senza rifare redirect ad ogni
     * chiamata e con cookie/token coerenti col dominio vero.
     */
    private fun maybeAdoptRedirect(response: com.lagradost.nicehttp.NiceResponse) {
        runCatching {
            val host = response.url.toHttpUrl().host
            if (host.isNotBlank() && host != siteRootUrl.toHttpUrl().host) {
                siteRootUrl = "https://$host/"
                cdnHost = "cdn.$host"
                mainUrl = siteRootUrl + "it"
            }
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
    //  HOMEPAGE (con retry: Cloudflare/sito a volte scartano le richieste
    //  e senza retry la riga spariva dalla home)
    // ------------------------------------------------------------------

    /** Riprova fino a RETRIES volte con attesa crescente tra un tentativo e l'altro. */
    private suspend fun <T> withRetries(block: suspend (attempt: Int) -> T?): T? {
        var waitMs = 350L
        repeat(RETRIES) { attempt ->
            val result = block(attempt)
            if (result != null) return result
            if (attempt < RETRIES - 1) {
                Thread.sleep(waitMs)
                waitMs = (waitMs * 2).coerceAtMost(1500)
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val current = page.coerceAtLeast(1)
        val query = try {
            parseJson<ArchiveQuery>(request.data)
        } catch (e: Exception) {
            null
        } ?: return null

        ensureSession()

        return when (query.kind) {
            "browse" -> {
                withRetries { attempt -> fetchBrowse(query, current, attempt) }
            }
            "archive" -> {
                withRetries { attempt -> fetchArchive(query, current, attempt) }
            }
            else -> null
        }
    }

    private suspend fun fetchBrowse(query: ArchiveQuery, page: Int, attempt: Int): HomePageResponse? {
        return try {
            if (attempt > 0) ensureSession(force = true)
            val path = query.path ?: return null
            val response = app.get(
                siteRootUrl + path.removePrefix("/"),
                params = mapOf("page" to page.toString(), "lang" to "it"),
                headers = sliderHeaders(),
            )
            // 403/419/429: Cloudflare o limite del sito -> sessione fresca e riprova
            if (response.code == 403 || response.code == 419 || response.code == 429) {
                ensureSession(force = true)
                return null
            }
            if (response.code !in 200..299) return null
            maybeAdoptRedirect(response)
            // Pagina oltre l'ultima: il sito risponde 200 con titoli vuoti
            // -> null e l'app smette di paginare (comportamento corretto)
            val titles = parseArchiveTitles(response.body.string())
            if (titles.isEmpty()) return null
            val items = searchResponseBuilder(titles)
            if (items.isEmpty()) return null
            val hasNext = titles.size >= PAGE_SIZE && page < MAX_PAGE
            newHomePageResponse(
                HomePageList(query.label, items),
                hasNext = hasNext
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchArchive(query: ArchiveQuery, page: Int, attempt: Int): HomePageResponse? {
        return try {
            if (attempt > 0) ensureSession(force = true)
            val params = mutableMapOf(
                "page" to page.toString(),
                "lang" to "it",
            )
            query.genre?.let { params["genre[]"] = it.toString() }
            query.year?.let { params["year"] = it.toString() }
            query.type?.let { params["type"] = it }
            query.sort?.let { params["sort"] = it }

            val response = app.get(
                "${siteRootUrl}it/archive",
                params = params,
                headers = sliderHeaders(),
            )
            if (response.code == 403 || response.code == 419 || response.code == 429) {
                ensureSession(force = true)
                return null
            }
            if (response.code !in 200..299) return null
            maybeAdoptRedirect(response)
            val titles = parseArchiveTitles(response.body.string())
            if (titles.isEmpty()) return null
            val allItems = searchResponseBuilder(titles)
            if (allItems.isEmpty()) return null
            // Riga con limite (es. Top 10): solo i primi N, senza paginazione
            val capped = query.limit != null
            val items = if (capped) allItems.take(query.limit) else allItems
            val hasNext = !capped && titles.size >= PAGE_SIZE && page < MAX_PAGE
            newHomePageResponse(
                HomePageList(query.label, items),
                hasNext = hasNext
            )
        } catch (e: Exception) {
            null
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
