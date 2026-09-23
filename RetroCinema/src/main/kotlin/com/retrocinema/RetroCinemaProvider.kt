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
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.util.regex.Pattern
import kotlin.math.roundToInt

/**
 * RetroCinema — UN SOLO plugin che contiene tutto.
 *
 * Sorgenti (verificate dal vivo):
 *  - Catalogo ITALIANO curato (114+ film verificati uno a uno su Internet
 *    Archive, audio italiano garantito: solo film di origine italiana +
 *    comiche mute). Include STORIE D'AMORE, FILM CON I BAMBINI e film
 *    degli anni '70-'90 (Fantozzi, Benigni...). Scroll infinito locale.
 *  - RaiPlay (ufficiale): collezioni curate "Stanlio e Ollio - Edizioni
 *    restaurate", "Grandi Classici di Hollywood" (doppiati in italiano),
 *    "Il grande cinema" + RACCOLTE: Dal libro al film, Cinema ragazzi
 *    (film con i bambini), 25 anni di Rai Cinema, Film in esclusiva
 *    (nuove uscite), Storie d'amore. Stream HLS via relinker Rai + SRT.
 *  - StreamingCommunity (fonte FMHY, via API Inertia paginata): NUOVE
 *    USCITE, slider Top 10 / Tendenza / Aggiunti di recente e righe per
 *    genere CON SCROLL INFINITO REALE (Commedia ~6.000 titoli, Dramma
 *    ~8.000, Romance ~2.000, Famiglia ~1.700, Avventura, Animazione...).
 *    Solo film, audio italiano, player VixCloud + fallback VixSrc.
 *
 * SCROLL INFINITO DAVVERO OVUNQUE: ogni riga della home che ha contenuti
 * paginati dichiara hasNext=true e fornisce la pagina successiva a ogni
 * scroll (comportamento loadMore per-riga di CloudStream); la ricerca è
 * paginata con SearchResponseList.
 *
 * Nessun horror, nessuna serie TV, niente contenuti adulti.
 */
class RetroCinemaProvider : MainAPI() {

    override var mainUrl = "https://www.raiplay.it"
    override var name = "RetroCinema"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "it"
    override val hasMainPage = true

    private val iaUrl = "https://archive.org"

    // Sessione StreamingCommunity (fonte FMHY) + estrattori VixCloud/VixSrc
    private val sc = ScSession()
    private val vix = ScVixExtractors()

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

    // Token di spam nei titoli degli upload IA (qualità, uploader, contenitori)
    private val iaSpamTokens = setOf(
        "1080p", "720p", "480p", "360p", "2160p", "4k", "uhd", "hd", "hq",
        "blu-ray", "bluray", "brrip", "dvdrip", "dvd", "web-dl", "webdl", "web",
        "x264", "x265", "h264", "h265", "hevc", "aac", "ac3", "mp3", "yts", "yify",
        "rarbg", "ita", "eng", "sub", "subs", "vhs", "remux", "xvid", "divx",
        "avi", "mp4", "mkv", "full", "completo", "zoowoman.website", "zoowoman",
        "angee", "para", "website", "swu", "fc", "hdrip", "hdtv", "colorized",
        "restored", "restaurato", "restaurata", "video", "quality", "upgrade"
    )

