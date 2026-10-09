// StreamingCommunity (versione RetroCinema) - home personalizzabile e stabile
version = 5

cloudstream {
    description = "StreamingCommunity con la home che puoi SCEGLIERE: tocca il titolo di una riga in home e apri le impostazioni del plugin per riordinare o nascondere le sezioni (preset Standard, Famiglia, Solo film, Solo serie). In cima Top 10 serie e Top 10 film, Tendenze di adesso, Aggiunti di recente, poi tutti i generi in ordine di tendenza e le annate in fondo. TUTTE le righe con scroll infinito, Tendenze e Aggiunti comprese (pagine browse ufficiali del sito): migliaia di titoli. Film e serie in italiano con 1080p FHD. Righe che si caricano sempre: richieste in sequenza e riprova automatica. Basato sul provider di doGior (GPL-3.0), con domini di riserva automatici."
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
