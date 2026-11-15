package com.pococast.airplay

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID

/**
 * A very small AirPlay "video sender" speaking to an Apple TV on port 7000.
 *
 * The Apple TV plays the URL we hand it. The requests mimic what other open
 * source senders do: an RTSP style POST /play with a binary plist body, and a
 * fallback to the older plain HTTP variant with the URL in a header.
 */
class AirPlaySession(val address: String, val port: Int = 7000) {

    data class Response(
        val code: Int,
        val statusText: String,
        val headers: Map<String, String>
    )

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var cseq = 0
    private var sessionHeader: String? = null
    private val clientSessionId: String = UUID.randomUUID().toString()

    fun info(): String = "$address:$port (session ${clientSessionId.take(8)})"

    @Synchronized
    fun connect() {
        close()
        val newSocket = Socket()
        newSocket.tcpNoDelay = true
        newSocket.connect(InetSocketAddress(address, port), 5000)
        newSocket.soTimeout = 20000
        socket = newSocket
        input = BufferedInputStream(newSocket.getInputStream())
        output = newSocket.getOutputStream()
    }

    /** Asks the Apple TV to start playing [url]. */
    @Synchronized
    fun play(url: String): Response {
        val body = BinaryPlist.dict(
            "Content-Location" to url,
            "Start-Position" to 0.0,
            "X-Apple-Session-ID" to clientSessionId
        )
        return request(
            "POST", "/play", body, true,
            "User-Agent" to "MediaControl/1.0",
            "Content-Type" to "application/x-apple-binary-plist",
            "X-Apple-ProtocolVersion" to "1"
        )
    }

    /** Older variant of the play request, in case the plist form is refused. */
    @Synchronized
    fun playLegacy(url: String): Response {
        close()
        return request(
            "POST", "/play", null, false,
            "User-Agent" to "MediaControl/1.0",
            "Content-Type" to "text/parameters",
            "Content-Location" to url,
            "Start-Position" to "0.0",
            "X-Apple-Session-ID" to clientSessionId
        )
    }

    @Synchronized
    fun rate(value: Double): Response =
        request("POST", "/rate?value=" + format(value), null, true)

    @Synchronized
    fun scrub(position: Double): Response =
        request("POST", "/scrub?position=" + format(position), null, true)

    @Synchronized
    fun stop(): Response = request("POST", "/stop", null, true)

    @Synchronized
    fun close() {
        try {
            socket?.close()
        } catch (ignored: IOException) {
        }
        socket = null
        input = null
        output = null
        sessionHeader = null
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

    private fun request(
        method: String,
        path: String,
        body: ByteArray?,
        rtsp: Boolean,
        vararg headers: Pair<String, String>
    ): Response {
        try {
            if (socket == null) connect()
            val out = output ?: throw IOException("not connected")
            cseq += 1

            val builder = StringBuilder()
            builder.append(method).append(' ').append(path).append(' ')
                .append(if (rtsp) "RTSP/1.0" else "HTTP/1.1").append("\r\n")
            builder.append("Host: ").append(address).append(':').append(port).append("\r\n")
            builder.append("CSeq: ").append(cseq).append("\r\n")
            builder.append("X-Apple-Session-ID: ").append(clientSessionId).append("\r\n")
            for ((name, value) in headers) {
                builder.append(name).append(": ").append(value).append("\r\n")
            }
            if (rtsp && path != "/play") {
                val session = sessionHeader
                if (session != null) builder.append("Session: ").append(session).append("\r\n")
            }
            builder.append("Content-Length: ").append(body?.size ?: 0).append("\r\n\r\n")

            out.write(builder.toString().toByteArray(Charsets.UTF_8))
            if (body != null) out.write(body)
            out.flush()

            return readResponse()
        } catch (error: IOException) {
            close()
            throw error
        }
    }

    private fun readResponse(): Response {
        val stream = input ?: throw IOException("not connected")
        val statusLine = readLine(stream) ?: throw IOException("Apple TV closed the connection")
        val parts = statusLine.split(" ", limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Unexpected reply: $statusLine")
        val statusText = parts.getOrNull(2) ?: ""

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

        headers["session"]?.let { sessionHeader = it }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length > 0) {
            var remaining = length
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) break
                remaining -= read
            }
        }

        return Response(code, statusText, headers)
    }

    private fun readLine(stream: BufferedInputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = stream.read()
            if (value < 0) {
                return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            }
            if (value == '\n'.code) break
            if (value == '\r'.code) continue
            buffer.write(value)
        }
        return buffer.toString("UTF-8")
    }
}
