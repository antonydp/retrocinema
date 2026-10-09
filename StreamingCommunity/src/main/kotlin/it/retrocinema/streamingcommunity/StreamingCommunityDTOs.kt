package it.retrocinema.streamingcommunity

/**
 * DTO per StreamingCommunity (basato sul provider "HadEnough" di doGior, GPL-3.0).
 * Jackson tollerante: i campi sconosciuti vengono ignorati (le API cambiano spesso).
 */
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.toJson

/** Riga della homepage: slider oppure archivio con filtri (genere/anno/tipo/ordine). */
data class ArchiveQuery(
    val kind: String,            // "slider" | "archive"
    val label: String,           // nome della riga mostrato nell'app
    val slider: String? = null,  // trending | latest
    val genre: Int? = null,      // id genere (array nel sito)
    val year: Int? = null,       // anno di uscita (singolo nel sito)
    val type: String? = null,    // "movie" | "tv" (verificato live)
    val sort: String? = null,    // release_date | created_at | score | views | name
    val limit: Int? = null,      // se presente: riga fissa (es. Top 10) senza paginazione
)

/** Paginator Laravel usato da /it/archive e /it/search. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ScPaginator(
    @JsonProperty("current_page") val currentPage: Int = 0,
    @JsonProperty("data") val data: List<ScTitle> = emptyList(),
    @JsonProperty("last_page") val lastPage: Int = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScTitle(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String = "",
    @JsonProperty("slug") val slug: String = "",
    @JsonProperty("type") val type: String = "",
    @JsonProperty("images") val images: List<ScPosterImage> = emptyList(),
) {
    fun getPoster(): String? = images.firstOrNull { it.type == "poster" }?.filename
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScPosterImage(
    @JsonProperty("filename") val filename: String = "",
    @JsonProperty("type") val type: String = "",
    @JsonProperty("imageable_type") val imageableType: String? = null,
    @JsonProperty("imageable_id") val imageableId: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScGenre(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String = "",
    @JsonProperty("type") val type: String? = null,
)

/** Dati extra per l'estrazione (serializzati dentro l'URL della sorgente). */
data class ScLoadData(
    val url: String,
    val type: String,
    val tmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScInertiaResponse(
    @JsonProperty("props") val props: ScProps,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("version") val version: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScProps(
    @JsonProperty("scws_url") val scwsUrl: String? = null,
    @JsonProperty("cdn_url") val cdnUrl: String? = null,
    @JsonProperty("title") val title: ScTitleProp? = null,
    @JsonProperty("loadedSeason") val loadedSeason: ScSeason? = null,
    @JsonProperty("sliders") val sliders: List<ScSlider>? = null,
    @JsonProperty("genres") val genres: List<ScGenre>? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("browseMoreApiRoute") val browseMoreApiRoute: String? = null,
    @JsonProperty("titles") val titles: List<ScTitle>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScSeason(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("number") val number: Int = 0,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("title_id") val titleId: Int? = null,
    @JsonProperty("episodes") val episodes: List<ScEpisode>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScEpisode(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("number") val number: Int = 0,
    @JsonProperty("name") val name: String = "",
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("scws_id") val scwsId: Int? = null,
    @JsonProperty("season_id") val seasonId: Int? = null,
    @JsonProperty("images") val images: List<ScPosterImage> = emptyList(),
) {
    fun getCover(): String? = images.firstOrNull { it.type == "cover" }?.filename
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScSlider(
    @JsonProperty("name") val name: String = "",
    @JsonProperty("label") val label: String = "",
    @JsonProperty("titles") val titles: List<ScTitle> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScMainActor(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScTitleProp(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String = "",
    @JsonProperty("slug") val slug: String = "",
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("score") val score: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("age") val age: Int? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("tmdb_id") val tmdbId: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("seasons_count") val seasonsCount: Int? = null,
    @JsonProperty("scws_id") val scwsId: Int? = null,
    @JsonProperty("trailers") val trailers: List<ScTrailer>? = null,
    @JsonProperty("seasons") val seasons: List<ScSeason>? = null,
    @JsonProperty("images") val images: List<ScPosterImage> = emptyList(),
    @JsonProperty("genres") val genres: List<ScGenre> = emptyList(),
    @JsonProperty("main_actors") val mainActors: List<ScMainActor>? = null,
) {
    fun getBackgroundImageId(): String? = images.firstOrNull { it.type == "background" }?.filename
    fun getPosterImageId(): String? = images.firstOrNull { it.type == "poster" }?.filename
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ScTrailer(
    @JsonProperty("id") val id: Int = 0,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("youtube_id") val youtubeId: String? = null,
    @JsonProperty("title_id") val titleId: Int? = null,
) {
    fun getYoutubeUrl(): String? =
        youtubeId?.let { "https://www.youtube.com/watch?v=$it" }
}

/** Helper per costruire le righe della homepage. */
internal fun archiveQuery(
    label: String,
    genre: Int? = null,
    year: Int? = null,
    type: String? = null,
    sort: String? = null,
    limit: Int? = null,
): Pair<String, String> =
    ArchiveQuery(kind = "archive", label = label, genre = genre, year = year, type = type, sort = sort, limit = limit).toJson() to label

internal fun sliderQuery(slider: String, label: String): Pair<String, String> =
    ArchiveQuery(kind = "slider", label = label, slider = slider).toJson() to label
