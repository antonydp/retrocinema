package com.retrocinema.archive

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

/**
 * Provider "Cinema d'epoca" basato sul modello del provider ufficiale
 * InternetArchiveProvider (recloudstream/extensions, autrice Luna712).
 *
 * Serve video DAL DOMINIO PUBBLICO conservati su archive.org:
 * nessun contenuto protetto da copyright. Le righe della home sono
 * curate per genere e decennio, ordinate per popolarità reale
 * (downloads desc), pensate per un catalogo semplice e familiare.
 */
class InternetArchiveProvider : MainAPI() {

    // ---------- Proprietà obbligatorie di MainAPI ----------
    override var mainUrl = "https://archive.org"
    override var name = "Internet Archive"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    // ---------- Modelli JSON (Jackson, campi difensivi: archive.org
    // a volte restituisce stringhe e a volte liste per lo stesso campo) ----------
    private data class SearchResult(val response: DocsResponse?)
    private data class DocsResponse(val docs: List<Doc>?)

    private data class Doc(
        val identifier: String?,
        val title: Any? = null,
        val year: Any? = null,
    )

    private data class MetadataResult(
        val metadata: MediaEntry? = null,
        val files: List<MediaFile>? = null,
        val dir: String? = null,
        val server: String? = null,
    )

    private data class MediaEntry(
        val identifier: String? = null,
        val title: Any? = null,
        val description: Any? = null,
        val date: Any? = null,
        val year: Any? = null,
        val creator: Any? = null,
        val subject: Any? = null,
    )

    private data class MediaFile(
        val name: String? = null,
        val format: String? = null,
        val height: Int? = null,
        val size: Long? = null,
    )

    // Il "pacco" che load() passa a loadLinks(): più link con qualità diverse
    data class LoadData(val urlData: Set<URLData>)
    data class URLData(val url: String, val quality: Int)

    // ---------- Utility ----------
    private fun toStr(v: Any?): String? = when (v) {
        null -> null
        is String -> v.ifBlank { null }
        is List<*> -> v.filterIsInstance<String>().joinToString(" ").ifBlank { null }
        else -> v.toString()
    }

    private fun toYear(v: Any?): Int? {
        val s = toStr(v) ?: return null
        return s.take(4).filter { it.isDigit() }.toIntOrNull().takeIf { it in 1880..2020 }
            ?: s.filter { it.isDigit() }.take(4).toIntOrNull().takeIf { it in 1880..2020 }
    }

    // ---------- Query su Internet Archive (stile Lucene) ----------
    private fun searchUrl(query: String, page: Int): String {
        val base = "collection:(feature_films) AND mediatype:(movies)"
        val q = if (query.isBlank()) base else "$base AND ($query)"
        return "$mainUrl/advancedsearch.php" +
                "?q=" + URLEncoder.encode(q, "UTF-8") +
                "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
                "&sort%5B%5D=downloads+desc&rows=30&page=$page&output=json"
    }

    private fun Doc.toSearchResponse(): SearchResponse? {
        val id = identifier ?: return null
        return newMovieSearchResponse(
            toStr(title) ?: id,
            "$mainUrl/details/$id",
            TvType.Movie
        ) {
            this.posterUrl = "$mainUrl/services/img/$id"
            this.year = toYear(year)
        }
    }

    // ---------- 1. Home page: righe curate (genere + decenni) ----------
    override val mainPage = mainPageOf(
        Pair("", "I più visti del momento"),
        Pair("subject:(comedy)", "Commedie classiche"),
        Pair("subject:(musical)", "Musical"),
        Pair("collection:(film_noir)", "Film Noir"),
        Pair("subject:(western)", "Western"),
        Pair("language:(italian)", "Classici italiani"),
        Pair("year:[1930 TO 1939]", "Anni 1930"),
        Pair("year:[1940 TO 1949]", "Anni 1940"),
        Pair("year:[1950 TO 1959]", "Anni 1950"),
        Pair("year:[1960 TO 1969]", "Anni 1960"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = tryParseJson<SearchResult>(app.get(searchUrl(request.data ?: "", page)).text)
        val items = res?.response?.docs.orEmpty().mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(
            HomePageList(request.name ?: name, items),
            hasNext = true // scrolling infinito: archive.org ha sempre altre pagine
        )
    }

    // ---------- 2. Ricerca libera ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val res = tryParseJson<SearchResult>(app.get(searchUrl("title:($query)", 1)).text)
            ?: return emptyList()
        return res.response?.docs.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    // ---------- 3. Pagina del risultato ----------
    override suspend fun load(url: String): LoadResponse {
        val identifier = url.substringAfterLast("/").substringBefore("?")
        if (identifier.isBlank()) throw ErrorLoadingException("URL non valido")

        val meta = tryParseJson<MetadataResult>(app.get("$mainUrl/metadata/$identifier").text)
            ?: throw ErrorLoadingException("Risposta JSON non valida da archive.org")

        val m = meta.metadata
        val title = toStr(m?.title) ?: identifier

        // Filtri file video: solo formati che ExoPlayer riproduce in modo affidabile.
        // (Escludiamo volutamente "Ogg Video"/Theora che spesso non parte.)
        val videoFiles = meta.files.orEmpty().filter { f ->
            val fmt = f.format ?: return@filter false
            fmt.contains("MPEG4", true) ||
                    fmt.startsWith("H.264", true) ||
                    fmt.startsWith("Matroska", true) ||
                    fmt.startsWith("DivX", true)
        }

        val server = meta.server
        val dir = meta.dir
        val urlData = videoFiles.mapNotNull { f ->
            val fileName = f.name ?: return@mapNotNull null
            val link = if (server != null && dir != null) {
                "https://$server$dir/$fileName"
            } else {
                "$mainUrl/download/$identifier/$fileName"
            }
            val q = (f.height ?: 480).coerceIn(240, 1080)
            URLData(link, q)
        }.distinctBy { it.quality }
            .sortedByDescending { it.quality }
            .take(4) // poche fonti chiare: più semplice scegliere per un utente anziano
            .toSet()

        if (urlData.isEmpty()) throw ErrorLoadingException("Nessun file video disponibile per questo film")

        val year = toYear(m?.year) ?: toYear(m?.date)
        val plot = toStr(m?.description)
            ?.replace(Regex("<[^>]*>"), "")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()

        val tags = buildList {
            toStr(m?.creator)?.let { add(it) }
            toStr(m?.subject)?.split(",", ";")?.forEach { s ->
                val t = s.trim()
                if (t.isNotEmpty() && t !in this) add(t)
            }
        }.take(6)

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            LoadData(urlData).toJson() // <- il "data" che ritroverà loadLinks()
        ) {
            this.posterUrl = "$mainUrl/services/img/$identifier"
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    // ---------- 4. Link video ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = tryParseJson<LoadData>(data) ?: return false
        // Dal migliore al peggiore, come fa il provider ufficiale
        load.urlData.sortedByDescending { it.quality }.forEach {
            callback(
                newExtractorLink(
                    name,
                    "$name ${it.quality}p",
                    it.url
                ) {
                    this.quality = it.quality
                    this.referer = "" // archive.org non richiede referer
                }
            )
        }
        return true
    }
}
