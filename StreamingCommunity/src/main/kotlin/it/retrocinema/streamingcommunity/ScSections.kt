package it.retrocinema.streamingcommunity

import android.content.Context
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStore

/**
 * Una sezione della homepage di StreamingCommunity.
 * L'id serve per salvare ordine e attivazione nel menu impostazioni.
 */
data class ScSection(
    val id: String,
    val label: String,
    val query: ArchiveQuery,
) {
    fun toPair(): Pair<String, String> = query.toJson() to label
}

/**
 * Catalogo delle sezioni e configurazione utente.
 *
 * L'utente puo riorganizzare/disattivare le sezioni dal menu impostazioni
 * del plugin (gear in home): la scelta viene salvata con DataStore come
 * lista CSV di id nell'ordine voluto; assente/vuota = predefinito.
 */
object ScSections {
    const val KEY = "sc_home_sections_v1"

    /** Le 24 sezioni nell'ordine predefinito. */
    val DEFAULT: List<ScSection> = listOf(
        // --- In cima: top 10 separati per tipo ---
        ScSection("top_serie", "Top 10 serie", ArchiveQuery(kind = "archive", label = "Top 10 serie", type = "tv", sort = "views", limit = 10)),
        ScSection("top_film", "Top 10 film", ArchiveQuery(kind = "archive", label = "Top 10 film", type = "movie", sort = "views", limit = 10)),
        // --- Slider ufficiali del sito ---
        ScSection("trending", "Tendenze di adesso", ArchiveQuery(kind = "slider", label = "Tendenze di adesso", slider = "trending")),
        ScSection("latest", "Aggiunti di recente", ArchiveQuery(kind = "slider", label = "Aggiunti di recente", slider = "latest")),
        // --- Generi ordinati per tendenza (scroll infinito) ---
        ScSection("gen_commedie", "Commedie", ArchiveQuery(kind = "archive", label = "Commedie", genre = 12, sort = "views")),
        ScSection("gen_storie_damore", "Storie d'amore", ArchiveQuery(kind = "archive", label = "Storie d'amore", genre = 15, sort = "views")),
        ScSection("gen_famiglia", "Famiglia", ArchiveQuery(kind = "archive", label = "Famiglia", genre = 16, sort = "views")),
        ScSection("gen_animazione", "Animazione", ArchiveQuery(kind = "archive", label = "Animazione", genre = 19, sort = "views")),
        ScSection("gen_avventura", "Avventura", ArchiveQuery(kind = "archive", label = "Avventura", genre = 11, sort = "views")),
        ScSection("gen_azione", "Azione", ArchiveQuery(kind = "archive", label = "Azione", genre = 4, sort = "views")),
        ScSection("gen_drammi", "Drammi", ArchiveQuery(kind = "archive", label = "Drammi", genre = 1, sort = "views")),
        ScSection("gen_crime", "Crime", ArchiveQuery(kind = "archive", label = "Crime", genre = 2, sort = "views")),
        ScSection("gen_mistero", "Mistero", ArchiveQuery(kind = "archive", label = "Mistero", genre = 6, sort = "views")),
        ScSection("gen_fantascienza", "Fantascienza", ArchiveQuery(kind = "archive", label = "Fantascienza", genre = 10, sort = "views")),
        ScSection("gen_fantasy", "Fantasy", ArchiveQuery(kind = "archive", label = "Fantasy", genre = 8, sort = "views")),
        ScSection("gen_western", "Western", ArchiveQuery(kind = "archive", label = "Western", genre = 20, sort = "views")),
        ScSection("gen_guerra", "Guerra", ArchiveQuery(kind = "archive", label = "Guerra", genre = 9, sort = "views")),
        ScSection("gen_storia", "Storia", ArchiveQuery(kind = "archive", label = "Storia", genre = 22, sort = "views")),
        ScSection("gen_musical", "Musical e musica", ArchiveQuery(kind = "archive", label = "Musical e musica", genre = 14, sort = "views")),
        ScSection("gen_documentari", "Documentari", ArchiveQuery(kind = "archive", label = "Documentari", genre = 24, sort = "views")),
        ScSection("gen_filmtv", "Film TV", ArchiveQuery(kind = "archive", label = "Film TV", genre = 21, sort = "views")),
        // --- Le annate, in fondo ---
        ScSection("annata_2026", "Nuove uscite 2026", ArchiveQuery(kind = "archive", label = "Nuove uscite 2026", year = 2026)),
        ScSection("annata_2025", "Nuove uscite 2025", ArchiveQuery(kind = "archive", label = "Nuove uscite 2025", year = 2025)),
        ScSection("annata_2024", "Nuove uscite 2024", ArchiveQuery(kind = "archive", label = "Nuove uscite 2024", year = 2024)),
    )

