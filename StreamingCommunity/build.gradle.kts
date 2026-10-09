// StreamingCommunity (versione RetroCinema) - homepage estremamente ricca
version = 1

cloudstream {
    description = "Il modo piu ricco di usare StreamingCommunity: oltre 30 file in home. Top 10 di oggi, Tendenze, Aggiunti di recente, In arrivo, Nuove uscite per anno (2026, 2025, 2024), le storie d'amore piu belle, le commedie piu belle, film per la famiglia e per i bambini, e tutti i generi (commedia, romance, avventura, western, musica, guerra, storia...). OGNI riga con scroll infinito: migliaia di titoli. Film e serie in italiano con 1080p FHD. Basato sul provider di doGior (GPL-3.0), con domini di riserva automatici."
    authors = listOf("antonydp", "doGior")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Documentary",
        "Cartoon"
    )

    // false: il bypass Cloudflare (CloudflareKiller) e autonomo nell'app e NON
    // richiede risorse del plugin; con true il task make cerca res.apk che
    // non esiste per moduli senza cartella res/ (verificato nel sorgente
    // recloudstream/gradle: Tasks.kt -> zipTree(resApkFile))
    requiresResources = false
    language = "it"

    iconUrl = "https://streamingunity.win/apple-touch-icon.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
