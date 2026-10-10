// SisterHappy - una serie, tutta in italiano, pronta da guardare
version = 1

cloudstream {
    description = "Una sola serie TV, tutta in italiano: tutte le stagioni con i loro episodi, aggiornata quando escono puntate nuove. Link MaxStream, DeltaBit e MixDrop aperti in automatico anche dietro i protettori di link, con riprova automatica e scambio di dominio al volo. Semplice da usare: apri, scegli l'episodio, guarda. La home mostra subito la scheda della serie."
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
