// Usa un numero intero per la versione: incrementa a ogni release
version = 6

cloudstream {
    description = "Tutto in ITALIANO: 114+ film italiani VERIFICATI uno a uno (Totò, Sordi, Gassman, Fellini, De Sica, Monicelli, neorealismo, peplum, melodrammi, storie d'amore, film con i bambini, Fantozzi e Benigni), comiche mute, e su RaiPlay: Dal libro al film, Cinema ragazzi, 25 anni Rai Cinema, film in esclusiva, Stanlio e Ollio e i Grandi Classici. Scroll infinito, niente horror, niente serie TV, solo film per la famiglia."
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
