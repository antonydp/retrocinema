package com.retrocinema.archive

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class InternetArchivePlugin : Plugin() {
    override fun load(context: Context) {
        // Tutti i provider vanno registrati in questo modo
        registerMainAPI(InternetArchiveProvider())
    }
}
