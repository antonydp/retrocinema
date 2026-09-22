// Usa un numero intero per la versione: incrementa a ogni release
version = 1

cloudstream {
    description = "Film e serie di StreamingCommunity, con risoluzione automatica del dominio. Codice adattato dal provider di doGior (doGiorsHadEnough). Nota: i domini di questi siti cambiano spesso; se non funziona, aggiorna il plugin."
    authors = listOf("antonydp", "doGior")
    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta-only
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Documentary",
        "Cartoon"
    )
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/antonydp/retrocinema/main/logo.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
