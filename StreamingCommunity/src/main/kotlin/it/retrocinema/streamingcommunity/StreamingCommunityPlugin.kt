package it.retrocinema.streamingcommunity

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamingCommunityPlugin : Plugin() {
    override fun load(context: Context) {
        // Context per la configurazione salvata (DataStore)
        StreamingCommunity.appContext = context
        registerMainAPI(StreamingCommunity())

        // Menu impostazioni: si apre dal tasto del provider in home.
        // Da qui l'utente riordina/disattiva le sezioni della homepage.
        openSettings = { ctx ->
            runCatching { showScSectionsDialog(ctx) }
        }
    }
}
