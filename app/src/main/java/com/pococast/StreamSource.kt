package com.pococast

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Thrown when the user cancels a download or a conversion. */
class CancelledException : IOException("cancelled by the user")

/**
 * What a stream URL turned out to be, decided from its headers and its path.
 *
 * P2P streams matter here: Nuvio serves a torrent it has already opened on the
 * phone as a plain http URL, but that URL usually points at a Matroska file,
 * which the Apple TV's AirPlay player will not take as it is.
 */
data class StreamInfo(
    val contentType: String?,
    val size: Long,
    val container: Container
) {

    enum class Container { MP4, HLS, MKV, DASH, OTHER }

    /** A short name for the status line, such as "MKV · 1.42 GB". */
    fun describe(): String {
        val name = when (container) {
            Container.MP4 -> "MP4"
            Container.HLS -> "HLS"
            Container.MKV -> "MKV"
            Container.DASH -> "DASH"
            Container.OTHER -> "video"
        }
        return if (size > 0L) name + " · " + StreamSource.formatSize(size) else name
    }
}

/**
 * Asks a stream what it is and copies it down to a file, so it can be
 * repackaged for the Apple TV. Nothing here goes through a P2P library: the
 * torrent itself is Nuvio's business, and PocoCast only ever reads the URL
 * Nuvio is already serving.
 */
object StreamSource {

    const val USER_AGENT = "PocoCast/1.0"

    fun probe(url: String, headers: Map<String, String> = emptyMap()): StreamInfo {
        var connection: HttpURLConnection? = null
        try {
            val parsed = URL(url)
            val opened = parsed.openConnection() as HttpURLConnection
            connection = opened
            opened.connectTimeout = 8000
            opened.readTimeout = 8000
            opened.instanceFollowRedirects = true
            opened.requestMethod = "GET"
            // One byte is enough to learn the type and the whole size.
            opened.setRequestProperty("Range", "bytes=0-0")
            opened.setRequestProperty("User-Agent", USER_AGENT)
            for ((name, value) in headers) opened.setRequestProperty(name, value)

            val code = opened.responseCode
            if (code < 200 || code > 299) {
                throw IOException("the stream answered HTTP $code")
            }
            val type = opened.contentType
            return StreamInfo(type, totalSize(opened), containerOf(parsed.path, type))
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Copies [url] to [dest], reporting (written, total) as it goes. [total] is
     * -1 when the source does not say how big it is.
     */
    fun download(
        url: String,
        dest: File,
        cancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
        headers: Map<String, String> = emptyMap()
    ): File {
        var connection: HttpURLConnection? = null
        try {
            val opened = URL(url).openConnection() as HttpURLConnection
            connection = opened
            opened.connectTimeout = 15000
            opened.readTimeout = 30000
            opened.instanceFollowRedirects = true
            opened.requestMethod = "GET"
            opened.setRequestProperty("User-Agent", USER_AGENT)
            for ((name, value) in headers) opened.setRequestProperty(name, value)

            val code = opened.responseCode
            if (code < 200 || code > 299) {
                throw IOException("the stream answered HTTP $code")
            }
            val total = totalSize(opened)
            val input = opened.inputStream
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(256 * 1024)
                var written = 0L
                var reported = 0L
                while (true) {
                    if (cancelled()) throw CancelledException()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    if (written - reported >= 4L * 1024 * 1024 || (total > 0L && written >= total)) {
                        reported = written
                        onProgress(written, total)
                    }
                }
                output.flush()
                onProgress(written, total)
            }
            return dest
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    internal fun containerOf(path: String, contentType: String?): StreamInfo.Container {
        val type = (contentType ?: "").lowercase(Locale.US)
        val file = path.lowercase(Locale.US)
        return when {
            type.contains("mpegurl") || file.endsWith(".m3u8") -> StreamInfo.Container.HLS
            type.contains("dash") || file.endsWith(".mpd") -> StreamInfo.Container.DASH
            type.contains("matroska") || file.endsWith(".mkv") || file.endsWith(".mka") ->
                StreamInfo.Container.MKV
            type.startsWith("video/mp4") || type.startsWith("video/quicktime") ||
                type.contains("x-m4v") || file.endsWith(".mp4") || file.endsWith(".m4v") ||
                file.endsWith(".mov") -> StreamInfo.Container.MP4
            else -> StreamInfo.Container.OTHER
        }
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "size unknown"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1000.0) {
            String.format(Locale.US, "%.0f MB", mb)
        } else {
            String.format(Locale.US, "%.2f GB", mb / 1024.0)
        }
    }

    /**
     * The whole size of the resource, from `Content-Range` when the server
     * honoured the one-byte range request, otherwise from `Content-Length`.
     */
    private fun totalSize(connection: HttpURLConnection): Long {
        val range = connection.getHeaderField("Content-Range")
        if (range != null) {
            val total = range.substringAfterLast('/', "").trim().toLongOrNull()
            if (total != null && total > 0L) return total
        }
        val length = connection.getHeaderField("Content-Length")?.trim()?.toLongOrNull()
        if (length != null && length > 0L && connection.responseCode == 200) {
            return length
        }
        return -1L
    }
}