    private val byId: Map<String, ScSection> = DEFAULT.associateBy { it.id }

    /** Sezioni attive salvate (ordine compreso); predefinito se nessuna scelta. */
    fun loadOrder(context: Context?): List<ScSection> {
        if (context == null) return DEFAULT
        val csv = runCatching {
            with(DataStore) { context.getKey<String>(KEY, null) }
        }.getOrNull().orEmpty()
        return fromCsv(csv) ?: DEFAULT
    }

    /** Da CSV di id a sezioni; null se il CSV e vuoto o non valido (= predefinito). */
    fun fromCsv(csv: String): List<ScSection>? {
        if (csv.isBlank()) return null
        val ids = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val sections = ids.mapNotNull { byId[it] }.distinct()
        return sections.ifEmpty { null }
    }

    fun toCsv(sections: List<ScSection>): String = sections.joinToString(",") { it.id }

    fun save(context: Context, sections: List<ScSection>) {
        runCatching {
            with(DataStore) { context.setKey(KEY, toCsv(sections)) }
        }
    }

    /** Torna all'ordine predefinito. */
    fun reset(context: Context) {
        runCatching {
            with(DataStore) { context.setKey(KEY, "") }
        }
    }

    // ------------------------------------------------------------------
    //  Preset: scorciatoie nel menu impostazioni (solo ordine e attive)
    // ------------------------------------------------------------------

    fun presetStandard(): List<ScSection> = DEFAULT

    /** Famiglia e bambini in cima, poi top10/novita, resto e annate. */
    fun presetFamiglia(): List<ScSection> = listOfIds(
        "gen_famiglia", "gen_animazione", "gen_storie_damore", "gen_commedie",
        "top_film", "top_serie", "latest", "trending",
        "gen_avventura", "gen_musical", "gen_fantasy", "gen_fantascienza",
        "gen_azione", "gen_drammi", "gen_crime", "gen_mistero",
        "gen_guerra", "gen_storia", "gen_western", "gen_documentari", "gen_filmtv",
        "annata_2026", "annata_2025", "annata_2024",
    )

    /** Niente righe solo-serie: tutto film. */
    fun presetSoloFilm(): List<ScSection> = listOfIds(
        "top_film", "latest", "trending",
        "gen_commedie", "gen_storie_damore", "gen_famiglia", "gen_animazione",
        "gen_avventura", "gen_azione", "gen_drammi", "gen_crime", "gen_mistero",
        "gen_fantascienza", "gen_fantasy", "gen_western", "gen_guerra",
        "gen_storia", "gen_musical", "gen_documentari",
        "annata_2026", "annata_2025", "annata_2024",
    )

    /** Niente righe solo-film: tutto serie. */
    fun presetSoloSerie(): List<ScSection> = listOfIds(
        "top_serie", "latest", "trending",
        "gen_commedie", "gen_storie_damore", "gen_famiglia", "gen_animazione",
        "gen_avventura", "gen_azione", "gen_drammi", "gen_crime", "gen_mistero",
        "gen_fantascienza", "gen_fantasy", "gen_western", "gen_guerra",
        "gen_storia", "gen_musical", "gen_documentari", "gen_filmtv",
        "annata_2026", "annata_2025", "annata_2024",
    )

    private fun listOfIds(vararg ids: String): List<ScSection> =
        ids.mapNotNull { byId[it] }
}
