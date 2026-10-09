package it.retrocinema.streamingcommunity

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

/** Player VixCloud di StreamingCommunity (dietro Cloudflare: bypass con CloudflareKiller). */
class VixCloudExtractor : ExtractorApi() {
    override val mainUrl = "vixcloud.co"
    override val name = "VixCloud"
    override val requiresReferer = false
    val TAG = "VixCloudExtractor"
    private var referer: String? = null
    val h = mutableMapOf(
        "Accept" to "*/*",
        "Connection" to "keep-alive",
        "Cache-Control" to "no-cache",
        "user-agent" to SC_UA,
    )

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
                source = "VixCloud",
                name = "StreamingCommunity - VixCloud",
                url = playlistUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.headers = h
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
        val iframe = app.get(url, headers = h, interceptor = CloudflareKiller()).document
        val scripts = iframe.select("script")
        val script =
            scripts.find { it.data().contains("masterPlaylist") }!!.data().replace("\n", "\t")

        val scriptJson = getSanitisedScript(script)
        return JSONObject(scriptJson)
    }

    private fun getSanitisedScript(script: String): String {
        // Separa gli assegnamenti top-level tipo window.xxx =
        val parts = Regex("""window\.(\w+)\s*=""")
            .split(script)
            .drop(1) // la prima parte e vuota (prima del primo assegnamento)

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
