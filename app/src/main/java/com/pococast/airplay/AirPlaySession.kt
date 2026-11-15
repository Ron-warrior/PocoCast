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
 * The Apple TV plays the URL we hand it. Before it will, the connection has to
 * complete HomeKit transient pairing — without it a current tvOS answers 403 to
 * every play request — so [pair] runs first. The requests themselves mimic what
 * other open source senders do: an RTSP style POST /play with a binary plist
 * body, and a fallback to the older plain HTTP variant with the URL in a header.
 */
class AirPlaySession(val address: String, val port: Int = 7000) {

    data class Response(
        val code: Int,
        val statusText: String,
        val headers: Map<String, String>,
        val body: ByteArray = ByteArray(0)
    )

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: OutputStream? = null
    private var cseq = 0
    private var sessionHeader: String? = null
    private val clientSessionId: String = UUID.randomUUID().toString()

    /** Whether the pairing exchange has already succeeded on this connection. */
    var isPaired: Boolean = false
        private set

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
        // A fresh connection is an unpaired one, and the TV would answer 403
        // again, so the pairing exchange is repeated before the retry.
        try {
            pair()
        } catch (ignored: Throwable) {
        }
        return request(
            "POST", "/play", null, false,
            "User-Agent" to "MediaControl/1.0",
            "Content-Type" to "text/parameters",
            "Content-Location" to url,
            "Start-Position" to "0.0",
            "X-Apple-Session-ID" to clientSessionId
        )
    }

    /**
     * Performs HomeKit transient pairing on this connection.
     *
     * AirPlay's transient form uses a fixed PIN, so this needs nothing from the
     * user: the Apple TV publishes a salt and its SRP public key, both sides
     * derive the same secret, and the device accepts `/play` afterwards. If the
     * Apple TV's access control is set to require a password, the exchange is
     * refused instead and the reply carries an error.
     */
    @Synchronized
    fun pair(): Response {
        post("/pair-pin-start", ByteArray(0))

        val opening = HapTlv.decode(
            post(
                "/pair-setup",
                HapTlv.encode(
                    HapTlv.METHOD to byteArrayOf(0x00),
                    HapTlv.SEQ_NO to byteArrayOf(0x01),
                    HapTlv.FLAGS to byteArrayOf(0x10)
                )
            ).body
        )
        val salt = opening[HapTlv.SALT]
            ?: throw IOException("the Apple TV did not offer a pairing salt")
        val serverKey = opening[HapTlv.PUBLIC_KEY]
            ?: throw IOException("the Apple TV did not offer a pairing key")

        val srp = Srp.client(TRANSIENT_PIN, salt, serverKey)

        val finished = post(
            "/pair-setup",
            HapTlv.encode(
                HapTlv.SEQ_NO to byteArrayOf(0x03),
                HapTlv.PUBLIC_KEY to srp.clientPublic,
                HapTlv.PROOF to srp.proof
            )
        )

        // The TV can answer 200 while carrying a TLV error, which would leave
        // the next play request refused with no explanation.
        val failure = HapTlv.decode(finished.body)[HapTlv.ERROR]
        if (failure != null && failure.isNotEmpty()) {
            isPaired = false
            throw IOException(
                "the Apple TV rejected the pairing proof (error " +
                    (failure[0].toInt() and 0xFF) + ")"
            )
        }

        isPaired = finished.code == 200
        return finished
    }

    /** A pairing step, which is plain HTTP rather than RTSP. */
    private fun post(path: String, body: ByteArray): Response = request(
        "POST", path, body, false,
        "User-Agent" to "AirPlay/320.20",
        "Connection" to "keep-alive",
        "X-Apple-HKP" to "4",
        "Content-Type" to "application/octet-stream"
    )

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
        isPaired = false
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
        val body = ByteArray(maxOf(0, length))
        if (length > 0) {
            var filled = 0
            while (filled < length) {
                val read = stream.read(body, filled, length - filled)
                if (read <= 0) break
                filled += read
            }
        }

        return Response(code, statusText, headers, body)
    }

    /**
     * Repeats the whole exchange and reports what each step answered, without
     * throwing. Used when a play request is refused, so the reason is on screen
     * instead of guessed at.
     */
    @Synchronized
    fun diagnose(): String {
        val report = StringBuilder()
        close()

        fun record(label: String, block: () -> Response) {
            try {
                val response = block()
                report.append(label).append(": ").append(response.code).append(' ')
                    .append(response.statusText)
                val server = response.headers["server"]
                if (!server.isNullOrBlank()) report.append(" [").append(server).append(']')
                if (response.body.isNotEmpty()) {
                    report.append(" <").append(summarise(response.body)).append('>')
                }
                report.append('\n')
            } catch (error: Throwable) {
                report.append(label).append(": ")
                    .append(error.message ?: error.javaClass.simpleName).append('\n')
            }
        }

        record("/info") {
            request("GET", "/info", null, false, "User-Agent" to "AirPlay/320.20")
        }
        record("/pair-pin-start") {
            post("/pair-pin-start", ByteArray(0))
        }

        var salt: ByteArray? = null
        var serverKey: ByteArray? = null
        record("/pair-setup m1") {
            val response = post(
                "/pair-setup",
                HapTlv.encode(
                    HapTlv.METHOD to byteArrayOf(0x00),
                    HapTlv.SEQ_NO to byteArrayOf(0x01),
                    HapTlv.FLAGS to byteArrayOf(0x10)
                )
            )
            val opened = HapTlv.decode(response.body)
            salt = opened[HapTlv.SALT]
            serverKey = opened[HapTlv.PUBLIC_KEY]
            response
        }

        val saltValue = salt
        val keyValue = serverKey
        if (saltValue == null || keyValue == null) {
            report.append("pairing: the salt or key was missing\n")
        } else {
            val srp = try {
                Srp.client(TRANSIENT_PIN, saltValue, keyValue)
            } catch (error: Throwable) {
                report.append("srp: ").append(error.message ?: "failed").append('\n')
                null
            }
            if (srp != null) {
                record("/pair-setup m3") {
                    post(
                        "/pair-setup",
                        HapTlv.encode(
                            HapTlv.SEQ_NO to byteArrayOf(0x03),
                            HapTlv.PUBLIC_KEY to srp.clientPublic,
                            HapTlv.PROOF to srp.proof
                        )
                    )
                }
            }
        }

        record("/play") {
            play("http://192.0.2.1/nothing.mp4")
        }

        return report.toString().trim()
    }

    /** A one-line description of a reply body: TLV tags, a plist, or plain text. */
    private fun summarise(body: ByteArray): String {
        val looksLikeTlv = body.size >= 2 &&
            (body[0].toInt() and 0xFF) <= 0x20 &&
            (body[1].toInt() and 0xFF) + 2 <= body.size
        if (looksLikeTlv) {
            val entries = HapTlv.decode(body)
            if (entries.isNotEmpty()) {
                return entries.entries.joinToString(", ") { entry ->
                    val name = when (entry.key) {
                        HapTlv.METHOD -> "Method"
                        HapTlv.SALT -> "Salt"
                        HapTlv.PUBLIC_KEY -> "PublicKey"
                        HapTlv.PROOF -> "Proof"
                        HapTlv.SEQ_NO -> "SeqNo"
                        HapTlv.ERROR -> "Error" + ((entry.value.firstOrNull()?.toInt() ?: -1) and 0xFF)
                        HapTlv.FLAGS -> "Flags"
                        else -> "tag" + entry.key
                    }
                    name + ":" + entry.value.size
                }
            }
        }
        val text = body.toString(Charsets.UTF_8)
        if (text.startsWith("bplist")) return "binary plist, " + body.size + " bytes"
        val printable = text.filter { it.isLetterOrDigit() || it in " .,:;/<>-_" }.take(80)
        return if (printable.isNotBlank()) printable else body.size.toString() + " bytes"
    }

    private companion object {
        /** The fixed PIN of AirPlay's transient pairing; nobody types this one. */
        const val TRANSIENT_PIN = "3939"
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
