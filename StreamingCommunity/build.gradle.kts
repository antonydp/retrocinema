// StreamingCommunity (versione RetroCinema) - homepage ordinata e infinita
version = 2

cloudstream {
    description = "StreamingCommunity con la home organizzata come si deve: Top 10 serie e Top 10 film (i piu visti), Tendenze di adesso, Aggiunti di recente, poi tutti i generi ordinati per tendenza (commedie, storie d'amore, famiglia, animazione, avventura...) e in fondo le annate 2026/2025/2024. Ogni riga con scroll infinito: migliaia di titoli. Film e serie in italiano con 1080p FHD. Basato sul provider di doGior (GPL-3.0), con domini di riserva automatici."
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
