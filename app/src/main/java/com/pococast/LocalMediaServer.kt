package com.pococast

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Serves the selected video over HTTP on the local network so the Apple TV can
 * fetch it. Range requests are supported, which the Apple TV relies on for
 * starting playback quickly and for seeking.
 *
 * It serves three things: a file picked through the content resolver, a file in
 * the app's own storage (a stream converted for the Apple TV), and a relay for
 * a URL the Apple TV cannot reach itself — a P2P engine's 127.0.0.1 stream, for
 * one — which matters because a torrent stream only exists on this phone.
 */
class LocalMediaServer(private val context: Context) {

    private var serverSocket: ServerSocket? = null
    private var workers: ExecutorService? = null

    @Volatile
    private var mediaUri: Uri? = null

    @Volatile
    private var mediaFile: File? = null

    @Volatile
    private var proxyTarget: String? = null

    @Volatile
    private var proxyHeaders: Map<String, String> = emptyMap()

    @Volatile
    private var proxyPlaylist = false

    @Volatile
    private var mediaName: String = "video.mp4"

    @Volatile
    private var mediaMime: String = "video/mp4"

    @Volatile
    private var mediaSize: Long = -1L

    var port: Int = 0
        private set

    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0))
        val pool = Executors.newCachedThreadPool()
        serverSocket = socket
        workers = pool
        port = socket.localPort
        val acceptThread = Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    pool.execute { handle(client) }
                } catch (error: IOException) {
                    break
                } catch (error: RuntimeException) {
                    break
                }
            }
        }, "pococast-http")
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (ignored: IOException) {
        }
        serverSocket = null
        workers?.shutdownNow()
        workers = null
    }

    fun setMedia(uri: Uri, name: String, mimeType: String?) {
        proxyTarget = null
        proxyPlaylist = false
        proxyHeaders = emptyMap()
        mediaFile = null
        mediaUri = uri
        mediaName = name
        mediaMime = mimeType ?: guessMime(name)
        mediaSize = querySize(uri)
    }

    /** Serves a file the app wrote itself, such as a stream converted to MP4. */
    fun setFile(file: File, name: String, mimeType: String?) {
        proxyTarget = null
        proxyPlaylist = false
        proxyHeaders = emptyMap()
        mediaUri = null
        mediaFile = file
        mediaName = name
        mediaMime = mimeType ?: guessMime(name)
        mediaSize = file.length()
    }

    /**
     * Relays [url] through the phone. Needed for a stream that exists only in
     * another app on this phone (a P2P engine on 127.0.0.1), for a stream that
     * only answers when it is asked with particular [headers], and for an HLS
     * playlist, whose segment URLs then have to be rewritten to come back here.
     */
    fun setProxy(
        url: String,
        headers: Map<String, String> = emptyMap(),
        playlist: Boolean = false
    ) {
        mediaUri = null
        mediaFile = null
        proxyTarget = url
        proxyHeaders = headers
        proxyPlaylist = playlist
        mediaName = "stream"
        mediaMime = guessMime(Uri.parse(url).lastPathSegment ?: "")
        mediaSize = -1L
    }

    fun urlFor(host: String): String = when {
        proxyTarget == null ->
            "http://$host:$port/" + Uri.encode(mediaName.ifEmpty { "video.mp4" })
        proxyPlaylist -> "http://$host:$port/hls?u=" +
            URLEncoder.encode(proxyTarget ?: "", "UTF-8")
        else -> "http://$host:$port/stream"
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30000
            val stream = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(stream) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return
            val target = parts.getOrNull(1) ?: return
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(stream) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase(Locale.US)] =
                        line.substring(colon + 1).trim()
                }
            }

            val out = client.getOutputStream()
            if (path == "/" || path == "/index.html" || path == "/status") {
                val body = "PocoCast media server\n".toByteArray(Charsets.UTF_8)
                writeSimple(out, "200 OK", "text/plain; charset=utf-8", body)
                return
            }
            if (method != "GET" && method != "HEAD") {
                writeSimple(out, "405 Method Not Allowed", "text/plain", ByteArray(0))
                return
            }
            if (path == "/hls") {
                serveHls(out, method == "HEAD", query, headers["host"] ?: "")
                return
            }
            if (proxyTarget != null) {
                serveProxy(out, method == "HEAD", headers["range"])
                return
            }
            serveMedia(out, method == "HEAD", headers["range"])
        } catch (ignored: Exception) {
        } finally {
            try {
                client.close()
            } catch (ignored: IOException) {
            }
        }
    }

    private fun serveMedia(out: OutputStream, headOnly: Boolean, rangeHeader: String?) {
        val total = mediaSize
        if (total <= 0L) {
            writeSimple(out, "500 Internal Server Error", "text/plain", "Unknown file size\n".toByteArray())
            return
        }

        var start = 0L
        var end = total - 1
        var partial = false
        var unsatisfiable = false

        if (!rangeHeader.isNullOrBlank()) {
            val spec = rangeHeader.substringAfter("bytes=", "").trim()
            val first = spec.split(',').firstOrNull()?.trim() ?: ""
            val dash = first.indexOf('-')
            if (dash >= 0) {
                val fromText = first.substring(0, dash).trim()
                val toText = first.substring(dash + 1).trim()
                val from = fromText.toLongOrNull()
                val to = toText.toLongOrNull()
                if (from == null && to != null) {
                    if (to > 0L) {
                        start = maxOf(0L, total - to)
                        end = total - 1
                        partial = true
                    } else {
                        unsatisfiable = true
                    }
                } else if (from != null) {
                    start = from
                    end = if (to == null) total - 1 else minOf(to, total - 1)
                    if (start >= total || start > end) unsatisfiable = true else partial = true
                }
            }
        }

        if (unsatisfiable) {
            val header = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                "Content-Range: bytes */" + total + "\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.flush()
            return
        }

        val length = end - start + 1
        val builder = StringBuilder()
        if (partial) {
            builder.append("HTTP/1.1 206 Partial Content\r\n")
        } else {
            builder.append("HTTP/1.1 200 OK\r\n")
        }
        builder.append("Content-Type: ").append(mediaMime).append("\r\n")
        builder.append("Content-Length: ").append(length).append("\r\n")
        builder.append("Accept-Ranges: bytes\r\n")
        if (partial) {
            builder.append("Content-Range: bytes ")
                .append(start).append('-').append(end).append('/').append(total).append("\r\n")
        }
        builder.append("Connection: close\r\n\r\n")
        out.write(builder.toString().toByteArray(Charsets.UTF_8))
        out.flush()

        if (!headOnly) streamBody(out, start, length)
    }

    private fun streamBody(out: OutputStream, offset: Long, length: Long) {
        val stored = mediaFile
        if (stored != null) {
            try {
                FileInputStream(stored).use { input ->
                    seek(input, offset)
                    copyBytes(input, out, length)
                }
            } catch (ignored: IOException) {
            }
            return
        }

        val uri = mediaUri ?: return
        var descriptor: ParcelFileDescriptor? = null
        var stream: FileInputStream? = null
        try {
            val opened = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("cannot open the selected file")
            descriptor = opened
            val file = FileInputStream(opened.fileDescriptor)
            stream = file
            seek(file, offset)
            copyBytes(file, out, length)
        } catch (ignored: IOException) {
        } finally {
            try {
                stream?.close()
            } catch (ignored: IOException) {
            }
            try {
                descriptor?.close()
            } catch (ignored: IOException) {
            }
        }
    }

    private fun seek(stream: FileInputStream, offset: Long) {
        if (offset <= 0L) return
        val positioned = try {
            stream.channel.position(offset)
            true
        } catch (error: IOException) {
            false
        }
        if (!positioned) skipFully(stream, offset)
    }

    /**
     * Relays the URL set by [setProxy] to the Apple TV. The phone is the only
     * device that can reach a stream another app keeps on 127.0.0.1, so the
     * bytes pass through here; ranges are passed on when the source honours
     * them and cut out here when it does not.
     */
    private fun serveProxy(out: OutputStream, headOnly: Boolean, rangeHeader: String?) {
        val target = proxyTarget
        if (target == null) {
            writeSimple(out, "404 Not Found", "text/plain", "No stream is set\n".toByteArray())
            return
        }

        var connection: HttpURLConnection? = null
        try {
            val opened = URL(target).openConnection() as HttpURLConnection
            connection = opened
            opened.connectTimeout = 10000
            opened.readTimeout = 30000
            opened.instanceFollowRedirects = true
            opened.requestMethod = "GET"
            opened.setRequestProperty("User-Agent", "PocoCast/1.0")
            if (!rangeHeader.isNullOrBlank()) opened.setRequestProperty("Range", rangeHeader)
            for ((name, value) in proxyHeaders) opened.setRequestProperty(name, value)

            val code = opened.responseCode
            if (code < 200 || code > 299) {
                writeSimple(
                    out, "502 Bad Gateway", "text/plain",
                    "The stream answered HTTP $code. Is the app that serves it still running?\n".toByteArray()
                )
                return
            }

            val type = opened.contentType ?: mediaMime
            val upstreamRange = opened.getHeaderField("Content-Range")
            val upstreamLength = opened.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            val media = opened.inputStream

            if (code == 206 && upstreamRange != null) {
                // The source honoured the range: hand its answer straight on.
                val header = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Type: $type\r\n" +
                    "Content-Range: $upstreamRange\r\n" +
                    (if (upstreamLength >= 0L) "Content-Length: $upstreamLength\r\n" else "") +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.flush()
                if (!headOnly) copyBytes(media, out, upstreamLength)
                return
            }

            val start = startOf(rangeHeader)

            if (upstreamLength < 0L) {
                // No length to work with: stream what there is.
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $type\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.flush()
                if (!headOnly) copyBytes(media, out, -1L)
                return
            }

            val total = upstreamLength
            if (start >= total) {
                val header = "HTTP/1.1 416 Range Not Satisfiable\r\n" +
                    "Content-Range: bytes */$total\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.flush()
                return
            }

            if (start == 0L) {
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $type\r\n" +
                    "Content-Length: $total\r\n" +
                    "Accept-Ranges: bytes\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.flush()
                if (!headOnly) copyBytes(media, out, total)
                return
            }

            // The source ignored the range, so the leading bytes are dropped
            // here and the rest is served as the partial response asked for.
            val end = total - 1
            val length = end - start + 1
            val header = "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Range: bytes $start-$end/$total\r\n" +
                "Content-Length: $length\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.flush()
            if (!headOnly) {
                skipFully(media, start)
                copyBytes(media, out, length)
            }
        } catch (ignored: IOException) {
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    /** The lower bound of a `Range: bytes=a-b` header, or 0. */
    private fun startOf(rangeHeader: String?): Long {
        if (rangeHeader.isNullOrBlank()) return 0L
        val spec = rangeHeader.substringAfter("bytes=", "").substringBefore(',').trim()
        return spec.substringBefore('-').trim().toLongOrNull() ?: 0L
    }

    /** Copies [length] bytes, or everything left when [length] is negative. */
    private fun copyBytes(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining != 0L) {
            val want = if (remaining < 0L) buffer.size else minOf(buffer.size.toLong(), remaining).toInt()
            val read = try {
                input.read(buffer, 0, want)
            } catch (error: IOException) {
                break
            }
            if (read <= 0) break
            try {
                out.write(buffer, 0, read)
                out.flush()
            } catch (error: IOException) {
                break
            }
            if (remaining > 0L) remaining -= read
        }
    }

    private fun skipFully(stream: InputStream, amount: Long) {
        var remaining = amount
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() < 0) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun writeSimple(out: OutputStream, status: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun readLine(stream: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val value = stream.read()
            if (value < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (value == '\n'.code) return buffer.toString()
            if (value != '\r'.code) buffer.append(value.toChar())
        }
    }

    private fun querySize(uri: Uri): Long {
        try {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            if (descriptor != null) {
                try {
                    val size = descriptor.statSize
                    if (size > 0L) return size
                } finally {
                    descriptor.close()
                }
            }
        } catch (ignored: Exception) {
        }
        try {
            val cursor = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null
            )
            if (cursor != null) {
                cursor.use {
                    if (it.moveToFirst() && !it.isNull(0)) return it.getLong(0)
                }
            }
        } catch (ignored: Exception) {
        }
        return -1L
    }

    /**
     * Relays an HLS playlist and everything it points at.
     *
     * Every URI in the playlist — segments, nested playlists, encryption keys —
     * is rewritten to come back through this server, so a playlist that only
     * exists on the phone, or whose segments are signed for this device, still
     * plays on the Apple TV.
     */
    private fun serveHls(out: OutputStream, headOnly: Boolean, query: String, host: String) {
        val target = queryParameter(query, "u")
        if (target == null) {
            writeSimple(out, "400 Bad Request", "text/plain", "No playlist was named\n".toByteArray())
            return
        }

        var connection: HttpURLConnection? = null
        try {
            val opened = openStream(target)
            connection = opened
            val code = opened.responseCode
            if (code < 200 || code > 299) {
                writeSimple(
                    out, "502 Bad Gateway", "text/plain",
                    "The playlist answered HTTP $code\n".toByteArray()
                )
                return
            }

            val type = opened.contentType ?: ""
            val isPlaylist = target.substringBefore('?').lowercase(Locale.US).endsWith(".m3u8") ||
                type.contains("mpegurl")

            if (!isPlaylist) {
                // A segment or a key file: pass it through untouched.
                val length = opened.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + (if (type.isEmpty()) "application/octet-stream" else type) + "\r\n" +
                    (if (length >= 0L) "Content-Length: $length\r\n" else "") +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.flush()
                if (!headOnly) copyBytes(opened.inputStream, out, length)
                return
            }

            val text = opened.inputStream.bufferedReader().use { it.readText() }
            val body = rewritePlaylist(text, target, host).toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: " + body.size + "\r\n" +
                "Connection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.flush()
            if (!headOnly) {
                out.write(body)
                out.flush()
            }
        } catch (ignored: IOException) {
        } finally {
            try {
                connection?.disconnect()
            } catch (ignored: Exception) {
            }
        }
    }

    /** Points every URI in a playlist back at this server. */
    private fun rewritePlaylist(text: String, target: String, host: String): String {
        val base = URL(target)
        val builder = StringBuilder()
        for (line in text.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> builder.append(line).append('\n')
                trimmed.startsWith("#") -> {
                    val rewritten = if (trimmed.contains("URI=\"")) {
                        Regex("URI=\"([^\"]+)\"").replace(line) { match ->
                            val value = match.groupValues[1]
                            "URI=\"" + localUrlFor(absoluteUrl(base, value), host) + "\""
                        }
                    } else {
                        line
                    }
                    builder.append(rewritten).append('\n')
                }
                else -> builder.append(localUrlFor(absoluteUrl(base, trimmed), host)).append('\n')
            }
        }
        return builder.toString()
    }

    private fun absoluteUrl(base: URL, value: String): String =
        try {
            URL(base, value).toString()
        } catch (error: Exception) {
            value
        }

    private fun localUrlFor(absolute: String, host: String): String {
        val authority = if (host.isBlank()) "127.0.0.1:$port" else host
        return "http://$authority/hls?u=" + URLEncoder.encode(absolute, "UTF-8")
    }

    private fun queryParameter(query: String, name: String): String? {
        if (query.isBlank()) return null
        for (pair in query.split('&')) {
            if (pair.substringBefore('=') == name) {
                return try {
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
                } catch (error: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun openStream(target: String): HttpURLConnection {
        val opened = URL(target).openConnection() as HttpURLConnection
        opened.connectTimeout = 10000
        opened.readTimeout = 20000
        opened.instanceFollowRedirects = true
        opened.requestMethod = "GET"
        opened.setRequestProperty("User-Agent", "PocoCast/1.0")
        for ((name, value) in proxyHeaders) opened.setRequestProperty(name, value)
        return opened
    }

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            else -> "video/mp4"
        }
}
