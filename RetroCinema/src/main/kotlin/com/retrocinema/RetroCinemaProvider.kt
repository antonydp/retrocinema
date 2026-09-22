package com.retrocinema

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.util.regex.Pattern
import kotlin.math.roundToInt

/**
 * RetroCinema — UN SOLO plugin che contiene tutto.
 *
 * Sorgenti (verificate dal vivo):
 *  - RaiPlay (ufficiale): collezioni curate "Grandi Classici di Hollywood",
 *    "Stanlio e Ollio - Edizioni restaurate", "Il grande cinema". Stream HLS
 *    ufficiale via relinker Rai + sottotitoli italiani SRT.
 *  - Internet Archive: migliaia di film classici del dominio pubblico con
 *    download diretto, righe curate per genere e decennio.
 *
 * Il catalogo è SELEZIONATO: solo lungometraggi, solo classici adatti a
 * tutta la famiglia. Nessun horror, nessuna serie TV, niente contenuti adulti.
 */
class RetroCinemaProvider : MainAPI() {

    override var mainUrl = "https://www.raiplay.it"
    override var name = "RetroCinema"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val iaUrl = "https://archive.org"

    // Mapper difensivo per archive.org (come il provider ufficiale di
    // recloudstream: i metadata IA mescolano stringhe e liste).
    private val mapper by lazy {
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true)
            configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, true)
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }
    }

    // ------------------------------------------------------------------
    //  Filtri di qualità: NIENTE horror, niente adulti, niente rumore
    // ------------------------------------------------------------------
    private val badGenreWords = listOf("horror", "erotico", "porn", "hard")
    private val iaBadTitleWords = listOf(
        "cutugno", "svcd", "full album", "i'll be over you", "concert",
        "live in", "tribute to", "trailer", "radio broadcast"
    )

    private fun isCleanTitle(title: String): Boolean {
        val t = title.lowercase()
        return iaBadTitleWords.none { t.contains(it) }
    }

    private fun isCleanRaiItem(node: JsonNode): Boolean {
        for (f in listOf("genre", "subgenre")) {
            val v = node.get(f)?.asTextOrNull()?.lowercase() ?: continue
            if (badGenreWords.any { v.contains(it) }) return false
        }
        for (f in listOf("genres", "subgenres")) {
            val arr = node.get(f) ?: continue
            if (arr.isArray) {
                for (g in arr) {
                    val v = g.get("name")?.asTextOrNull()?.lowercase() ?: continue
                    if (badGenreWords.any { v.contains(it) }) return false
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------------
    //  Dati passati da load() a loadLinks()
    // ------------------------------------------------------------------
    data class LoadData(
        val src: String,                       // "ia" | "iaplaylist" | "rai"
        val id: String? = null,                // identifier archive.org
        val path: String? = null,              // path video json RaiPlay
        val urlData: Set<URLData>? = null      // playlist IA (file multipli)
    )

    data class URLData(
        val url: String,
        val format: String,
        val size: Float,
        val quality: Int
    )

    // ------------------------------------------------------------------
    //  PAGINA PRINCIPALE — ricca, curata, in italiano
    // ------------------------------------------------------------------
    private fun iaQ(extra: String): String {
        val base = "collection:(feature_films) AND mediatype:(movies) AND NOT subject:(horror)"
        return if (extra.isBlank()) base else "$base AND ($extra)"
    }

    private val commediaQ = iaQ(
        "(title:(\"alberto sordi\") OR creator:(\"alberto sordi\") " +
                "OR title:(\"totò\") OR creator:(\"totò\") " +
                "OR title:(\"peppino de filippo\") OR creator:(\"peppino de filippo\") " +
                "OR title:(\"anna magnani\") OR creator:(\"anna magnani\") " +
                "OR title:(\"vittorio gassman\") OR creator:(\"vittorio gassman\") " +
                "OR title:(\"nino manfredi\") OR creator:(\"nino manfredi\") " +
                "OR title:(\"gina lollobrigida\") OR creator:(\"gina lollobrigida\") " +
                "OR title:(\"totò a colori\"))"
    )

    private val musicalQ = iaQ(
        "(creator:(\"fred astaire\") OR title:(\"fred astaire\") " +
                "OR creator:(\"judy garland\") OR title:(\"judy garland\") " +
                "OR creator:(\"gene kelly\") OR title:(\"gene kelly\") " +
                "OR subject:(musical))"
    )

    private val westernQ = iaQ("(subject:(western) OR collection:(westerns))")

    private val noirQ = iaQ("(subject:(\"film noir\") OR subject:(noir) OR subject:(gangster))")

    private val capolavoriQ = iaQ(
        "(title:(\"de sica\") OR creator:(\"de sica\") " +
                "OR title:(rossellini) OR creator:(rossellini) " +
                "OR title:(fellini) OR title:(visconti) OR title:(monicelli))"
    )

    private val decadeFilter = "AND NOT title:(dracula OR frankenstein OR zombie OR monster)"

    // Film scelti a mano, verificati uno a uno (esistono e hanno video).
    private val daVedere = listOf(
        Triple("McLintock!", "mclintok_widescreen", 1963),
        Triple("La grande guerra (Sordi e Gassman)", "a-grande-guerra", 1959),
        Triple("Un italiano in America", "un-italiano-in-america-1967-hd", 1967),
        Triple("Un giorno in pretura", "un-giorno-in-pretura-film-completo-con-alberto-sordi-e-peppino-de-filippo", 1954),
        Triple("Piccola posta", "piccola-posta-1955-franca-valeri-e-alberto-sordi", 1955),
        Triple("Santa Fe Trail", "Santa_Fe_Trail_movie", 1940),
        Triple("War of the Wildcats", "WarOfTheWildcats-JohnWayne1943", 1943),
        Triple("Only the Valiant", "OnlytheValaint", 1951),
        Triple("The Gun and the Pulpit", "cco_thegunandthepulpit", 1974)
    )

    override val mainPage = mainPageOf(
        Pair("local|", "Da vedere assolutamente"),
        Pair("rai|grandiclassicidihollywood", "Grandi Classici di Hollywood"),
        Pair("rai|stanlioeollio-edizionirestaurate", "Stanlio e Ollio (edizioni restaurate)"),
        Pair("raimulti|ilgrandecinema", "Il grande cinema su RaiPlay"),
        Pair("ia|", "I più visti su Internet Archive"),
        Pair("ia|$commediaQ", "Commedia all'Italiana"),
        Pair("ia|$musicalQ", "Musical"),
        Pair("ia|$westernQ", "Western"),
        Pair("ia|$noirQ", "Film Noir e Gangster"),
        Pair("ia|$capolavoriQ", "Capolavori italiani"),
        Pair("ia|" + iaQ("year:[1930 TO 1939] $decadeFilter"), "Anni '30"),
        Pair("ia|" + iaQ("year:[1940 TO 1949] $decadeFilter"), "Anni '40"),
        Pair("ia|" + iaQ("year:[1950 TO 1959] $decadeFilter"), "Anni '50"),
        Pair("ia|" + iaQ("year:[1960 TO 1969] $decadeFilter"), "Anni '60")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data ?: ""
        val list: List<SearchResponse> = try {
            when {
                data.startsWith("local|") -> daVedereRow()
                data.startsWith("rai|") -> raiCollectionRow(data.removePrefix("rai|"), page)
                data.startsWith("raimulti|") -> raiMultiRow(data.removePrefix("raimulti|"), page)
                data.startsWith("ia|") -> iaSearchRow(data.removePrefix("ia|"), page)
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList() // una riga che fallisce non deve rompere la home
        }
        return newHomePageResponse(
            listOf(
                HomePageList(request.name ?: name, list, true) // righe orizzontali stile Netflix
            ),
            hasNext = false
        )
    }

    // ---- Riga fissa "Da vedere assolutamente" (nessuna richiesta di rete) ----
    private fun daVedereRow(): List<SearchResponse> {
        return daVedere.mapNotNull { (title, id, year) ->
            newMovieSearchResponse(
                title,
                "$iaUrl/details/$id",
                TvType.Movie
            ) {
                this.posterUrl = "$iaUrl/services/img/$id"
                this.year = year
            }
        }
    }

    // ---- Righe Internet Archive (query dinamiche, ordinate per popolarità) ----
    private suspend fun iaSearchRow(query: String, page: Int): List<SearchResponse> {
        val url = "$iaUrl/advancedsearch.php" +
                "?q=" + java.net.URLEncoder.encode(query, "UTF-8") +
                "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
                "&sort%5B%5D=downloads+desc&rows=24&page=$page&output=json"
        val res = tryParseJson<IA_SEARCH>(app.get(url).text)
            ?: return emptyList()
        return res.response?.docs.orEmpty().mapNotNull { it.toSearch() }
            .filter { isCleanTitle(it.name) }
    }

    // ---- Righe RaiPlay da collezioni verificate ----
    private suspend fun raiCollectionRow(slug: String, page: Int): List<SearchResponse> {
        if (page > 1) return emptyList()
        val json = app.get("$mainUrl/collezioni/$slug.json").text
        return raiItemsFromJson(json).map { it.toSearch() }
    }

    // ---- "Il grande cinema": collezione con blocchi e sotto-set ----
    private suspend fun raiMultiRow(slug: String, page: Int): List<SearchResponse> {
        if (page > 1) return emptyList()
        val root = mapper.readTree(app.get("$mainUrl/collezioni/$slug.json").text)
        val out = mutableListOf<RaiItem>()
        val seen = mutableSetOf<String>()
        root.get("blocks")?.forEach { block ->
            block.get("sets")?.forEach { set ->
                val setPath = set.get("path_id")?.asTextOrNull() ?: return@forEach
                val setName = set.get("name")?.asTextOrNull() ?: ""
                // tieni solo i blocchi di FILM: via i documentari e i ritratti
                if (setName.contains("Story of Film") || setName.contains("Ritratti")) return@forEach
                try {
                    val setJson = app.get("$mainUrl$setPath").text
                    for (item in raiItemsFromJson(setJson)) {
                        if (item.path !in seen) {
                            seen.add(item.path)
                            out.add(item)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return out.map { it.toSearch() }
    }

    private data class IA_SEARCH(val response: IA_DOCS? = null)

    private data class IA_DOCS(val docs: List<IA_DOC>? = null)

    private data class IA_DOC(
        val identifier: String? = null,
        val title: Any? = null,
        val year: Any? = null
    )

    private fun IA_DOC.toSearch(): SearchResponse? {
        val id = identifier ?: return null
        val t = toStr(title) ?: id
        return newMovieSearchResponse(t, "$iaUrl/details/$id", TvType.Movie) {
            this.posterUrl = "$iaUrl/services/img/$id"
            this.year = toYear(year)
        }
    }

    // ------------------------------------------------------------------
    //  RICERCA — Internet Archive + RaiPlay, risultati uniti
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val out = mutableListOf<SearchResponse>()

        // 1) Internet Archive (film del dominio pubblico)
        try {
            val iaQuery = "mediatype:(movies) AND NOT subject:(horror) AND (title:($q) OR creator:($q))"
            val url = "$iaUrl/advancedsearch.php" +
                    "?q=" + java.net.URLEncoder.encode(iaQuery, "UTF-8") +
                    "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
                    "&sort%5B%5D=downloads+desc&rows=20&output=json"
            val res = tryParseJson<IA_SEARCH>(app.get(url).text)
            res?.response?.docs.orEmpty().mapNotNull { it.toSearch() }
                .filter { isCleanTitle(it.name) }
                .let { out.addAll(it) }
        } catch (_: Exception) {
        }

        // 2) RaiPlay (classici disponibili in italiano)
        try {
            val html = app.get(
                "$mainUrl/ricerca.html?q=" + java.net.URLEncoder.encode(q, "UTF-8")
            ).text
            val doc = Jsoup.parse(html)
            val seen = mutableSetOf<String>()
            for (a in doc.select("a[data-info-url], a[data-video-json]")) {
                val rawPath = a.attr("data-info-url").ifBlank { a.attr("data-video-json") }
                if (!rawPath.startsWith("/programmi/") && !rawPath.startsWith("/video/")) continue
                if (!rawPath.endsWith(".json")) continue
                val title = a.attr("aria-label")
                    .removePrefix("maggiori informazioni su ")
                    .ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
                if (title.isBlank()) continue
                if (rawPath in seen) continue
                seen.add(rawPath)
                val img = a.selectFirst("img")?.attr("abs:src").orEmpty()
                out.add(
                    newMovieSearchResponse(title, "$mainUrl$rawPath", TvType.Movie) {
                        if (img.isNotBlank()) this.posterUrl = img
                    }
                )
            }
        } catch (_: Exception) {
        }

        return out.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    //  Parsing generico delle collezioni RaiPlay
    //  (gestisce sia contents[].contents[] sia blocchi/sets nidificati)
    // ------------------------------------------------------------------
    private class RaiItem(
        val title: String,
        val path: String,
        val poster: String?,
        val year: Int?
    )

    private fun JsonNode.asTextOrNull(): String? {
        if (isMissingNode || isNull) return null
        val t = asText()
        return t.ifBlank { null }
    }

    private fun raiPoster(node: JsonNode): String? {
        val imgs = node.get("images") ?: return null
        for (key in listOf("portrait", "portrait_logo", "square", "landscape")) {
            val p = imgs.get(key)?.asTextOrNull() ?: continue
            // miniatura leggera per caricare più in fretta
            val url = if (p.contains("/dl/img/")) "$mainUrl/resizegd/300x-$p" else "$mainUrl$p"
            return url
        }
        return null
    }

    private fun collectRaiItems(node: JsonNode, out: MutableList<RaiItem>) {
        when {
            node.isObject -> {
                val pid = node.get("path_id")?.asTextOrNull()
                if (pid != null &&
                    (pid.startsWith("/programmi/") || pid.startsWith("/video/")) &&
                    pid.endsWith(".json")
                ) {
                    val title = node.get("title")?.asTextOrNull()
                        ?: node.get("name")?.asTextOrNull()
                    if (title != null && isCleanTitle(title) && isCleanRaiItem(node)) {
                        val year = toYear(node.get("year")?.asTextOrNull())
                        out.add(RaiItem(title, pid, raiPoster(node), year))
                    }
                    return // non scendere dentro l'item
                }
                node.fields().forEach { collectRaiItems(it.value, out) }
            }
            node.isArray -> node.forEach { collectRaiItems(it, out) }
        }
    }

    private fun raiItemsFromJson(json: String): List<RaiItem> {
        val root = mapper.readTree(json)
        val out = mutableListOf<RaiItem>()
        collectRaiItems(root, out)
        return out.distinctBy { it.path }
    }

    private fun RaiItem.toSearch(): SearchResponse {
        return newMovieSearchResponse(title, "$mainUrl$path", TvType.Movie) {
            if (!poster.isNullOrBlank()) this.posterUrl = poster
            if (year != null) this.year = year
        }
    }

    // ------------------------------------------------------------------
    //  Utility comuni
    // ------------------------------------------------------------------
    private fun toStr(v: Any?): String? = when (v) {
        null -> null
        is String -> v.ifBlank { null }
        is List<*> -> v.filterIsInstance<String>().joinToString(" ").ifBlank { null }
        else -> v.toString()
    }

    private fun toYear(v: Any?): Int? {
        val s = toStr(v) ?: return null
        return s.take(4).filter { it.isDigit() }.toIntOrNull().takeIf { it in 1880..2030 }
            ?: s.filter { it.isDigit() }.take(4).toIntOrNull().takeIf { it in 1880..2030 }
    }

    private fun parseDurationMinutes(hms: String?): Int? {
        if (hms.isNullOrBlank()) return null
        val parts = hms.split(":").map { it.toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1]               // ore:minuti:secondi
            2 -> parts[0]                                // minuti:secondi
            else -> null
        }
    }

    // ------------------------------------------------------------------
    //  PAGINA DEL FILM — instrada su RaiPlay o Internet Archive
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("archive.org/details/") -> loadInternetArchive(url)
            url.startsWith("$mainUrl/programmi/") -> loadRaiProgram(url)
            url.startsWith("$mainUrl/video/") -> loadRaiVideo(url)
            else -> throw ErrorLoadingException("URL non riconosciuto: $url")
        }
    }

    // ---- Internet Archive: porta fedelmente la logica del provider
    //      ufficiale recloudstream (Luna712), provata in produzione ----
    private data class MetadataResult(
        val metadata: MediaEntry? = null,
        val files: List<MediaFile>? = null,
        val dir: String? = null,
        val server: String? = null
    )

    private data class MediaEntry(
        val identifier: String? = null,
        val mediatype: String? = null,
        val title: Any? = null,
        val description: Any? = null,
        val subject: Any? = null,
        val creator: Any? = null,
        val date: String? = null,
        val year: Any? = null
    )

    private data class MediaFile(
        val name: String? = null,
        val format: String? = null,
        val title: String? = null,
        val original: String? = null,
        val length: String? = null,
        val size: Float? = null,
        val height: Int? = null
    ) {
        val lengthInSeconds: Float by lazy { calculateLengthInSeconds() }

        private fun calculateLengthInSeconds(): Float {
            return length?.toFloatOrNull() ?: run {
                if (length?.contains(":") == true) lengthToSeconds(length) else 0f
            }
        }

        private fun lengthToSeconds(time: String): Float {
            val parts = time.split(":")
            return when (parts.count()) {
                2 -> (parts[0].toFloatOrNull() ?: 0f) * 60 + (parts[1].toFloatOrNull() ?: 0f)
                3 -> (parts[0].toFloatOrNull() ?: 0f) * 3600 +
                        (parts[1].toFloatOrNull() ?: 0f) * 60 +
                        (parts[2].toFloatOrNull() ?: 0f)
                else -> 0f
            }
        }
    }

    private fun extractYear(dateString: String?): Int? {
        if (dateString == null || dateString.length < 4) return null
        if (dateString.length == 4) return dateString.toIntOrNull()
        val yearRange = Pattern.compile("\\b(\\d{4})-(\\d{4})\\b").matcher(dateString)
        if (yearRange.find()) return yearRange.group(1)?.toInt()
        val year = Pattern.compile("\\b(\\d{4})\\b").matcher(dateString)
        if (year.find()) return year.group(1)?.toInt()
        return null
    }

    private fun getUniqueName(fileName: String): String {
        return fileName.substringAfterLast('/').substringBeforeLast('.')
            .replace('_', ' ')
            .substringBeforeLast(".")
            .replace("512kb", "")
            .trim()
    }

    private fun cleanHtml(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select("font").unwrap()
        val divs = doc.select("div")
        if (divs.isNotEmpty()) divs.last()?.unwrap()
        return doc.body().html()
    }

    private suspend fun loadInternetArchive(url: String): LoadResponse {
        val identifier = url.substringAfterLast("/").substringBefore("?")
        if (identifier.isBlank()) throw ErrorLoadingException("URL non valido")

        val responseText = app.get("$iaUrl/metadata/$identifier").text
        val res = mapper.readValue(responseText, MetadataResult::class.java)
        val m = res.metadata ?: throw ErrorLoadingException("Risposta non valida da archive.org")
        val title = toStr(m.title) ?: identifier

        // Filtra i file video come il provider ufficiale
        val videoFiles = res.files.orEmpty().filter {
            it.lengthInSeconds >= 10.0 &&
                    (it.format?.contains("MPEG", true) == true ||
                            it.format?.startsWith("H.264", true) == true ||
                            it.format?.startsWith("Matroska", true) == true ||
                            it.format?.startsWith("DivX", true) == true)
        }

        val metaYear = toYear(m.year) ?: extractYear(m.date)
        val metaPlot = toStr(m.description)?.let { cleanHtml(it) }
        val metaTags = buildList {
            toStr(m.creator)?.let { add(it) }
            toStr(m.subject)?.split(",", ";")?.forEach { s ->
                val t = s.trim()
                if (t.isNotEmpty() && t !in this) add(t)
            }
        }.take(6)

        val distinctVideos = videoFiles.distinctBy { getUniqueName(it.name ?: "") }

        return if (distinctVideos.size <= 1) {
            // FILM SINGOLO: l'estrattore interno di CloudStream gestisce
            // archive.org/details/ in modo collaudato (pattern ufficiale)
            newMovieLoadResponse(title, url, TvType.Movie, LoadData(src = "ia", id = identifier)) {
                this.plot = metaPlot
                this.year = metaYear
                this.tags = metaTags
                this.posterUrl = "$iaUrl/services/img/$identifier"
                this.duration = (videoFiles.firstOrNull()?.lengthInSeconds ?: 0f).let {
                    if (it > 0f) (it / 60).roundToInt() else null
                }
                this.actors = toStr(m.creator)?.let {
                    listOf(ActorData(Actor(it, ""), roleString = "Regia / Interpreti"))
                }
            }
        } else {
            // Item con più video distinti: playlist a episodi (pattern ufficiale)
            val urlMap = linkedMapOf<String, MutableSet<URLData>>()
            for (file in videoFiles) {
                val cleanedName = (file.original ?: file.name ?: continue)
                    .substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
                val link = if (res.server != null && res.dir != null) {
                    "https://${res.server}${res.dir}/${file.name}"
                } else {
                    "$iaUrl/download/$identifier/${file.name}"
                }
                val q = (file.height ?: 480).coerceIn(240, 1080)
                urlMap.getOrPut(cleanedName) { mutableSetOf() }.add(
                    URLData(link, file.format ?: "", file.size ?: 0f, q)
                )
            }

            val mostFrequentMinutes = videoFiles
                .map { (it.lengthInSeconds / 60).roundToInt() }
                .groupBy { it }
                .maxByOrNull { it.value.count() }?.key

            val episodes: List<Episode> = urlMap.map { (fileName, urls) ->
                val file = videoFiles.first {
                    (it.original ?: it.name ?: "").substringAfterLast('/')
                        .substringBeforeLast('.').replace('_', ' ') == fileName
                }
                newEpisode(LoadData(src = "iaplaylist", urlData = urls).toJson()) {
                    name = file.title ?: fileName
                    runTime = (file.lengthInSeconds / 60).roundToInt()
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.plot = metaPlot
                this.year = metaYear
                this.tags = metaTags
                this.posterUrl = "$iaUrl/services/img/$identifier"
                this.duration = mostFrequentMinutes
            }
        }
    }

    // ---- RaiPlay: programma ufficiale (es. /programmi/gilda.json) ----
    private suspend fun loadRaiProgram(url: String): LoadResponse {
        val root = mapper.readTree(app.get(url).text)
        val title = root.get("name")?.asTextOrNull() ?: "Film"
        val firstItem = root.get("first_item_path")?.asTextOrNull()
            ?: throw ErrorLoadingException("Questo film non ha video disponibili su RaiPlay")
        val info = root.get("program_info")
        val plot = info?.get("description")?.asTextOrNull()
            ?: info?.get("vanity")?.asTextOrNull()
        val year = toYear(info?.get("year")?.asTextOrNull() ?: info?.get("onair_date")?.asTextOrNull())
        val duration = parseDurationMinutes(root.get("first_item_duration")?.asTextOrNull())
        val poster = info?.get("images")?.let { raiPosterRaw(it) }

        return newMovieLoadResponse(title, url, TvType.Movie, LoadData(src = "rai", path = firstItem)) {
            this.plot = plot
            this.year = year
            this.duration = duration
            if (!poster.isNullOrBlank()) this.posterUrl = poster
            this.tags = listOf("RaiPlay")
        }
    }

    // ---- RaiPlay: video diretto (es. /video/2023/08/....json) ----
    private suspend fun loadRaiVideo(url: String): LoadResponse {
        val root = mapper.readTree(app.get(url).text)
        val video = root.get("video")
        val title = video?.get("title")?.asTextOrNull()
            ?: root.get("name")?.asTextOrNull()
            ?: "Film"
        val duration = parseDurationMinutes(video?.get("duration")?.asTextOrNull())
        return newMovieLoadResponse(
            title, url, TvType.Movie,
            LoadData(src = "rai", path = url.removePrefix(mainUrl))
        ) {
            this.duration = duration
            this.tags = listOf("RaiPlay")
        }
    }

    private fun raiPosterRaw(imgs: JsonNode): String? {
        for (key in listOf("portrait", "portrait_logo", "square", "landscape")) {
            val p = imgs.get(key)?.asTextOrNull() ?: continue
            return "$mainUrl$p"
        }
        return null
    }

    // ------------------------------------------------------------------
    //  LINK VIDEO — il cuore del plugin
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val load = tryParseJson<LoadData>(data) ?: return false

        return when (load.src) {
            // ---------- Internet Archive: estrattore interno collaudato ----------
            "ia" -> {
                val id = load.id ?: return false
                loadExtractor("$iaUrl/details/$id", subtitleCallback, callback)
                true
            }

            // ---------- Playlist Internet Archive (pattern ufficiale) ----------
            "iaplaylist" -> {
                val urls = load.urlData.orEmpty()
                urls.sortedByDescending { it.size }.forEach { u ->
                    callback(
                        newExtractorLink(
                            name,
                            if (urls.count() > 1) "$name (${u.format})" else name,
                            u.url
                        ) {
                            this.quality = u.quality
                            this.referer = ""
                        }
                    )
                }
                true
            }

            // ---------- RaiPlay: relinker ufficiale + sottotitoli ITA ----------
            "rai" -> {
                val path = load.path ?: return false
                val root = mapper.readTree(app.get("$mainUrl$path").text)
                val video = root.get("video")
                    ?: root.get("block")?.get("video")
                    ?: return false
                val contentUrl = video.get("content_url")?.asTextOrNull() ?: return false

                // 1) HLS esplicito (formato usato dal player RaiPlay)
                val hlsUrl = if (contentUrl.contains("?")) "$contentUrl&output=71" else "$contentUrl?output=71"
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name · RaiPlay HD",
                        url = hlsUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P720.value
                    }
                )

                // 2) Fallback: URL del relinker così com'è
                if (contentUrl != hlsUrl) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name · RaiPlay",
                            url = contentUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.P480.value
                        }
                    )
                }

                // Sottotitoli italiani (e altri se presenti)
                val subs = video.get("subtitleList") ?: video.get("subtitlesArray")
                if (subs != null && subs.isArray) {
                    for (s in subs) {
                        val subPath = s.get("url")?.asTextOrNull() ?: continue
                        if (!subPath.endsWith(".srt")) continue
                        val label = s.get("label")?.asTextOrNull() ?: "Italiano"
                        subtitleCallback(SubtitleFile(label, "$mainUrl$subPath"))
                    }
                }
                true
            }

            else -> false
        }
    }
}
