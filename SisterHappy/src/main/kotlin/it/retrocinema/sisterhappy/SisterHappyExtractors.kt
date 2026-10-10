package it.retrocinema.sisterhappy

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

/** User-Agent desktop condiviso dagli estrattori. */
private const val EXT_UA = SH_UA

/**
 * MaxStream: pagina player con script "eval(function(p,a,c,k,e,d)...)" da spacchettare;
 * dentro c'e src:"https://...m3u8". (logica da provider CB01 GPL di DieGon7771,
 * estesa con regex di riserva).
 */
class MaxStreamExtractor : ExtractorApi() {
    override var name = "MaxStream"
    override var mainUrl = "https://maxstream.video/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "Accept" to "*/*",
            "Connection" to "keep-alive",
            "User-Agent" to EXT_UA,
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.5",
            "Cache-Control" to "max-age=0",
            "Upgrade-Insecure-Requests" to "1",
        )
        val response = app.get(url, headers = headers, timeout = 15_000)
        val body = response.body.string()

        val src = unpackSource(body)
        if (src.isNullOrBlank()) {
            Log.d(name, "Nessuna sorgente trovata in $url")
            return
        }
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = src,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer ?: ""
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /** Spacchetta l'evalpacked se presente, poi cerca src:/file:/source:. */
    private fun unpackSource(body: String): String? {
        val candidates = mutableListOf<String>()
        if (body.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching {
                val script = "eval(function(p,a,c,k,e,d)" +
                    body.substringAfter("eval(function(p,a,c,k,e,d)")
                        .substringBefore(")))") + ")))"
                candidates.add(getAndUnpack(script))
            }
        }
        candidates.add(body)
        for (candidate in candidates) {
            Regex("src\\s*:\\s*[\"']([^\"']+)[\"']").find(candidate)
                ?.groupValues?.get(1)?.let { return it }
            Regex("file\\s*:\\s*[\"']([^\"']+)[\"']").find(candidate)
                ?.groupValues?.get(1)?.let { return it }
        }
        return null
    }
}

/**
 * DeltaBit: pagina player (domini deltabbit vari) con sorgente dentro jwplayer
 * o script packed. Estrazione tollerante: unpack -> file:/sources -> <source> -> mp4/m3u8 grezzi.
 */
class DeltaBitExtractor : ExtractorApi() {
    override var name = "DeltaBit"
    override var mainUrl = "https://deltabit.to/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "User-Agent" to EXT_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.5",
            "Referer" to (referer ?: mainUrl),
        )
        val response = app.get(url, headers = headers, timeout = 15_000)
        val body = response.body.string()

        val link = findVideo(body)
        if (link.isNullOrBlank()) {
            Log.d(name, "Nessuna sorgente trovata in $url")
            return
        }
        val isHls = link.substringBefore('?').lowercase().endsWith(".m3u8")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun findVideo(body: String): String? {
        val texts = mutableListOf<String>()
        if (body.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching {
                val script = "eval(function(p,a,c,k,e,d)" +
                    body.substringAfter("eval(function(p,a,c,k,e,d)")
                        .substringBefore(")))") + ")))"
                texts.add(getAndUnpack(script))
            }
        }
        texts.add(body)
        for (text in texts) {
            Regex("file\\s*:\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
            Regex("sources\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
            Regex("<source[^>]+src\\s*=\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
        }
        Regex("https?://[^\"'<>\\s]+\\.(?:m3u8|mp4)[^\"'<>\\s]*").find(body)?.value?.let { return it }
        return null
    }
}

/**
 * MixDrop: pagina /e/<id> con script packed contenente wurl="https://...m3u8".
 * Estrazione via HTTP leggera (niente WebView): unpack + regex di riserva.
 */
class MixDropExtractor : ExtractorApi() {
    override var name = "MixDrop"
    override var mainUrl = "https://mixdrop.top/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        // Le pagine player sono sotto /e/; /f/ e la pagina di download
        val playerUrl = url.replace("/f/", "/e/")
        val headers = mapOf(
            "User-Agent" to EXT_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "it-IT,it;q=0.9,en;q=0.5",
            "Referer" to (referer ?: mainUrl),
        )
        val response = app.get(playerUrl, headers = headers, timeout = 15_000)
        val body = response.body.string()

        val link = findVideo(body)
        if (link.isNullOrBlank()) {
            Log.d(name, "Nessuna sorgente trovata in $playerUrl")
            return
        }
        val isHls = link.substringBefore('?').lowercase().endsWith(".m3u8")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun findVideo(body: String): String? {
        val texts = mutableListOf<String>()
        if (body.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching {
                val script = "eval(function(p,a,c,k,e,d)" +
                    body.substringAfter("eval(function(p,a,c,k,e,d)")
                        .substringBefore(")))") + ")))"
                texts.add(getAndUnpack(script))
            }
        }
        texts.add(body)
        for (text in texts) {
            Regex("wurl\\s*[:=]\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
            Regex("file\\s*:\\s*[\"'](https?://[^\"']+)[\"']").find(text)
                ?.groupValues?.get(1)?.let { return it }
        }
        Regex("https?://[^\"'<>\\s]+\\.(?:m3u8|mp4)[^\"'<>\\s]*").find(body)?.value?.let { return it }
        return null
    }
}
