package com.retrocinema.raiplay

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
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Provider RaiPlay: catalogo curato di film classici del servizio
 * pubblico italiano. Tutti i contenuti sono gratuiti e legali.
 *
 * Catagna di estrazione verificata (settembre 2026):
 *   1. pagine programma/film:  /programmi/<slug>.json      -> first_item_path
 *   2. pagina del singolo video: /video/....json           -> video.content_url
 *   3. stream: content_url + "&output=71"                  -> playlist HLS (m3u8)
 *
 * Le righe della home sono curate a mano (slug verificati) più
 * righe dinamiche lette dagli indici JSON ufficiali di RaiPlay.
 */
class RaiPlayProvider : MainAPI() {

    override var mainUrl = "https://www.raiplay.it"
    override var name = "RaiPlay"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "it"
    override val hasMainPage = true

    companion object {
        // Slugs verificati manualmente (2026-09): film classici presenti su RaiPlay.
        // Se RaiPlay rimuove un titolo, la riga lo salta automaticamente.
        private val COMEDIE_ITALIANE = listOf(
            "isolitiignoti",                                // I soliti ignoti (1958)
            "paneamoreefantasia",                           // Pane, amore e fantasia (1953)
            "totopeppinoelamalafemmina",                    // Totò, Peppino e... la malafemmina (1956)
            "divorzioallitaliana",                          // Divorzio all'italiana (1961)
            "labandadeglionesti",                           // La banda degli onesti (1956)
            "totodiabolicus",                               // Totò diabolicus (1962)
            "sedottaeabbandonata",                          // Sedotta e abbandonata (1964)
            "drammadellagelosiatuttiiparticolariincronaca", // Dramma della gelosia (1970)
            "audacecolpodeisolitiignoti",                   // Audace colpo dei soliti ignoti (1959)
            "risoamaro",                                    // Riso amaro (1949)
            "romacittaaperta",                              // Roma città aperta (1945)
            "salvatoregiuliano",                            // Salvatore Giuliano (1962)
            "ilgattopardo",                                 // Il Gattopardo (1963)
            "gruppodifamigliainuninterno",                  // Gruppo di famiglia in un interno (1974)
        )

        private val CLASSICI_HOLLYWOOD = listOf(
            "gilda",                    // Gilda (1946) - noir con Rita Hayworth
            "lasignoradelvenerdi",      // La signora del venerdì (1940) - screwball comedy
            "funnygirl",                // Funny Girl (1968) - musical con Barbra Streisand
            "daquialleternita",         // Da qui all'eternità (1953)
            "lammutinamentodelcaine",   // L'ammutinamento del Caine (1954)
            "incantesimofilm",          // Incantesimo (1945)
            "avventurieridellaria",     // Avventurieri dell'aria (1939)
            "laragazzadelsecolo",       // La ragazza del secolo
        )

        private const val STANLIO_COLLECTION = "/collezioni/stanlioeollio-edizionirestaurate.json"
        private const val FILM_INDEX = "/tipologia/film/index.json"
        private const val TECHE_INDEX = "/tipologia/techerai/index.json"
        private const val TOP_FILMS_BLOCK = "I film più visti della settimana"
    }

    // ---------- Modelli JSON unificati (programma / collezione / video) ----------
    private data class RaiNode(
        val name: String? = null,
        @JsonProperty("path_id") val pathId: String? = null,
        @JsonProperty("first_item_path") val firstItemPath: String? = null,
        @JsonProperty("first_item_duration") val firstItemDuration: String? = null,
        val subtitle: String? = null,
        val description: String? = null,
        val video: RaiVideo? = null,
        val images: RaiImages? = null,
        val blocks: List<RaiNode>? = null,
        val contents: List<RaiNode>? = null,
    )

    private data class RaiVideo(
        @JsonProperty("content_url") val contentUrl: String? = null,
        val duration: String? = null,
    )

    private data class RaiImages(
        val landscape: String? = null,
        val portrait: String? = null,
    )

    // ---------- Utility ----------
    private fun RaiImages.bestPoster(): String? =
        (portrait?.takeIf { it.isNotBlank() }) ?: (landscape?.takeIf { it.isNotBlank() })

