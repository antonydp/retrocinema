// Usa un numero intero per la versione: incrementa a ogni release
version = 1

cloudstream {
    description = "Il meglio di RaiPlay per gli amanti del cinema di una volta: Commedia all'italiana (Totò, Sordi, Gassman), Grandi Classici di Hollywood, Stanlio e Ollio e le Teche Rai. Tutto gratuito, legale e in italiano."
    authors = listOf("antonydp")
    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta-only
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/antonydp/retrocinema/main/logo.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
