package it.retrocinema.sisterhappy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SisterHappyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SisterHappy())
        registerExtractorAPI(MaxStreamExtractor())
        registerExtractorAPI(DeltaBitExtractor())
        registerExtractorAPI(MixDropExtractor())
    }
}
