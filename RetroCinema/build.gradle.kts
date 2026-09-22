// Usa un numero intero per la versione: incrementa a ogni release
version = 4

cloudstream {
    description = "Oltre 180 film classici VERIFICATI uno a uno: italiani (Fellini, De Sica, Sordi, Totò), Western, Film Noir, Musical, Comiche e i Grandi Classici su RaiPlay. Scroll infinito, catalogo selezionato a mano, solo film per tutta la famiglia: niente horror, niente serie TV."
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
