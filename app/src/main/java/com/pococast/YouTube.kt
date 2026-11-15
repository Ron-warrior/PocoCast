package com.pococast

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A playable pair for one YouTube video, and how to describe it on screen. */
data class YouTubeChoice(
    val title: String,
    val label: String,
    val videoUrl: String,
    val audioUrl: String?
)

/**
 * Turns a YouTube link into direct streams.
 *
 * This is the only route by which an advertisement-free YouTube video reaches
 * the Apple TV, and it has nothing to do with capturing the screen: the player
 * page is never loaded, so there is nothing to advertise on. A public Piped or
 * Invidious front-end resolves the video id to the media URLs YouTube's own
 * player would have fetched, and the Apple TV plays those.
 *
 * Those front-ends are run by volunteers: they come and go, some refuse
 * datacentre or foreign addresses, and one that answered yesterday can be down
 * today. So every instance in [INSTANCES] is tried in turn, the first that
 * offers a playable stream wins, and the failure is reported rather than
 * silently ignored. To use your own, put its address first in the list.
 */
object YouTube {

    private const val INSTANCES_LINE = "https://pipedapi.kavin.rocks https://pipedapi.adminforge.de " +
        "https://api.piped.private.coffee https://inv.nadeko.net https://yewtu.be"

    private val INSTANCES: List<String> = INSTANCES_LINE.split(" ").filter { it.isNotBlank() }

    /** itags YouTube uses for H.264 video and AAC audio, which an MP4 can carry. */
    private val H264_ITAGS = setOf(133, 134, 135, 136, 137, 160, 264, 266, 298, 299)

    private val LINK = Regex(
        "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^\\s]*&)?v=|shorts/|embed/|live/|v/))" +
            "([A-Za-z0-9_-]{11})"
    )

    private val ITAG = Regex("[?&]itag=(\\d+)")
    private val DIGITS = Regex("\\d+")

    fun videoIdIn(text: String): String? = LINK.find(text)?.groupValues?.get(1)

