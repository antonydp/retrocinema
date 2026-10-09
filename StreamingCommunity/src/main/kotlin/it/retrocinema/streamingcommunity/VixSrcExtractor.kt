package it.retrocinema.streamingcommunity

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Fallback VixSrc (stessa libreria, permalink via tmdb_id). */
class VixSrcExtractor : ExtractorApi() {
    override val mainUrl = "vixsrc.to"
    override val name = "VixSrc"
    override val requiresReferer = false
    val TAG = "VixSrcExtractor"
    private var referer: String? = null

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        this.referer = referer
        Log.d(TAG, "REFERER: $referer  URL: $url")
        val playlistUrl = getPlaylistLink(url)
        Log.w(TAG, "FINAL URL: $playlistUrl")

        callback.invoke(
            newExtractorLink(
                source = "VixSrc",
                name = "StreamingCommunity - VixSrc",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: "https://vixsrc.to/"
            }
        )
    }

    private suspend fun getPlaylistLink(url: String): String {
        val script = getScript(url)
        val masterPlaylist = script.getJSONObject("masterPlaylist")
        val masterPlaylistParams = masterPlaylist.getJSONObject("params")
        val token = masterPlaylistParams.getString("token")
        val expires = masterPlaylistParams.getString("expires")
        val playlistUrl = masterPlaylist.getString("url")

        val params = "token=$token&expires=$expires"
        val masterPlaylistUrl = if ("?b" in playlistUrl) {
            "${playlistUrl.replace("?b:1", "?b=1")}&$params"
        } else {
            "$playlistUrl?$params"
        }

        val withFhd = if (script.optBoolean("canPlayFHD", false)) "$masterPlaylistUrl&h=1" else masterPlaylistUrl
        Log.d(TAG, "Master Playlist URL: $withFhd")
        return withFhd
    }

    private suspend fun getScript(url: String): JSONObject {
        val headers = mutableMapOf(
            "Accept" to "*/*",
            "Alt-Used" to url.toHttpUrl().host,
            "Connection" to "keep-alive",
            "Host" to url.toHttpUrl().host,
            "Referer" to (referer ?: "https://vixsrc.to/"),
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "User-Agent" to SC_UA,
        )

        val resp = app.get(url, headers = headers).document
        val scripts = resp.select("script")
        val script =
            scripts.find { it.data().contains("masterPlaylist") }!!.data().replace("\n", "\t")

        val scriptJson = getSanitisedScript(script)
        return JSONObject(scriptJson)
    }

    private fun getSanitisedScript(script: String): String {
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1)

        val keys = Regex("""window\.(\w+)\s*=""")
            .findAll(script)
            .map { it.groupValues[1] }
            .toList()

        val jsonObjects = keys.zip(parts).map { (key, value) ->
            val cleaned = value
                .replace(";", "")
                .replace(Regex("""(\{|\[|,)\s*(\w+)\s*:"""), "$1 \"$2\":")
                .replace(Regex(""",(\s*[}\]])"""), "$1")
                .trim()

            "\"$key\": $cleaned"
        }
        val finalObject =
            "{\n${jsonObjects.joinToString(",\n")}\n}"
                .replace("'", "\"")

        return finalObject
    }
}
