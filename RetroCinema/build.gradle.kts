// Usa un numero intero per la versione: incrementa a ogni release
version = 5

cloudstream {
    description = "Tutto in ITALIANO: 87+ film italiani VERIFICATI uno a uno (Totò, Sordi, Gassman, Fellini, De Sica, Monicelli, neorealismo, peplum, melodrammi), comiche mute, Stanlio e Ollio e i Grandi Classici su RaiPlay. Scroll infinito, niente horror, niente serie TV, solo film per la famiglia."
    authors = listOf("antonydp", "Luna712", "recloudstream")
    status = 1 // 0: Down, 1: Ok, 2: Slow, 3: Beta-only
    tvTypes = listOf("Movie")
    requiresResources = false
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/antonydp/retrocinema/main/logo.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
