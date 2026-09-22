// Usa un numero intero per la versione: incrementa a ogni release
version = 3

cloudstream {
    description = "UN SOLO plugin con tutto il cinema di una volta: classici italiani (Totò, Sordi, Gassman), Grandi Classici di Hollywood e Stanlio e Ollio su RaiPlay, Musical, Western e Film Noir da Internet Archive. Catalogo selezionato a mano, solo film per tutta la famiglia: niente horror, niente serie TV."
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
