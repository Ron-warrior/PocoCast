package com.pococast

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList

/** A resolved YouTube video: either one playable URL, or separate tracks to join. */
data class YouTubeMedia(
    val title: String,
    val directUrl: String? = null,
    val videoUrl: String? = null,
    val audioUrl: String? = null
)

/**
 * Resolves a YouTube link with NewPipeExtractor.
 *
 * NewPipe is the maintained extractor: it knows the current signature and
 * throttling schemes, so it is the part of this app that has to keep up with
 * YouTube. Preferred outcome is the HLS manifest (1080p, no work on the phone);
 * failing that, a 1080p H.264 track plus an AAC track, joined by [Remuxer].
 */
object YouTube {

    private val LINK = Regex(
        "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^\\s]*&)?v=|shorts/|embed/|live/|v/))" +
            "([A-Za-z0-9_-]{11})"
    )

    private val initialised = java.util.concurrent.atomic.AtomicBoolean(false)

    fun videoIdIn(text: String): String? = LINK.find(text)?.groupValues?.get(1)

    fun resolve(videoId: String, say: (String) -> Unit): YouTubeMedia {
        if (initialised.compareAndSet(false, true)) {
            NewPipe.init(NpeDownloader())
        }
        say("Asking YouTube for the stream…")
        Diag.log("youtube: resolving " + videoId)
        val extractor = ServiceList.YouTube
            .getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
        extractor.fetchPage()
        val title = runCatching { extractor.name }.getOrNull().orEmpty().ifEmpty { "YouTube video" }

        val hls = runCatching { extractor.hlsUrl }.getOrNull()?.takeIf { it.isNotBlank() }
        Diag.log("youtube: title=" + title + " hls=" + (hls != null))
        if (hls != null) return YouTubeMedia(title, directUrl = hls)

        val videos = runCatching { extractor.videoOnlyStreams }.getOrNull().orEmpty()
            .filter { it.format == MediaFormat.MPEG_4 }
            .filter { it.codec == null || it.codec.contains("avc", ignoreCase = true) }
        val audios = runCatching { extractor.audioStreams }.getOrNull().orEmpty()
            .filter { it.format == MediaFormat.MPEG_4 }
            .filter { it.codec == null || it.codec.contains("mp4a", ignoreCase = true) }

        val video = videos.maxByOrNull { heightOf(it.resolution) }
        val audio = audios.maxByOrNull { it.averageBitrate }
        if (video != null && audio != null) {
            Diag.log("youtube: tracks video=" + heightOf(video.resolution) + "p audio=" +
                audio.averageBitrate + "kbps")
            say("YouTube: ${heightOf(video.resolution)}p, joining the tracks on the phone")
            return YouTubeMedia(title, videoUrl = video.url, audioUrl = audio.url)
        }

        val muxed = runCatching { extractor.videoStreams }.getOrNull().orEmpty()
            .filter { it.format == MediaFormat.MPEG_4 }
            .maxByOrNull { heightOf(it.resolution) }
        if (muxed != null) return YouTubeMedia(title, directUrl = muxed.url)

        Diag.log("youtube: no usable stream (videoOnly=" + videos.size + " audio=" + audios.size + ")")
        throw IllegalStateException("YouTube offered no H.264/AAC stream for this video")
    }

    private fun heightOf(resolution: String?): Int =
        resolution?.substringBefore('p')?.toIntOrNull() ?: 0
}
