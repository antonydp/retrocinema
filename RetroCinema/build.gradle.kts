// Usa un numero intero per la versione: incrementa a ogni release
version = 7

cloudstream {
    description = "TUTTO IN ITALIANO con SCROLL INFINITO ovunque. Nuove uscite ogni giorno (StreamingCommunity), 114+ classici italiani verificati uno a uno (Totò, Sordi, Gassman, Fellini, De Sica), storie d'amore, film con i bambini e dal libro al film (RaiPlay: Cinema ragazzi, Dal libro al film, 25 anni Rai Cinema, film in esclusiva), Stanlio e Ollio, commedia all'italiana, melodrammi, comiche. Righe infinite per genere: commedie, drammi, romance, famiglia, avventura, animazione. Ricerca paginata. Niente horror, niente serie TV, solo film per la famiglia."
    authors = listOf("antonydp", "Luna712", "recloudstream", "doGior")
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