    // Pulizia titoli IA: via virgolette, AKA, qualità e spam degli uploader
    private fun cleanIaTitle(raw: String): String {
        var t = raw.replace('_', ' ')
        t = t.replace(Regex("[\"\u201c\u201d]"), " ")
        t = t.replace(Regex("\\bAKA\\b.*", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("\\bstarring\\b.*", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("\\[[^\\]]*\\]"), " ")
        t = t.replace(Regex("[(){}<>]"), " ")
        val out = StringBuilder()
        for (tokRaw in t.split(" ")) {
            val tok = tokRaw.trim().trim('.', ',', ';', ':', '-')
            if (tok.isEmpty()) continue
            val low = tok.lowercase()
            val isYear = Regex("^(19|20)\\d{2}$").matches(low)
            if (!isYear && (low.length == 1 || low in iaSpamTokens ||
                        Regex("^(19|20)\\d{2}p$").matches(low))) continue
            out.append(tok).append(' ')
        }
        t = out.toString().trim().trimEnd('-', '\u2013', '\u2014', ':', ',', '.')
        t = t.replace(Regex("\\s+"), " ")
        return t.ifBlank { raw }
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
        val src: String,                       // "iaplaylist" | "rai" | "sc"
        val id: String? = null,                // identifier archive.org (storico)
        val path: String? = null,              // path video json RaiPlay
        val urlData: Set<URLData>? = null,     // file IA (uno o più), link diretti
        val subs: List<IaSub>? = null,         // sottotitoli .vtt archive.org
        val scUrl: String? = null,             // pagina iframe StreamingCommunity
        val scTmdb: Int? = null                // tmdbId per il fallback VixSrc
    )

    data class IaSub(val url: String, val lang: String)

    data class URLData(
        val url: String,
        val format: String,
        val size: Float,
        val quality: Int
    )

    // ------------------------------------------------------------------
    //  PAGINA PRINCIPALE — ricca, curata, TUTTA IN ITALIANO,
    //  con righe a scroll infinito reale (StreamingCommunity)
    // ------------------------------------------------------------------
    override val mainPage = mainPageOf(
        Pair("local|", "Da vedere assolutamente"),
        Pair("sc|slider|latest", "Nuove uscite: appena aggiunte"),
        Pair("cat|amore", "Storie d'amore"),
        Pair("sc|genre|15", "Storie d'amore: sempre nuove"),
        Pair("cat|bambini", "Film con i bambini"),
        Pair("rairaccolta|https://www.raiplay.it/raccolta/Dal-libro-al-film-b490523c-0d87-4547-832f-c50a816bb5af.html", "Dal libro al film (RaiPlay)"),
        Pair("rairaccolta|https://www.raiplay.it/raccolta/cinema-ragazzi-raiplay-c5b4990f-e51d-4fef-9276-a91693b02506.html", "Cinema ragazzi (RaiPlay)"),
        Pair("sc|genre|16", "Film di famiglia: sempre nuovi"),
        Pair("cat|toto", "Totò e i comici della risata"),
        Pair("cat|commedia", "Commedia all'italiana"),
        Pair("sc|genre|12", "Commedie moderne: sempre nuove"),
        Pair("cat|autori", "Il grande cinema d'autore"),
        Pair("cat|neorealismo", "Neorealismo: l'Italia vera"),
        Pair("cat|melodramma", "Grandi melodrammi"),
        Pair("rairaccolta|https://www.raiplay.it/raccolta/Stefania-Sandrelli-una-lunga-storia-damore-1696ffa6-2a06-4bed-bfe9-1b950317924b.html", "Storie d'amore su RaiPlay"),
        Pair("rai|stanlioeollio-edizionirestaurate", "Stanlio e Ollio restaurati"),
        Pair("rai|grandiclassicidihollywood", "Grandi classici di Hollywood (italiano)"),
        Pair("raimulti|ilgrandecinema", "Il grande cinema su RaiPlay"),
        Pair("sc|slider|top10", "Top 10 di oggi"),
        Pair("sc|genre|1", "Grandi drammi: sempre nuovi"),
        Pair("sc|genre|11", "Avventure: sempre nuove"),
        Pair("sc|genre|19", "Animazione: sempre nuova"),
        Pair("cat|peplum", "Peplum e avventura antica"),
        Pair("cat|recenti", "Anni '70-'90: da ricordare"),
        Pair("cat|comiche", "Comiche senza parole"),
        Pair("rairaccolta|https://www.raiplay.it/raccolta/25-anni-con-Rai-Cinema-0a0a2784-043f-4b1e-8e94-3f2b4ba380ac.html", "25 anni di Rai Cinema (nuovi)"),
        Pair("rairaccolta|https://www.raiplay.it/raccolta/film-in-esclusiva-9c2e6ef9-902b-4021-a4ab-bf5fbc671e2e.html", "Film in esclusiva (nuove uscite)"),
        Pair("always|", "Scopri film sempre nuovi")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data ?: ""
        // Ogni ramo restituisce (items, hasMore): se hasMore=true, CloudStream
        // ricarica la stessa riga con pagina+1 e CONCATENA gli item: è così
        // che funziona lo scroll infinito per riga.
        val result: Pair<List<SearchResponse>, Boolean> = try {
            when {
                data.startsWith("local|") -> catalogRow(picks()) to false
                data.startsWith("cat|") -> catalogRow(catalogFor(data.removePrefix("cat|"))) to false
                data.startsWith("rai|") -> raiCollectionRow(data.removePrefix("rai|"), page) to false
                data.startsWith("raimulti|") -> raiMultiRow(data.removePrefix("raimulti|"), page) to false
                data.startsWith("rairaccolta|") -> raiRaccoltaRow(data.removePrefix("rairaccolta|"), page) to false
                data.startsWith("sc|") -> {
                    val parts = data.split("|")
                    when (parts.getOrNull(1)) {
                        "slider" -> scSliderRow(parts.getOrNull(2) ?: "latest")
                        "genre" -> scGenreRow(parts.getOrNull(2)?.toIntOrNull() ?: -1, page)
                        else -> emptyList<SearchResponse>() to false
                    }
                }
                data.startsWith("always|") -> {
                    // scroll infinito SOLO sul catalogo verificato: zero rete,
                    // zero sorprese, tutti film italiani già controllati uno a uno
                    val all = catalogAll()
                    val from = (page - 1) * 24
                    val more = from + 24 < all.size
                    catalogRow(all.drop(from).take(24)) to more
                }
                else -> emptyList<SearchResponse>() to false
            }
        } catch (_: Exception) {
            emptyList<SearchResponse>() to false // una riga che fallisce non rompe la home
        }
        val (list, hasMore) = result
        return newHomePageResponse(
            listOf(
                HomePageList(request.name ?: name, list, true) // righe orizzontali stile Netflix
            ),
            hasNext = hasMore && list.isNotEmpty()
        )
    }

    // ------------------------------------------------------------------
    //  Righe StreamingCommunity (SOLO FILM)
    // ------------------------------------------------------------------

    /** Converte un titolo StreamingCommunity in SearchResponse (SOLO FILM). */
    private fun ScTitle.toSearch(): SearchResponse? {
        if (type != "movie" || id == 0 || name.isBlank()) return null
        val poster = images.firstOrNull { it.type == "poster" }?.filename
        return newMovieSearchResponse(name, sc.titleUrl(this), TvType.Movie) {
            if (!poster.isNullOrBlank()) this.posterUrl = "${sc.cdnRoot()}/images/$poster"
        }
    }

    private fun scMovies(list: List<ScTitle>): List<SearchResponse> =
        list.mapNotNull { it.toSearch() }.distinctBy { it.url }

    /** Riga per genere con VERO scroll infinito: paginator current/last. */
    private suspend fun scGenreRow(genreId: Int, page: Int): Pair<List<SearchResponse>, Boolean> {
        if (genreId <= 0 || page < 1 || page > 60) return emptyList<SearchResponse>() to false
        val paginator = sc.archivePage(genreId, page) ?: return emptyList<SearchResponse>() to false
        val items = scMovies(paginator.data)
        val more = items.isNotEmpty() &&
                paginator.current_page < paginator.last_page
        return items to more
    }

    /** Riga slider (latest/top10/trending): lista singola, finita. */
    private suspend fun scSliderRow(name: String): Pair<List<SearchResponse>, Boolean> {
        val slider = sc.slider(name) ?: return emptyList<SearchResponse>() to false
        return scMovies(slider.titles) to false
    }

    private fun catalogFor(key: String): List<FilmCatalogo> = when (key) {
        "toto" -> Catalogo.toto
        "commedia" -> Catalogo.commedia
        "autori" -> Catalogo.autori
        "neorealismo" -> Catalogo.neorealismo
        "melodramma" -> Catalogo.melodramma
        "amore" -> Catalogo.amore + byTitles(
            listOf("Senso", "Poveri ma belli", "La ragazza con la valigia",
                "Il sorpasso", "Matrimonio all'italiana", "Divorzio all'italiana")
        )
        "bambini" -> Catalogo.bambini + byTitles(
            listOf("Sciuscià", "Ladri di biciclette", "Miracolo a Milano")
        )
        "recenti" -> Catalogo.recenti
        "peplum" -> Catalogo.peplum
        "musical" -> Catalogo.musical
        "comiche" -> Catalogo.comiche
        else -> emptyList()
    }

    // Preleva film per titolo esatto da tutto il catalogo (per arricchire le righe)
    private fun byTitles(titles: List<String>): List<FilmCatalogo> {
        val all = catalogAll()
        return titles.mapNotNull { t -> all.firstOrNull { it.titolo == t } }
    }

    // Tutti i film del catalogo, deduplicati: alimenta lo scroll infinito
    private fun catalogAll(): List<FilmCatalogo> {
        val all = Catalogo.toto + Catalogo.commedia + Catalogo.autori +
                Catalogo.neorealismo + Catalogo.melodramma + Catalogo.amore +
                Catalogo.bambini + Catalogo.recenti + Catalogo.peplum +
                Catalogo.musical + Catalogo.comiche
        return all.distinctBy { it.id }
    }

    // I grandi titoli da mettere in cima alla home (risolti dal catalogo)
    private val picksTitles = listOf(
        "I soliti ignoti", "Ladri di biciclette", "Don Camillo",
        "Il ritorno di Don Camillo", "Fantozzi", "Il sorpasso",
        "C'eravamo tanto amati", "Nuovo Cinema Paradiso", "Un americano a Roma",
        "La grande guerra", "Guardie e ladri", "L'oro di Napoli",
        "Riso amaro", "Pane, amore e fantasia", "Amici miei",
        "Amarcord", "La strada", "Miracolo a Milano", "Roma città aperta",
        "Le notti di Cabiria", "Totò a colori"
    )

    private fun picks(): List<FilmCatalogo> {
        val all = catalogAll()
        val chosen = picksTitles.mapNotNull { t -> all.firstOrNull { it.titolo == t } }.toMutableList()
        // se qualche titolo manca, completa con gli altri film del catalogo
        for (f in all) {
            if (chosen.size >= 16) break
            if (chosen.none { it.id == f.id }) chosen.add(f)
        }
        return chosen.take(16)
    }

    // Normalizza per confronto titoli: minuscole, niente accenti/apostrofi
    private fun normalizeForSearch(s: String): String {
        val simplified = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return simplified.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    // Riga dal catalogo verificato: istantanea, zero richieste di rete
    private fun catalogRow(films: List<FilmCatalogo>): List<SearchResponse> {
        return films.map { f ->
            newMovieSearchResponse(f.titolo, "$iaUrl/details/${f.id}", TvType.Movie) {
                this.posterUrl = "$iaUrl/services/img/${f.id}"
                this.year = f.anno
            }
        }
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

    // ---- Righe RaiPlay da RACCOLTE (pagine HTML SSR) ----
    // Estrae SOLO i film (data-layout="single"): le serie TV hanno
    // data-layout="multi" e vengono scartate. Cache in memoria per
    // non rifare il fetch dell'HTML (~1 MB) a ogni apertura home.
    private val raccoltaCache = mutableMapOf<String, List<RaiItem>>()

    private suspend fun raiRaccoltaRow(url: String, page: Int): List<SearchResponse> {
        if (page > 1) return emptyList()
        val items = try {
            raccoltaCache.getOrPut(url) {
                val html = app.get(url).text
                val out = mutableListOf<RaiItem>()
                val seen = mutableSetOf<String>()
                for (block in html.split("card-item cell").drop(1)) {
                    if (!block.contains("data-layout=\"single\"")) continue
                    val path = Regex("data-video-json=\"(/programmi/[^\"]+\\.json)\"")
                        .find(block)?.groupValues?.get(1) ?: continue
                    if (path in seen) continue
                    val titleRaw = Regex("aria-label=\"maggiori informazioni su ([^\"]+)\"")
                        .find(block)?.groupValues?.get(1) ?: continue
                    val title = Jsoup.parse(titleRaw).text() // unescape &#x27; ecc.
                    if (!isCleanTitle(title)) continue
                    val poster = Regex("<img alt=\"[^\"]*\" src=\"([^\"]+)\"")
                        .find(block)?.groupValues?.get(1)
                    seen.add(path)
                    out.add(RaiItem(title, path, poster?.let { "$mainUrl$it" }, null))
                }
                out
            }
        } catch (_: Exception) {
            emptyList()
        }
        return items.map { it.toSearch() }
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
        val t = toStr(title)?.let { cleanIaTitle(it) } ?: id
        return newMovieSearchResponse(t, "$iaUrl/details/$id", TvType.Movie) {
            this.posterUrl = "$iaUrl/services/img/$id"
            this.year = toYear(year)
        }
    }

    // ------------------------------------------------------------------
    //  RICERCA — paginata (scroll infinito): IA + RaiPlay
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val q = query.trim()
        if (q.isBlank()) return newSearchResponseList(emptyList(), false)
        val out = mutableListOf<SearchResponse>()
        var hasMore = false

        // 1) Catalogo locale curato: istantaneo, titoli italiani puliti,
        //    film già verificati. Esce per primo nei risultati.
        val qn = normalizeForSearch(q)
        if (qn.isNotBlank()) {
            for (f in catalogAll()) {
                val tn = normalizeForSearch(f.titolo)
                if (tn.contains(qn) || qn.contains(tn)) {
                    out.add(
                        newMovieSearchResponse(f.titolo, "$iaUrl/details/${f.id}", TvType.Movie) {
                            this.posterUrl = "$iaUrl/services/img/${f.id}"
                            this.year = f.anno
                        }
                    )
                }
            }
        }

        // 2) StreamingCommunity (fonte FMHY): paginator reale con last_page,
        //    SOLO FILM — nuove uscite e tutto il catalogo in italiano
        var scMore = false
        try {
            val pg = sc.searchPage(q, page)
            if (pg != null) {
                val items = scMovies(pg.data)
                out.addAll(items)
                scMore = items.isNotEmpty() && pg.current_page < pg.last_page
            }
        } catch (_: Exception) {
        }

        // 3) Internet Archive, paginato (film del dominio pubblico)
        var iaMore = false
        try {
            val iaQuery = "mediatype:(movies) AND NOT subject:(horror) AND (title:($q) OR creator:($q))"
            val url = "$iaUrl/advancedsearch.php" +
                    "?q=" + java.net.URLEncoder.encode(iaQuery, "UTF-8") +
                    "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year" +
                    "&sort%5B%5D=downloads+desc&rows=20&page=$page&output=json"
            val res = tryParseJson<IA_SEARCH>(app.get(url).text)
            val docs = res?.response?.docs.orEmpty()
            out.addAll(docs.mapNotNull { it.toSearch() }.filter { isCleanTitle(it.name) })
            iaMore = docs.size >= 20
        } catch (_: Exception) {
        }

        // Nota: la ricerca di RaiPlay lato HTML è stata dismessa (SPA), quindi
        // il catalogo RaiPlay resta raggiungibile dalle righe della home.

        hasMore = scMore || iaMore
        return newSearchResponseList(out.distinctBy { it.url }, hasMore)
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
    //  PAGINA DEL FILM — instrada su RaiPlay, Internet Archive o
    //  StreamingCommunity
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        return when {
            url.contains("archive.org/details/") -> loadInternetArchive(url)
            url.startsWith("$mainUrl/programmi/") -> loadRaiProgram(url)
            url.startsWith("$mainUrl/video/") -> loadRaiVideo(url)
            url.contains("/titles/") -> loadStreamingCommunity(url)
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

        // Solo file veramente riproducibili (MP4/MKV/AVI, niente Ogg né derivati IA)
        val videoFiles = playableIaFiles(res)
        if (videoFiles.isEmpty()) {
            throw ErrorLoadingException("Nessun file video riproducibile su Internet Archive")
        }

        // Sottotitoli .vtt presenti nell'item
        val subs = res.files.orEmpty()
            .filter { (it.name ?: "").endsWith(".vtt", true) }
            .map { IaSub(iaFileUrl(res, identifier, it.name ?: ""), "Internet Archive") }
            .take(8)

        val metaYear = toYear(m.year) ?: extractYear(m.date)
        val metaPlot = toStr(m.description)?.let { cleanHtml(it) }
        val metaTags = buildList {
            toStr(m.creator)?.let { add(it) }
            toStr(m.subject)?.split(",", ";")?.forEach { s ->
                val t = s.trim()
                if (t.isNotEmpty() && t !in this) add(t)
            }
        }.take(6)

        val uniqueNames = videoFiles.map { getUniqueName(it.name ?: "") }.distinct()

        return if (uniqueNames.size <= 1) {
            // FILM SINGOLO: link DIRETTI costruiti dai metadata.
            // Non usiamo l'estrattore interno (ha un selettore CSS rotto e
            // lascia il player in caricamento all'infinito).
            newMovieLoadResponse(
                title, url, TvType.Movie,
                LoadData(src = "iaplaylist", id = identifier, urlData = buildIaUrls(res, identifier, videoFiles), subs = subs)
            ) {
                this.plot = metaPlot
                this.year = metaYear
                this.tags = metaTags
                this.posterUrl = "$iaUrl/services/img/$identifier"
                this.duration = (videoFiles.maxOfOrNull { it.lengthInSeconds } ?: 0f).let {
                    if (it > 0f) (it / 60).roundToInt() else null
                }
                this.actors = toStr(m.creator)?.let {
                    listOf(ActorData(Actor(it, ""), roleString = "Regia / Interpreti"))
                }
            }
        } else {
            // Item con più video distinti: playlist a episodi (pattern ufficiale)
            val mostFrequentMinutes = videoFiles
                .map { (it.lengthInSeconds / 60).roundToInt() }
                .groupBy { it }
                .maxByOrNull { it.value.count() }?.key

            val episodes: List<Episode> = uniqueNames.map { uniName ->
                val files = videoFiles.filter { getUniqueName(it.name ?: "") == uniName }
                val file = files.first()
                val cleanedName = (file.original ?: file.name ?: uniName)
                    .substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
                newEpisode(
                    LoadData(
                        src = "iaplaylist", id = identifier,
                        urlData = buildIaUrls(res, identifier, files)
                    ).toJson()
                ) {
                    name = file.title ?: cleanedName
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

    // File video riproducibili da ExoPlayer: contenitori noti, formati noti,
    // niente Ogg Video (ExoPlayer non lo legge) e niente duplicati "IA".
    private fun playableIaFiles(res: MetadataResult): List<MediaFile> {
        return res.files.orEmpty().filter { f ->
            val fmt = (f.format ?: "").lowercase()
            val fileName = (f.name ?: "").lowercase()
            val goodContainer = fileName.endsWith(".mp4") || fileName.endsWith(".m4v") ||
                    fileName.endsWith(".mkv") || fileName.endsWith(".avi") ||
                    fileName.endsWith(".mpg") || fileName.endsWith(".mpeg")
            val goodFormat = fmt.contains("h.264") || fmt.contains("mpeg4") ||
                    fmt.contains("mpeg") || fmt.contains("quicktime") ||
                    fmt.contains("matroska") || fmt.contains("divx")
            f.lengthInSeconds >= 60.0 && goodContainer && goodFormat &&
                    !fmt.endsWith("ia") && !fmt.contains("ogg")
        }
    }

    // URL diretto al file: preferisce il server dati (più affidabile e veloce),
    // ricade su /download/. Il nome file è URL-encoded (spazi e parentesi).
    private fun iaFileUrl(res: MetadataResult, id: String, fileName: String): String {
        val enc = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val server = res.server
        val dir = res.dir
        return if (!server.isNullOrBlank() && !dir.isNullOrBlank())
            "https://$server$dir/$enc"
        else
            "$iaUrl/download/$id/$enc"
    }

    private fun buildIaUrls(res: MetadataResult, id: String, files: List<MediaFile>): Set<URLData> {
        return files.sortedByDescending { it.size ?: 0f }.take(4).map { f ->
            val q = (f.height ?: 480).coerceIn(240, 1080)
            URLData(iaFileUrl(res, id, f.name ?: ""), f.format ?: "", f.size ?: 0f, q)
        }.toSet()
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

    // ---- StreamingCommunity: scheda titolo (API Inertia, SOLO FILM) ----
    private suspend fun loadStreamingCommunity(url: String): LoadResponse {
        val inertia = sc.loadTitle(url)
            ?: throw ErrorLoadingException("StreamingCommunity non raggiungibile")
        val t = inertia.props.title
            ?: throw ErrorLoadingException("Titolo non disponibile")
        if (t.type != "movie") {
            throw ErrorLoadingException("Questo è una serie TV: RetroCinema ha solo film")
        }

        val cdn = inertia.props.cdn_url?.takeIf { it.isNotBlank() } ?: sc.cdnRoot()
        val poster = t.images.firstOrNull { it.type == "poster" }?.filename
        val bg = t.images.firstOrNull { it.type == "background" }?.filename
        val year = t.release_date?.take(4)?.toIntOrNull()?.takeIf { it in 1880..2030 }
        val tags = t.genres.map { g ->
            g.name.lowercase().replaceFirstChar { c -> c.uppercase() }
        }.filter { it.isNotBlank() }.take(6)

        val iframeUrl = "${sc.ensureRoot()}it/iframe/${t.id}&canPlayFHD=1"
        val recommendations = inertia.props.sliders?.firstOrNull()?.titles
            ?.let { scMovies(it) }?.take(16)

        return newMovieLoadResponse(
            t.name, url, TvType.Movie,
            LoadData(src = "sc", scUrl = iframeUrl, scTmdb = t.tmdb_id)
        ) {
            this.plot = t.plot
            this.year = year
            t.runtime?.let { if (it > 0) this.duration = it }
            if (!poster.isNullOrBlank()) this.posterUrl = "$cdn/images/$poster"
            if (!bg.isNullOrBlank()) this.backgroundPosterUrl = "$cdn/images/$bg"
            if (tags.isNotEmpty()) this.tags = tags
            this.actors = t.main_actors?.take(8)?.map { a ->
                ActorData(Actor(a.name, ""), roleString = "Interpreti")
            }
            this.recommendations = recommendations
        }
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
            // ---------- Internet Archive: link diretti + sottotitoli .vtt ----------
            "iaplaylist" -> {
                val urls = load.urlData.orEmpty()
                load.subs.orEmpty().forEach { s ->
                    if (s.url.isNotBlank()) {
                        subtitleCallback(SubtitleFile(s.lang.ifBlank { "Italiano" }, s.url))
                    }
                }
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

            // ---------- StreamingCommunity: VixCloud + fallback VixSrc ----------
            "sc" -> {
                val pageUrl = load.scUrl ?: return false
                var found = false
                // 1) Player VixCloud di StreamingCommunity (Cloudflare
                //    bypassato in app da CloudflareKiller)
                try {
                    val doc = app.get(
                        pageUrl,
                        headers = mapOf(
                            "Referer" to sc.ensureRoot(),
                            "User-Agent" to SC_UA
                        )
                    ).document
                    val src = doc.select("iframe").firstOrNull()?.attr("src")
                    if (!src.isNullOrBlank()) {
                        found = vix.vixCloud(src, sc.ensureRoot(), callback, subtitleCallback) || found
                    }
                } catch (_: Exception) {
                }
                // 2) Fallback VixSrc via tmdbId (dominio separato)
                val tmdb = load.scTmdb
                if (tmdb != null && tmdb > 0) {
                    try {
                        found = vix.vixSrc(
                            "https://vixsrc.to/movie/$tmdb", "https://vixsrc.to/",
                            callback, subtitleCallback
                        ) || found
                    } catch (_: Exception) {
                    }
                }
                found
            }

            else -> false
        }
    }
}
