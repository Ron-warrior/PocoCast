package com.pococast

import android.content.Context
import android.os.PowerManager
import android.util.Base64
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceListener
import com.airsonic.sender.api.DeviceType
import com.airsonic.sender.discovery.AirplayDiscovery
import com.airsonic.sender.discovery.pickLanIpForTarget
import com.airsonic.sender.pairing.PairingHandshake
import com.airsonic.sender.streaming.AirplayVideoController
import com.airsonic.sender.streaming.LocalMediaHttpServer
import com.airsonic.sender.streaming.RangeSource
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom

/**
 * Discovery, pairing and the AirPlay session, in one place. Every step is
 * mirrored into [Diag] so a failed cast can be explained after the fact.
 */
class CastController {

    private var discovery: AirplayDiscovery? = null
    private var video: AirplayVideoController? = null
    private var server: LocalMediaHttpServer? = null
    private var serverPort: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var target: AirDevice? = null
    private var mediaUrl: String? = null

    val devices = mutableListOf<AirDevice>()

    /** Summary for the diagnostic file. */
    fun about(): String = buildString {
        val device = target
        appendLine(
            "selected: " + (device?.let {
                it.name + " " + it.host + ":" + it.port + " type=" + it.type +
                    " caps=" + it.capabilities + " txt=" + it.txtRecords
            } ?: "none")
        )
        appendLine(
            "controller: " + (video?.let {
                "lastStatus=" + it.lastStatus + " lastError=" + it.lastError
            } ?: "none")
        )
        appendLine(
            "media server: " + (server?.let { "port=" + serverPort + " path=" + it.path } ?: "none")
        )
        appendLine("media url: " + (mediaUrl ?: "none"))
        appendLine("discovered: " + devices.joinToString { it.name + "@" + it.host })
    }

    fun startDiscovery(context: Context, onChanged: () -> Unit, onError: (String) -> Unit) {
        val existing = discovery
        if (existing != null) { existing.start(listener(onChanged, onError)); return }
        val found = AirplayDiscovery(context)
        discovery = found
        found.start(listener(onChanged, onError))
    }

    private fun listener(onChanged: () -> Unit, onError: (String) -> Unit) = object : DeviceListener {
        override fun onDeviceFound(device: AirDevice) {
            Diag.log("discovery: found " + device.name + " " + device.host + ":" + device.port)
            devices.removeAll { it.id == device.id }
            devices.add(device)
            onChanged()
        }
        override fun onDeviceLost(device: AirDevice) {
            Diag.log("discovery: lost " + device.name)
            devices.removeAll { it.name == device.name }
            onChanged()
        }
        override fun onDiscoveryFailed(reason: String) {
            Diag.log("discovery: failed " + reason)
            onError(reason)
        }
    }

    fun stopDiscovery() { discovery?.stop(); discovery = null }

    /** Casts [raw] to [target]; [askPin] runs on the caller's thread and blocks for the PIN. */
    fun cast(
        context: Context,
        target: AirDevice,
        raw: String,
        say: (String) -> Unit,
        askPin: () -> String?
    ) {
        this.target = target
        Diag.log("cast: target=" + target.name + " " + target.host + ":" + target.port +
            " type=" + target.type + " caps=" + target.capabilities)
        Diag.log("cast: input=" + raw.take(160))

        keepAwake(context)
        val handshake = pair(context, target, say, askPin)
            ?: throw IOException("pairing with ${target.name} failed")
        Diag.log("pairing: completed, sessionKey=" + (handshake.sessionKey?.size ?: 0) + "B")

        say("Setting up the AirPlay session…")
        val controller = AirplayVideoController(target.host, handshake)
        controller.onEvent = { firstLine, body ->
            Diag.log("event: " + firstLine + " body=" + body.size + "B")
        }
        controller.onConnectionLost = { Diag.log("session: connection lost (feedback keepalive failed)") }
        val connected = controller.connect()
        Diag.log("session: connect=" + connected + " lastStatus=" + controller.lastStatus +
            " lastError=" + controller.lastError)
        if (!connected) {
            throw IOException("the Apple TV refused the session setup (SETUP/RECORD)")
        }
        video = controller

        val playable = prepare(context, target, raw, say)
        mediaUrl = playable
        say("Asking ${target.name} to play…")
        val played = controller.play(playable)
        Diag.log("play: result=" + played + " lastStatus=" + controller.lastStatus +
            " lastError=" + controller.lastError)
        if (!played) {
            throw IOException("the Apple TV refused the play command")
        }
    }

