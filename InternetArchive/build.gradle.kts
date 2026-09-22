// Usa un numero intero per la versione: incrementa a ogni release
version = 1

cloudstream {
    description = "Migliaia di film classici e d'epoca dal dominio pubblico (Internet Archive): cinema muto, commedie, musical, western e film noir. Righe curate per decennio e genere, ordinate per popolarità."
    authors = listOf("antonydp", "Luna712")
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
