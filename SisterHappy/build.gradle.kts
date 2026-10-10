// SisterHappy - una serie, tutta in italiano, pronta da guardare
version = 8

cloudstream {
    description = "Una sola serie TV, tutta in italiano: la stagione più recente in cima alla lista, con episodi nuovi che appaiono da soli. I link MaxStream, DeltaBit e MixDrop vengono aperti in automatico: il plugin risolve i protettori di link con richieste leggere, gestendo cookie e redirect come un browser, e ricava il video direttamente. Semplice da usare: apri, scegli l'episodio, guarda."
    authors = listOf("antonydp")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1
    tvTypes = listOf(
        "TvSeries"
    )

    // false: il bypass Cloudflare (CloudflareKiller) e autonomo nell'app e NON
    // richiede risorse del plugin
    requiresResources = false
    language = "it"

    iconUrl = "https://raw.githubusercontent.com/antonydp/retrocinema/main/sisterhappy_logo.png"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
