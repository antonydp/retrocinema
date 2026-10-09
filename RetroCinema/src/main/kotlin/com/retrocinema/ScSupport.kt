package com.retrocinema

/**
 * Supporto StreamingCommunity / StreamingUnity (fonte FMHY).
 *
 * Porting adattato del provider "HadEnough" di doGior (GPL-3.0),
 * verificato dal vivo: API Inertia con paginator (current_page/data/
 * last_page) per generi e ricerca, slider API per le novità, player
 * VixCloud (dietro Cloudflare: bypassato in app con CloudflareKiller)
 * con fallback VixSrc. SOLO FILM: i titoli di tipo "tv" sono scartati.
 *
 * Domini: cambiano spesso; si prova una lista di candidati e si usa
 * il primo che risponde. CDN: cdn.<dominio>.
 */
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLDecoder

/** User-Agent realistico (necessario per la sessione Inertia). */
const val SC_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0"

// ---------------------------------------------------------------------------
//  DTO (Jackson tollerante: i campi sconosciuti vengono ignorati)
// ---------------------------------------------------------------------------

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScTitle(
    val id: Int = 0,
    val name: String = "",
    val slug: String = "",
    val type: String = "",
    val images: List<ScPosterImage> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScPosterImage(
    val filename: String = "",
    val type: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScGenre(val id: Int = 0, val name: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScMainActor(val id: Int = 0, val name: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScTrailer(val youtube_id: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScTitleProp(
    val id: Int = 0,
    val name: String = "",
    val slug: String = "",
    val plot: String? = null,
    val type: String? = null,
    val release_date: String? = null,
    val runtime: Int? = null,
    val tmdb_id: Int? = null,
    val images: List<ScPosterImage> = emptyList(),
    val genres: List<ScGenre> = emptyList(),
    val main_actors: List<ScMainActor>? = null,
    val trailers: List<ScTrailer>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScSlider(
    val name: String = "",
    val label: String = "",
    val titles: List<ScTitle> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScProps(
    @Suppress("PropertyName") val cdn_url: String? = null,
    val title: ScTitleProp? = null,
    val sliders: List<ScSlider>? = null,
    val titles: List<ScTitle>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScInertiaResponse(
    val props: ScProps = ScProps()
)

/** Paginator dell'API archive/search: {current_page, data[], last_page}. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScPaginator(
    val current_page: Int = 1,
    val data: List<ScTitle> = emptyList(),
    val last_page: Int = 1
)

// ---------------------------------------------------------------------------
//  Sessione StreamingCommunity: risoluzione dominio + header Inertia
// ---------------------------------------------------------------------------

class ScSession {

    private val candidates = listOf(
        "https://streamingunity.win/",
        "https://streamingunity.vip/",
        "https://streamingcommunityz.red/"
    )

    private val mapper by lazy {
        jacksonObjectMapper().configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
        )
    }

    private val resolvingRoot = java.util.concurrent.atomic.AtomicBoolean(false)
    private val preparingHeaders = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    var root: String? = null
        private set

    @Volatile
    private var inertiaVersion: String = ""

    @Volatile
    private var xsrf: String = ""

    @Volatile
    private var cookieHeader: String = ""

    @Volatile
    private var headersReady: Boolean = false

    /** Radice corrente (con slash finale); risolve il dominio al primo uso. */
    suspend fun ensureRoot(): String {
        root?.let { return it }
        if (resolvingRoot.compareAndSet(false, true)) {
            try {
                for (cand in candidates) {
                    try {
                        // OkHttp ha già i suoi timeout: i domini morti
                        // falliscono da soli, in modo pulito
                        if (app.get(cand).text.contains("id=\"app\"")) {
                            root = cand
                            return cand
                        }
                    } catch (_: Exception) {
                    }
                }
                // nessun dominio risponde: usa il default, le righe falliranno
                // in modo pulito senza rompere la home
                root = candidates[0]
            } finally {
                resolvingRoot.set(false)
            }
        } else {
            // un'altra coroutine sta risolvendo il dominio: attendi
            while (root == null) Thread.sleep(80)
        }
        return root!!
    }

    fun cdnRoot(): String {
        val r = root ?: return ""
        val host = r.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return "https://cdn.$host"
    }

    /** Sostituisce il dominio di un vecchio URL con quello corrente. */
    fun fixToCurrentRoot(url: String): String {
        val r = root ?: return url
        if (url.startsWith(r)) return url
        if (!url.contains("/titles/") && !url.contains("/it/")) return url
        val afterHost = url.substringAfter("://", "").substringAfter('/', "").trim('/')
        return if (afterHost.isNotBlank()) r.trimEnd('/') + "/" + afterHost else url
    }

    private suspend fun setupHeaders() {
        if (headersReady) return
        if (preparingHeaders.compareAndSet(false, true)) {
            try {
                ensureRoot()
                val r = root
                if (r == null) return
                val home = app.get("${r}it/archive")
                val dataPage = home.document.select("#app").attr("data-page")
                inertiaVersion = Regex("\"version\":\"([^\"]+)\"")
                    .find(dataPage)?.groupValues?.get(1) ?: ""

                val jar = linkedMapOf<String, String>()
                home.cookies.forEach { (k, v) -> jar[k] = v }
                try {
                    val csrf = app.get(
                        "${r}sanctum/csrf-cookie",
                        headers = mapOf(
                            "Referer" to "${r}it/",
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    )
                    csrf.cookies.forEach { (k, v) -> jar[k] = v }
                } catch (_: Exception) {
                }
                cookieHeader = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
                xsrf = jar["XSRF-TOKEN"]?.let {
                    try {
                        URLDecoder.decode(it, "UTF-8")
                    } catch (_: Exception) {
                        it
                    }
                } ?: ""
                headersReady = true
            } finally {
                preparingHeaders.set(false)
            }
        } else {
            while (!headersReady) Thread.sleep(80)
        }
    }

    private suspend fun inertiaHeaders(): Map<String, String> {
        setupHeaders()
        val r = root ?: return emptyMap()
        return mapOf(
            "Cookie" to cookieHeader,
            "X-Inertia" to "true",
            "X-Inertia-Version" to inertiaVersion,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "${r}it/"
        )
    }

    private suspend fun sliderHeaders(): Map<String, String> {
        setupHeaders()
        val r = root ?: return emptyMap()
        return mapOf(
            "Cookie" to cookieHeader,
            "X-Requested-With" to "XMLHttpRequest",
            "X-XSRF-TOKEN" to xsrf,
            "Referer" to "${r}it/",
            "Accept" to "application/json, text/plain, */*",
            "Content-Type" to "application/json",
            "Origin" to r.trimEnd('/')
        )

    }

    // ---- API paginate -----------------------------------------------------

    /** Una pagina dell'archivio per genere (60 titoli/pagina). */
    suspend fun archivePage(genreId: Int, page: Int): ScPaginator? {
        val r = root ?: return null
        val res = app.get(
            "${r}it/archive",
            params = mapOf(
                "page" to page.toString(),
                "lang" to "it",
                "genre[]" to genreId.toString()
            ),
            headers = inertiaHeaders()
        )
        return parsePaginator(res.text)
    }

    /** Una pagina di ricerca (paginata lato sito). */
    suspend fun searchPage(query: String, page: Int): ScPaginator? {
        val r = root ?: return null
        val res = app.get(
            "${r}it/search",
            params = mapOf("q" to query, "page" to page.toString()),
            headers = inertiaHeaders()
        )
        return parsePaginator(res.text)
    }

    /** Slider "latest"/"trending"/"top10": le novità del sito. */
    suspend fun slider(name: String): ScSlider? {
        val r = root ?: return null
        val body = """{"sliders":[{"name":"$name","genre":null}]}"""
        val res = app.post(
            "${r}api/sliders/fetch?lang=it",
            headers = sliderHeaders(),
            requestBody = body.toRequestBody("application/json;charset=utf-8".toMediaType())
        )
        val arr = tryParseJson<List<ScSlider>>(res.text) ?: return null
        return arr.firstOrNull()
    }

    /** Pagina titolo con metadati completi (plot, attori, tmdb, runtime). */
    suspend fun loadTitle(url: String): ScInertiaResponse? {
        val fixed = fixToCurrentRoot(url)
        val res = app.get(fixed, headers = inertiaHeaders())
        val text = res.text.trimStart()
        if (text.startsWith("<")) {
            // HTML: i metadati sono nell'attributo data-page
            val dp = Jsoup.parse(res.text).select("#app").attr("data-page")
            if (dp.isBlank()) return null
            val unescaped = Parser.unescapeEntities(dp, true)
            return tryParseJson<ScInertiaResponse>(unescaped)
        }
        return tryParseJson<ScInertiaResponse>(text)
    }

    // ---- Parsing ----------------------------------------------------------

    private fun parsePaginator(text: String): ScPaginator? {
        if (text.isBlank()) return null
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("{")) {
            try {
                mapper.readValue(trimmed, ScPaginator::class.java)
            } catch (_: Exception) {
                null
            }
        } else {
            // fallback: pagina HTML con data-page (props.titles)
            val dp = Jsoup.parse(text).select("#app").attr("data-page")
            if (dp.isBlank()) return null
            try {
                val unescaped = Parser.unescapeEntities(dp, true)
                val inertia = mapper.readValue(unescaped, ScInertiaResponse::class.java)
                ScPaginator(1, inertia.props.titles.orEmpty(), 1)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** URL della scheda del titolo sul dominio corrente. */
    fun titleUrl(t: ScTitle): String = "${root ?: ""}it/titles/${t.id}-${t.slug}"
}

// ---------------------------------------------------------------------------
//  Estrattori VixCloud / VixSrc (porting adattato da doGior, GPL-3.0)
// ---------------------------------------------------------------------------

class ScVixExtractors {

    private val mapper by lazy {
        jacksonObjectMapper().configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
        )
    }

    private val commonHeaders = mapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache",
        "user-agent" to SC_UA
    )

    /**
     * Estrae il masterPlaylist m3u8 da una pagina player VixCloud/VixSrc.
     * Lo script contiene assegnazioni window.xxx = {...} che vanno
     * ripulite e convertite in JSON prima di essere lette.
     */
    private fun parseWindowObject(script: String, key: String): JsonNode? {
        val m = Regex("window\\.$key\\s*=\\s*").find(script) ?: return null
        var v = script.substring(m.range.last + 1).trim()
        // taglia l'oggetto contando le graffe (robusto contro nesting)
        var depth = 0
        var end = -1
        for ((i, c) in v.withIndex()) {
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end == -1) return null
        v = v.substring(0, end + 1)
        val cleaned = v
            .replace(";", "")
            .replace(Regex("(\\{|\\[|,)\\s*(\\w+)\\s*:"), "$1 \"$2\":")
            .replace(Regex(",(\\s*[}\\]])"), "$1")
            .replace('\'', '"')
        return try {
            mapper.readTree(cleaned)
        } catch (_: Exception) {
            null
        }
    }

    private fun canPlayFhd(script: String): Boolean =
        Regex("window\\.canPlayFHD\\s*=\\s*(true|false)").find(script)?.groupValues?.get(1) == "true"

    private fun buildMasterUrl(mp: JsonNode): String? {
        val url = mp.path("url").asText("")
        val token = mp.path("params").path("token").asText("")
        val expires = mp.path("params").path("expires").asText("")
        if (url.isBlank() || token.isBlank()) return null
        val params = "token=$token&expires=$expires"
        val base = if ("?b" in url) {
            url.replace("?b:1", "?b=1") + "&$params"
        } else {
            "$url?$params"
        }
        return base
    }

    private suspend fun emit(
        sourceName: String,
        linkName: String,
        masterUrl: String,
        fhd: Boolean,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var url = masterUrl
        if (fhd && !url.contains("&h=1")) url += "&h=1"
        callback(
            newExtractorLink(
                source = sourceName,
                name = linkName,
                url = url,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = commonHeaders
                this.referer = referer ?: ""
            }
        )
        return true
    }

    /**
     * VixCloud (player di StreamingCommunity). La pagina è dietro
     * Cloudflare: in app bypassa con CloudflareKiller (WebView).
     */
    suspend fun vixCloud(
        embedUrl: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        return try {
            val doc = app.get(
                embedUrl,
                headers = commonHeaders,
                interceptor = CloudflareKiller()
            ).document
            val script = doc.select("script")
                .firstOrNull { it.data().contains("masterPlaylist") }?.data() ?: return false
            val mp = parseWindowObject(script, "masterPlaylist") ?: return false
            val masterUrl = buildMasterUrl(mp) ?: return false
            emit(
                "VixCloud", "StreamingCommunity · ITA", masterUrl,
                canPlayFhd(script), referer, callback
            )
        } catch (_: Exception) {
            false
        }
    }

    /** Fallback VixSrc (stesso player, dominio diverso, via tmdbId). */
    suspend fun vixSrc(
        movieUrl: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        return try {
            val headers = mapOf(
                "Accept" to "*/*",
                "Connection" to "keep-alive",
                "Referer" to (referer ?: "https://vixsrc.to/"),
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "User-Agent" to SC_UA
            )
            val doc = app.get(movieUrl, headers = headers).document
            val script = doc.select("script")
                .firstOrNull { it.data().contains("masterPlaylist") }?.data() ?: return false
            val mp = parseWindowObject(script, "masterPlaylist") ?: return false
            val masterUrl = buildMasterUrl(mp) ?: return false
            emit(
                "VixSrc", "StreamingCommunity · ITA (alt)", masterUrl,
                canPlayFhd(script), "https://vixsrc.to/", callback
            )
        } catch (_: Exception) {
            false
        }
    }
}