    // L'anno lo danno come sotto-titolo ("1958") o in coda al nome
    private fun yearFromString(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val digits = s.filter { it.isDigit() }
        if (digits.length < 4) return null
        val y = digits.takeLast(4).toIntOrNull() ?: return null
        return if (y in 1890..2030) y else null
    }

    // "01:41:10" -> minuti
    private fun parseMinutes(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val parts = s.split(":").map { it.trim().toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1]
            2 -> parts[0]
            else -> null
        }
    }

    private fun normalizeJsonUrl(u: String): String {
        var path = u.substringBefore("?")
        if (path.contains("/video/") && path.endsWith(".html")) {
            path = path.removeSuffix(".html") + ".json"
        } else if (!path.endsWith(".json")) {
            if (path.endsWith("/")) path = path.dropLast(1)
            path += ".json"
        }
        return if (path.startsWith("http")) path else fixUrl(path)
    }

    private fun collectChildren(node: RaiNode): List<RaiNode> {
        val out = mutableListOf<RaiNode>()
        fun walk(n: RaiNode) {
            n.blocks?.forEach { walk(it) }
            n.contents?.forEach { c ->
                if (c.pathId != null && !c.name.isNullOrBlank()) out.add(c)
                walk(c)
            }
        }
        walk(node)
        return out.distinctBy { it.pathId }
    }

    private fun findBlock(node: RaiNode, blockName: String): RaiNode? {
        node.blocks?.forEach { b ->
            if (b.name == blockName) return b
            findBlock(b, blockName)?.let { return it }
        }
        return null
    }

    private fun RaiNode.toSearchResponse(): SearchResponse? {
        val n = name?.trim() ?: return null
        val pid = pathId ?: return@toSearchResponse null
        return newMovieSearchResponse(n, fixUrl(pid), TvType.Movie) {
            this.posterUrl = images?.bestPoster()?.let { fixUrl(it) }
            this.year = yearFromString(subtitle) ?: yearFromString(n)
        }
    }

    // ---------- 1. Home page: righe curate ----------
    override val mainPage = mainPageOf(
        Pair("cult", "Commedia all'italiana"),
        Pair("hollywood", "Classici di Hollywood"),
        Pair("stanlio", "Stanlio e Ollio"),
        Pair("top", "Film del momento"),
        Pair("teche", "Le Teche Rai"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items: List<SearchResponse> = if (page > 1) {
            emptyList()
        } else {
            when (request.data) {
                "cult" -> curatedSlugsToItems(COMEDIE_ITALIANE)
                "hollywood" -> curatedSlugsToItems(CLASSICI_HOLLYWOOD)
                "stanlio" -> nodeToItems(fixUrl(STANLIO_COLLECTION), null, 14)
                "top" -> nodeToItems(fixUrl(FILM_INDEX), TOP_FILMS_BLOCK, 14)
                "teche" -> nodeToItems(fixUrl(TECHE_INDEX), null, 14)
                else -> emptyList()
            }
        }
        return newHomePageResponse(HomePageList(request.name ?: name, items), hasNext = false)
    }

    // Per le righe curate: scarica il JSON di ogni film, salta i titoli rimossi
    private suspend fun curatedSlugsToItems(slugs: List<String>): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        for (slug in slugs) {
            try {
                val node = tryParseJson<RaiNode>(app.get("$mainUrl/programmi/$slug.json").text)
                    ?: continue
                val item = node.toSearchResponse() ?: continue
                items.add(item)
            } catch (e: Exception) {
                // titolo non più disponibile: prosegui con il prossimo
            }
        }
        return items
    }

    // Per gli indici JSON ufficiali (collezioni e tipologie)
    private suspend fun nodeToItems(jsonUrl: String, blockName: String?, max: Int): List<SearchResponse> {
        val node = tryParseJson<RaiNode>(app.get(jsonUrl).text) ?: return emptyList()
        val chosen = if (blockName != null) {
            findBlock(node, blockName)?.let { collectChildren(it) } ?: emptyList()
        } else {
            collectChildren(node)
        }
        return chosen.take(max).mapNotNull { it.toSearchResponse() }
    }

    // ---------- 2. Ricerca libera (pagina SSR di RaiPlay) ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/ricerca.html", params = mapOf("q" to query)).document
        return document.select("div.card-item[data-info-url]").mapNotNull { card ->
            val infoUrl = card.attr("data-info-url").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val link = card.selectFirst("a.card-item__link") ?: return@mapNotNull null
            val title = link.attr("aria-label")
                .removePrefix("maggiori informazioni su ")
                .trim()
                .takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            newMovieSearchResponse(title, fixUrl(infoUrl), TvType.Movie) {
                this.posterUrl = poster?.let { fixUrl(it) }
            }
        }
    }

    // ---------- 3. Pagina del risultato ----------
    override suspend fun load(url: String): LoadResponse {
        val jsonUrl = normalizeJsonUrl(url)
        val node = tryParseJson<RaiNode>(app.get(jsonUrl).text)
            ?: throw ErrorLoadingException("RaiPlay: risposta non valida")
        val title = node.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw ErrorLoadingException("RaiPlay: titolo mancante")

        // --- CASO A: film singolo (il programma ha un unico video) ---
        val firstPath = node.firstItemPath
        if (firstPath != null) {
            val vNode = runCatching {
                tryParseJson<RaiNode>(app.get(fixUrl(firstPath)).text)
            }.getOrNull()
            val contentUrl = vNode?.video?.contentUrl
            if (contentUrl != null) {
                return newMovieLoadResponse(
                    title,
                    jsonUrl,
                    TvType.Movie,
                    contentUrl // loadLinks() riceverà l'URL del relinker
                ) {
                    this.posterUrl = (vNode.images?.bestPoster() ?: node.images?.bestPoster())
                        ?.let { fixUrl(it) }
                    this.year = yearFromString(vNode.subtitle) ?: yearFromString(node.subtitle)
                        ?: yearFromString(title)
                    this.plot = (vNode.description ?: node.description)?.trim()
                    this.duration = parseMinutes(
                        vNode.video?.duration ?: node.firstItemDuration
                    )
                }
            }
        }

        // --- CASO B: collezione / programma con più contenuti ---
        // (es. "Stanlio e Ollio": ogni film diventa un "episodio" navigabile)
        val children = collectChildren(node)
        if (children.isEmpty()) throw ErrorLoadingException("Nessun contenuto disponibile")

        val episodes = children.mapNotNull { c ->
            val pid = c.pathId ?: return@mapNotNull null
            newEpisode(fixUrl(pid)) {
                this.name = c.name?.trim()
                this.posterUrl = c.images?.bestPoster()?.let { fixUrl(it) }
            }
        }

        return newTvSeriesLoadResponse(title, jsonUrl, TvType.TvSeries, episodes) {
            this.posterUrl = node.images?.bestPoster()?.let { fixUrl(it) }
            this.plot = node.description?.trim()
        }
    }

    // ---------- 4. Link video ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Il "data" può essere: un URL relinker diretto (film) oppure
        // l'URL JSON di un contenuto figlio (collezioni/episodi).
        var streamUrl: String? = null

        if (data.contains("relinkerServlet", true)) {
            streamUrl = data
        } else {
            val node = runCatching {
                tryParseJson<RaiNode>(app.get(normalizeJsonUrl(data)).text)
            }.getOrNull()
            streamUrl = node?.video?.contentUrl
            if (streamUrl == null) {
                val fip = node?.firstItemPath
                if (fip != null) {
                    val vNode = runCatching {
                        tryParseJson<RaiNode>(app.get(fixUrl(fip)).text)
                    }.getOrNull()
                    streamUrl = vNode?.video?.contentUrl
                }
            }
        }

        val base = streamUrl ?: return false
        callback(
            newExtractorLink(
                source = name,
                name = "$name - RaiPlay",
                url = "$base&output=71", // verificato: restituisce playlist HLS (m3u8)
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
            }
        )
        return true
    }
}
