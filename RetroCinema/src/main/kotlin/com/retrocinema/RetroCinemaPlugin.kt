package com.retrocinema

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class RetroCinemaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RetroCinemaProvider())
    }
}
