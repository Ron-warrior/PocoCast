package com.pococast

import android.content.Context
import android.os.PowerManager
import android.util.Base64
import com.airsonic.sender.api.AirDevice
import com.airsonic.sender.api.DeviceListener
import com.airsonic.sender.discovery.AirplayDiscovery
import com.airsonic.sender.discovery.pickLanIpForTarget
import com.airsonic.sender.pairing.PairingHandshake
import com.airsonic.sender.streaming.AirplayVideoController
import com.airsonic.sender.streaming.LocalMediaHttpServer
import com.airsonic.sender.streaming.RangeSource
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom

/** Discovery, pairing and the AirPlay session, in one place. */
class CastController {

    private var discovery: AirplayDiscovery? = null
    private var video: AirplayVideoController? = null
    private var server: LocalMediaHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val devices = mutableListOf<AirDevice>()

    fun startDiscovery(context: Context, onChanged: () -> Unit, onError: (String) -> Unit) {
        val existing = discovery
        if (existing != null) { existing.start(listener(onChanged, onError)); return }
        val found = AirplayDiscovery(context)
        discovery = found
        found.start(listener(onChanged, onError))
    }

    private fun listener(onChanged: () -> Unit, onError: (String) -> Unit) = object : DeviceListener {
        override fun onDeviceFound(device: AirDevice) {
            devices.removeAll { it.id == device.id }
            devices.add(device)
            onChanged()
        }
        override fun onDeviceLost(device: AirDevice) {
            devices.removeAll { it.name == device.name }
            onChanged()
        }
        override fun onDiscoveryFailed(reason: String) = onError(reason)
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
        keepAwake(context)
        val handshake = pair(context, target, say, askPin)
            ?: throw IOException("pairing with ${target.name} failed")
        say("Setting up the AirPlay session…")
        val controller = AirplayVideoController(target.host, handshake)
        if (!controller.connect()) {
            throw IOException("the Apple TV refused the session setup (SETUP/RECORD)")
        }
        video = controller

        val playable = prepare(context, target, raw, say)
        say("Asking ${target.name} to play…")
        if (!controller.play(playable)) {
            throw IOException("the Apple TV refused the play command")
        }
    }

    fun stop() {
        runCatching { video?.stop() }
        runCatching { video?.close() }
        video = null
        server?.stop(); server = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
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

        if (storedLtpk != null) {
            say("Reconnecting to ${target.name}…")
            val known = handshake(target, seed).apply { knownAccessoryLtpk = storedLtpk }
            if (known.pairVerify { step -> if (step is PairingHandshake.Step.Failure) say(step.message) }) {
                return known
            }
            prefs.edit().remove("ltpk:${target.host}").apply()
        }

        say("Pairing with ${target.name}…")
        val open = handshake(target, seed)
        if (open.pairSetup("3939", onStep = {}, transient = true)) return open

        val pinHs = handshake(target, seed)
        if (!pinHs.pairSetupBegin { }) return null
        val pin = askPin() ?: return null
        if (!pinHs.pairSetupWithPin(pin, { })) return null
        pinHs.lastAccessoryLtpk?.let {
            prefs.edit().putString("ltpk:${target.host}", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
        }
        val verified = handshake(target, seed).apply { knownAccessoryLtpk = pinHs.lastAccessoryLtpk }
        return if (verified.pairVerify { }) verified else null
    }

    private fun handshake(target: AirDevice, seed: ByteArray) =
        PairingHandshake(target.host, target.port, "PocoCast-Android", seed)

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
            media.directUrl?.let { return it }
            say("Downloading ${media.title}…")
            val dir = File(context.cacheDir, "cast").apply { mkdirs() }
            val video = File(dir, "video.mp4").apply { delete() }
            val audio = File(dir, "audio.m4a").apply { delete() }
            download(media.videoUrl!!, video, say)
            download(media.audioUrl!!, audio, say)
            val output = File(dir, "youtube.mp4").apply { delete() }
            val joined = Remuxer.combine(video, audio, output, { false }) { percent ->
                say("Joining the tracks… $percent%")
            }
            video.delete(); audio.delete()
            return serve(joined.file, "video/mp4", target)
        }

        val info = StreamSource.probe(raw)
        val host = runCatching { URL(raw).host }.getOrNull()
        val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
        if (!loopback && (info.container == StreamInfo.Container.MP4 ||
                info.container == StreamInfo.Container.HLS)) {
            say("The TV can fetch this ${info.describe()} itself")
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
        if (info.container == StreamInfo.Container.MP4) {
            return serve(source, "video/mp4", target)
        }

        say("Repackaging for the Apple TV…")
        val output = File(dir, "cast.mp4").apply { delete() }
        val result = Remuxer.remux(source, output, { false }) { percent ->
            say("Repackaging $percent%")
        }
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
        val source = object : RangeSource {
            override val length: Long = file.length()
            override val mimeType: String = mime
            override fun open(offset: Long): java.io.InputStream =
                FileInputStream(file).apply { channel.position(offset) }
        }
        val http = LocalMediaHttpServer(source)
        val port = http.start()
        server?.stop()
        server = http
        val ip = pickLanIpForTarget(localAddresses(), target.host)
            ?: throw IOException("no LAN address to serve the video from")
        return "http://$ip:$port${http.path}"
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

    companion object { private const val PREFS = "pococast" }
}