    fun resolve(
        videoId: String,
        cancelled: () -> Boolean,
        onStatus: (String) -> Unit
    ): YouTubeChoice {
        var lastError: Throwable? = null
        for (instance in INSTANCES) {
            if (cancelled()) throw CancelledException()
            val host = hostOf(instance)
            onStatus("Asking $host for a direct stream…")
            try {
                val choice = choiceFrom(fetchJson(endpointOf(instance, videoId)))
                if (choice != null) return choice
                lastError = IOException("$host had no playable stream")
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IOException(
            "no YouTube front-end answered (" + (lastError?.message ?: "no error given") +
                "). Open the same video on an Invidious or Piped site in Brave and cast its stream " +
                "link instead, or put a working instance at the front of the list in YouTube.kt"
        )
    }

    /**
     * Picks what to play. A 1080p H.264 video track paired with an AAC audio
     * track gives the best picture and is joined into one MP4 on the phone; a
     * single muxed file is the fallback, and the only option when the front-end
     * offers nothing else.
     */
    private fun choiceFrom(root: JSONObject): YouTubeChoice? {
        val title = root.optString("title", "YouTube video")
        val muxed = ArrayList<Candidate>()
        val videoOnly = ArrayList<Candidate>()
        val audio = ArrayList<Candidate>()

        val piped = root.optJSONArray("videoStreams")
        if (piped != null) {
            for (index in 0 until piped.length()) {
                val item = piped.optJSONObject(index) ?: continue
                val url = item.optString("url", "")
                if (url.isEmpty()) continue
                val type = item.optString("mimeType", "")
                if (type.isNotEmpty() && !type.startsWith("video/mp4")) continue
                val candidate = Candidate(url, heightOf(item.optString("quality", "")))
                if (item.optBoolean("videoOnly", false)) {
                    if (!allowedVideoItag(url)) continue
                    videoOnly.add(candidate)
                } else {
                    muxed.add(candidate)
                }
            }
            val streams = root.optJSONArray("audioStreams") ?: JSONArray()
            for (index in 0 until streams.length()) {
                val item = streams.optJSONObject(index) ?: continue
                val url = item.optString("url", "")
                if (url.isEmpty()) continue
                val type = item.optString("mimeType", "")
                if (type.isNotEmpty() && !type.startsWith("audio/mp4")) continue
                audio.add(Candidate(url, item.optInt("bitrate", 0)))
            }
        }

        val formats = root.optJSONArray("formatStreams")
        if (formats != null) {
            for (index in 0 until formats.length()) {
                val item = formats.optJSONObject(index) ?: continue
                val url = item.optString("url", "")
                if (url.isEmpty()) continue
                val type = item.optString("type", item.optString("mimeType", ""))
                if (!type.startsWith("video/mp4")) continue
                if (!type.contains("mp4a")) continue
                muxed.add(
                    Candidate(
                        url,
                        heightOf(item.optString("qualityLabel", item.optString("quality", "")))
                    )
                )
            }
        }

        val adaptive = root.optJSONArray("adaptiveFormats")
        if (adaptive != null) {
            for (index in 0 until adaptive.length()) {
                val item = adaptive.optJSONObject(index) ?: continue
                val url = item.optString("url", "")
                if (url.isEmpty()) continue
                val type = item.optString("type", item.optString("mimeType", ""))
                if (type.startsWith("video/mp4")) {
                    if (!allowedVideoItag(url)) continue
                    videoOnly.add(
                        Candidate(
                            url,
                            heightOf(item.optString("qualityLabel", item.optString("quality", "")))
                        )
                    )
                } else if (type.startsWith("audio/mp4")) {
                    audio.add(Candidate(url, item.optInt("bitrate", 0)))
                }
            }
        }

        val bestMuxed = muxed.maxByOrNull { it.score }
        val bestVideo = videoOnly.maxByOrNull { it.score }
        val bestAudio = audio.maxByOrNull { it.score }

        return when {
            bestVideo != null && bestAudio != null && bestVideo.score >= (bestMuxed?.score ?: 0) ->
                YouTubeChoice(title, labelFor(bestVideo.score), bestVideo.url, bestAudio.url)

            bestMuxed != null -> YouTubeChoice(title, labelFor(bestMuxed.score), bestMuxed.url, null)

            bestVideo != null && bestAudio != null ->
                YouTubeChoice(title, labelFor(bestVideo.score), bestVideo.url, bestAudio.url)

            else -> null
        }
    }

    /**
     * A video URL without an itag is let through; one with an itag has to be
     * H.264, because a VP9 or AV1 track cannot go into the MP4 the Apple TV
     * needs and would fail after a long download instead of now.
     */
    private fun allowedVideoItag(url: String): Boolean {
        val itag = itagOf(url) ?: return true
        return H264_ITAGS.contains(itag)
    }

    private fun itagOf(url: String): Int? = ITAG.find(url)?.groupValues?.get(1)?.toIntOrNull()

    private fun heightOf(label: String): Int = DIGITS.find(label)?.value?.toIntOrNull() ?: 0

    private fun labelFor(height: Int): String = if (height > 0) "${height}p" else "video"

    private fun endpointOf(instance: String, videoId: String): String =
        if (hostOf(instance).contains("piped")) {
            "$instance/streams/$videoId"
        } else {
            "$instance/api/v1/videos/$videoId"
        }

    private fun hostOf(instance: String): String =
        instance.removePrefix("https://").removePrefix("http://").substringBefore('/')

    private fun fetchJson(url: String): JSONObject {
        var connection: HttpURLConnection? = null
        try {
            val opened = URL(url).openConnection() as HttpURLConnection
            connection = opened
            opened.connectTimeout = 8000
            opened.readTimeout = 15000
            opened.instanceFollowRedirects = true
            opened.requestMethod = "GET"
            opened.setRequestProperty("User-Agent", StreamSource.USER_AGENT)
            opened.setRequestProperty("Accept", "application/json")
            val code = opened.responseCode
            if (code < 200 || code > 299) throw IOException("HTTP $code")
            val text = opened.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    /** A stream URL with the number used to rank it: pixels for video, bits for audio. */
    private class Candidate(val url: String, val score: Int)
}