    fun stop() {
        runCatching { video?.stop() }
        runCatching { video?.close() }
        video = null
        server?.stop(); server = null
        serverPort = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        Diag.log("session: stopped")
    }

    // ---- pairing ----

    private fun pair(
        context: Context,
        target: AirDevice,
        say: (String) -> Unit,
        askPin: () -> String?
    ): PairingHandshake? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seed = seedOf(prefs)
        val storedLtpk = prefs.getString("ltpk:${target.host}", null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
        Diag.log("pairing: stored accessory key=" + (storedLtpk != null))

        if (storedLtpk != null) {
            say("Reconnecting to ${target.name}…")
            val known = handshake(target, seed).apply { knownAccessoryLtpk = storedLtpk }
            val verified = known.pairVerify { step ->
                if (step is PairingHandshake.Step.Failure) Diag.log("pairing: verify failed " + step.message)
            }
            Diag.log("pairing: pair-verify with stored key=" + verified +
                " lastPinStart=" + known.lastPinStartStatus)
            if (verified) return known
            prefs.edit().remove("ltpk:${target.host}").apply()
        }

        say("Pairing with ${target.name}…")
        if (!isAppleTv(target)) {
            val open = handshake(target, seed)
            if (open.pairSetup("3939", onStep = {}, transient = true)) {
                Diag.log("pairing: transient pair-setup succeeded")
                return open
            }
            Diag.log("pairing: transient pair-setup refused, trying PIN")
        } else {
            // An Apple TV refuses transient pairing and then holds a short
            // pairing backoff, which made the PIN attempt's M1 fail straight
            // afterwards. Skip straight to the exchange it actually wants.
            Diag.log("pairing: Apple TV, skipping transient and going straight to PIN")
        }

        var pinHs = handshake(target, seed)
        var started = pinHs.pairSetupBegin { }
        var attempt = 0
        while (!started && attempt < PAIRING_RETRIES_MS.size) {
            val waitMs = PAIRING_RETRIES_MS[attempt]
            attempt++
            say("The TV is not ready to pair yet — waiting ${waitMs / 1000}s…")
            Diag.log("pairing: M1 failed, retry #" + attempt + " in " + waitMs + "ms")
            Thread.sleep(waitMs)
            pinHs = handshake(target, seed)
            started = pinHs.pairSetupBegin { }
        }
        Diag.log(
            "pairing: pair-setup begin=" + started + " attempts=" + (attempt + 1) +
                " pinStartStatus=" + pinHs.lastPinStartStatus
        )
        if (!started) {
            throw IOException(
                "the Apple TV refused pair-setup — it may still be backing off; wait a minute and try again"
            )
        }
        say("Look at the TV for the 4-digit PIN")
        val pin = askPin() ?: return null
        Diag.log("pairing: PIN entered (" + pin.length + " digits)")
        if (!pinHs.pairSetupWithPin(pin, { })) {
            Diag.log("pairing: PIN pair-setup failed")
            return null
        }
        pinHs.lastAccessoryLtpk?.let {
            prefs.edit().putString("ltpk:${target.host}", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
        }
        val verified = handshake(target, seed).apply { knownAccessoryLtpk = pinHs.lastAccessoryLtpk }
        val ok = verified.pairVerify { }
        Diag.log("pairing: pair-verify after PIN=" + ok)
        return if (ok) verified else null
    }

    private fun handshake(target: AirDevice, seed: ByteArray) =
        PairingHandshake(target.host, target.port, "PocoCast-Android", seed)

    private fun isAppleTv(device: AirDevice): Boolean =
        device.type == DeviceType.APPLE_TV ||
            device.txtRecords["model"]?.startsWith("AppleTV", ignoreCase = true) == true

    private fun seedOf(prefs: android.content.SharedPreferences): ByteArray {
        prefs.getString("seed", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("seed", Base64.encodeToString(seed, Base64.NO_WRAP)).apply()
        return seed
    }

    // ---- media ----

    private fun prepare(
        context: Context,
        target: AirDevice,
        raw: String,
        say: (String) -> Unit
    ): String {
        YouTube.videoIdIn(raw)?.let { id ->
            val media = YouTube.resolve(id, say)
            media.directUrl?.let {
                Diag.log("media: YouTube gave a direct URL, the TV fetches it itself")
                return it
            }
            say("Downloading ${media.title}…")
            val dir = File(context.cacheDir, "cast").apply { mkdirs() }
            val videoFile = File(dir, "video.mp4").apply { delete() }
            val audioFile = File(dir, "audio.m4a").apply { delete() }
            download(media.videoUrl!!, videoFile, say)
            download(media.audioUrl!!, audioFile, say)
            Diag.log("media: downloaded video=" + videoFile.length() + "B audio=" + audioFile.length() + "B")
            val output = File(dir, "youtube.mp4").apply { delete() }
            val joined = Remuxer.combine(videoFile, audioFile, output, { false }) { percent ->
                say("Joining the tracks… $percent%")
            }
            Diag.log("media: joined=" + joined.file.length() + "B")
            videoFile.delete(); audioFile.delete()
            return serve(joined.file, "video/mp4", target)
        }

        val info = StreamSource.probe(raw)
        Diag.log("media: probe container=" + info.container + " " + info.describe())
        val host = runCatching { URL(raw).host }.getOrNull()
        val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
        if (!loopback && (info.container == StreamInfo.Container.MP4 ||
                info.container == StreamInfo.Container.HLS)) {
            say("The TV can fetch this ${info.describe()} itself")
            Diag.log("media: handing the URL to the TV directly")
            return raw
        }

        say("Downloading ${info.describe()}…")
        val dir = File(context.cacheDir, "cast").apply { mkdirs() }
        val source = File(dir, "source.bin").apply { delete() }
        StreamSource.download(
            url = raw,
            dest = source,
            cancelled = { false },
            onProgress = { done, total ->
                val percent = if (total > 0) " ${done * 100 / total}%" else ""
                say("Downloading ${StreamSource.formatSize(done)}$percent")
            }
        )
        Diag.log("media: downloaded " + source.length() + "B")
        if (info.container == StreamInfo.Container.MP4) {
            return serve(source, "video/mp4", target)
        }

        say("Repackaging for the Apple TV…")
        val output = File(dir, "cast.mp4").apply { delete() }
        val result = Remuxer.remux(source, output, { false }) { percent ->
            say("Repackaging $percent%")
        }
        Diag.log("media: remuxed " + result.file.length() + "B")
        source.delete()
        return serve(result.file, "video/mp4", target)
    }

    private fun download(url: String, dest: File, say: (String) -> Unit) {
        StreamSource.download(
            url = url,
            dest = dest,
            cancelled = { false },
            onProgress = { done, total ->
                val percent = if (total > 0) " ${done * 100 / total}%" else ""
                say("Downloading ${StreamSource.formatSize(done)}$percent")
            }
        )
    }

    private fun serve(file: File, mime: String, target: AirDevice): String {
        val total = file.length()
        val source = object : RangeSource {
            override val length: Long = total
            override val mimeType: String = mime
            override fun open(offset: Long): InputStream {
                Diag.log("media: TV requested offset=" + offset + " of " + total + "B (" + mime + ")")
                val base = FileInputStream(file).apply { channel.position(offset) }
                return object : FilterInputStream(base) {
                    private var served = 0L
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0) served += n
                        return n
                    }
                    override fun close() {
                        Diag.log("media: stream closed after " + served + "B")
                        super.close()
                    }
                }
            }
        }
        val http = LocalMediaHttpServer(source)
        val port = http.start()
        server?.stop()
        server = http
        serverPort = port
        val ip = pickLanIpForTarget(localAddresses(), target.host)
            ?: throw IOException("no LAN address to serve the video from")
        val url = "http://$ip:$port${http.path}"
        Diag.log("media: serving " + total + "B at " + url)
        return url
    }

    private fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    private fun keepAwake(context: Context) {
        if (wakeLock?.isHeld == true) return
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pococast:cast")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
    }

    companion object {
        private const val PREFS = "pococast"
        /** Pairing backoff after a refused attempt; TV clears it within ~30s. */
        private val PAIRING_RETRIES_MS = longArrayOf(5_000L, 15_000L, 30_000L)
    }
}
