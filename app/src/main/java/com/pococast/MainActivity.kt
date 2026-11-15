package com.pococast

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.pococast.airplay.AirPlaySession
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var devicesBox: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var fileView: TextView
    private lateinit var ipField: EditText
    private lateinit var urlField: EditText

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var discovery: AirPlayDiscovery? = null
    private var server: LocalMediaServer? = null
    private var session: AirPlaySession? = null

    private var targetName: String? = null
    private var targetHost: String? = null

    private var mediaUri: Uri? = null
    private var mediaName: String = ""
    private var mediaMime: String? = null
    private var remoteUrl: String? = null

    /** A stream already converted to MP4, and the URL it was converted from. */
    private var preparedFile: File? = null
    private var preparedFrom: String? = null

    /** Headers the source asked for, which only a relay can add. */
    private var streamHeaders: Map<String, String> = emptyMap()

    /** The last YouTube resolution, so Play does not ask twice. */
    private var youTubeFor: String? = null
    private var youTubeChoice: YouTubeChoice? = null

    @Volatile
    private var busy = false

    @Volatile
    private var cancelRequested = false

    private var basePosition = 0.0
    private var baseTime = 0L
    private var playing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            handleIntent(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        discovery?.stop()
        worker.shutdownNow()
        server?.stop()
        session?.close()
    }

    // ---------------------------------------------------------------- user interface

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(24))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Plays a video, a link or a P2P stream from this phone on an Apple TV, over AirPlay."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        })

        root.addView(sectionTitle("APPLE TV"))
        devicesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(devicesBox)
        root.addView(button("Find Apple TVs") { startDiscovery() })

        val ipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        ipField = EditText(this).apply {
            hint = "192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        ipRow.addView(ipField)
        ipRow.addView(button("Use IP") { useManualIp() })
        root.addView(ipRow)

        root.addView(sectionTitle("VIDEO"))
        fileView = TextView(this).apply {
            text = "No video chosen"
            textSize = 14f
        }
        root.addView(fileView)
        root.addView(button("Choose video") { pickVideo() })

        urlField = EditText(this).apply {
            hint = "or a link / P2P stream URL from Nuvio"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(urlField)
        root.addView(button("Cast this link") { useLink() })

        val prepareRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prepareRow.addView(weighted(button("Prepare for Apple TV") { prepareForCast() }))
        prepareRow.addView(weighted(button("Cancel") { cancelPreparation() }))
        root.addView(prepareRow)

        root.addView(sectionTitle("PLAYBACK"))
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(weighted(button("Play") { startPlayback() }))
        row1.addView(weighted(button("Pause") { pause() }))
        row1.addView(weighted(button("Resume") { resume() }))
        root.addView(row1)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(weighted(button("Stop") { stopPlayback() }))
        row2.addView(weighted(button("-10 s") { seekRelative(-10.0) }))
        row2.addView(weighted(button("+10 s") { seekRelative(10.0) }))
        root.addView(row2)

        root.addView(sectionTitle("STATUS"))
        statusView = TextView(this).apply {
            text = "Idle. Find your Apple TV, choose a video, then tap Play."
            textSize = 13f
            setTextIsSelectable(true)
        }
        root.addView(statusView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        return scroll
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        letterSpacing = 0.08f
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun weighted(button: Button): Button = button.apply {
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- discovery

    private fun startDiscovery() {
        devicesBox.removeAllViews()
        setStatus("Searching the local network for Apple TVs…")

        val finder = AirPlayDiscovery(this)
        discovery = finder
        finder.start(
            onDevice = { device ->
                main.post {
                    addDeviceButton(device)
                    setStatus("Found " + device.name + ". Tap it to select it.")
                }
            },
            onFinished = { message ->
                main.post { if (message != null) setStatus(message) }
            }
        )

        main.postDelayed({ discovery?.stop() }, 12000)
    }

    private fun addDeviceButton(device: AirPlayDiscovery.Device) {
        devicesBox.addView(button(device.name + "  (" + device.host + ")") {
            select(device.name, device.host)
        })
    }

    private fun useManualIp() {
        val host = ipField.text.toString().trim()
        if (host.isEmpty()) {
            setStatus("Type the Apple TV's IP address first.")
            return
        }
        select("Apple TV at $host", host)
    }

    private fun select(name: String, host: String) {
        targetName = name
        targetHost = host
        session?.close()
        session = null
        setStatus("Connecting to $name…")
        worker.execute {
            try {
                val probe = AirPlaySession(host)
                probe.connect()
                probe.close()
                setStatus("Selected $name. AirPlay port 7000 answers.")
            } catch (error: Throwable) {
                setStatus("Selected $name, but port 7000 did not answer: ${error.message}")
            }
        }
    }

    // ---------------------------------------------------------------- media selection

    @Suppress("DEPRECATION")
    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        try {
            startActivityForResult(intent, REQUEST_PICK)
        } catch (error: Throwable) {
            setStatus("No file picker available: ${error.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK && resultCode == RESULT_OK && data?.data != null) {
            setMedia(data.data!!)
        }
    }

    private fun setMedia(uri: Uri) {
        mediaUri = uri
        remoteUrl = null
        preparedFile = null
        preparedFrom = null
        streamHeaders = emptyMap()
        youTubeFor = null
        youTubeChoice = null
        mediaName = displayName(uri) ?: "video.mp4"
        mediaMime = contentResolver.getType(uri)
        fileView.text = "Video: $mediaName"
        setStatus("Video ready. Tap Play to send it to the Apple TV.")
    }

    private fun useLink() {
        val link = linkIn(urlField.text.toString())
        if (link == null) {
            setStatus("That is not a link. It has to start with http:// or https://")
            return
        }
        setLink(link)
    }

    /**
     * Nuvio and similar apps hand a stream over as ACTION_VIEW with the URL as
     * data, plus a "title" and sometimes a "headers" extra for sources that only
     * answer with the right Referer or User-Agent. AirPlay cannot send those,
     * so they are kept and replayed by the phone's relay instead of being lost.
     */
    private fun setLink(url: String, source: Intent? = null) {
        mediaUri = null
        mediaName = ""
        mediaMime = null
        remoteUrl = url
        preparedFile = null
        preparedFrom = null
        youTubeFor = null
        youTubeChoice = null
        streamHeaders = headersFrom(source)

        if (::urlField.isInitialized) urlField.setText(url)

        val title = source?.getStringExtra(EXTRA_TITLE)
        fileView.text = if (title.isNullOrBlank()) "Link: $url" else "Link: $title"

        val notes = ArrayList<String>()
        if (streamHeaders.isNotEmpty()) {
            notes.add("it needs request headers, so PocoCast will relay it and add them")
        }
        if (isLoopback(url)) {
            notes.add("it only exists on this phone, so PocoCast will relay it")
        }
        val opening = if (notes.isEmpty()) {
            "Link ready."
        } else {
            "Link ready, but " + notes.joinToString("; ") + "."
        }

        // A YouTube link is not a stream at all until a front-end resolves it,
        // and probing the page would only fetch HTML.
        if (YouTube.videoIdIn(url) != null) {
            setStatus(opening + " YouTube link: tap Play and PocoCast asks a front-end for a direct stream.")
            return
        }

        setStatus(opening + " Checking the stream…")

        // Ask the stream what it is straight away, so the user is told before
        // tapping Play whether the Apple TV can take it as it stands or it has
        // to be repackaged as an MP4 first.
        val probeUrl = url
        val probeHeaders = streamHeaders
        worker.execute {
            try {
                val info = StreamSource.probe(probeUrl, probeHeaders)
                if (remoteUrl == probeUrl) setStatus(opening + " " + describe(info))
            } catch (error: Throwable) {
                if (remoteUrl == probeUrl) {
                    setStatus(
                        opening + " Could not check it (" +
                            (error.message ?: error.javaClass.simpleName) + "); Play will try anyway."
                    )
                }
            }
        }
    }

    /**
     * Nuvio and other players pass a `headers` extra for sources that will only
     * answer with the right Referer or User-Agent. AirPlay cannot send those, so
     * they are read here and replayed by the phone's relay.
     */
    private fun headersFrom(source: Intent?): Map<String, String> {
        val raw = source?.extras?.get(EXTRA_HEADERS) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        when (raw) {
            is Bundle -> for (key in raw.keySet()) {
                val value = raw.get(key)
                if (value is String) out[key] = value
            }

            is String -> {
                val text = raw.trim()
                if (text.startsWith("{")) {
                    try {
                        val json = JSONObject(text)
                        for (key in json.keys()) out[key] = json.optString(key)
                    } catch (ignored: Exception) {
                    }
                } else {
                    for (line in text.lines()) putHeaderLine(out, line)
                }
            }

            is Array<*> -> for (entry in raw) {
                if (entry is String) putHeaderLine(out, entry)
            }

            is Map<*, *> -> for ((key, value) in raw) {
                if (key is String && value is String) out[key] = value
            }
        }
        return out
    }

    private fun putHeaderLine(out: MutableMap<String, String>, line: String) {
        val colon = line.indexOf(':')
        if (colon > 0) out[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
    }

    private fun describe(info: StreamInfo): String {
        return when (info.container) {
            StreamInfo.Container.MP4 -> info.describe() + ". The Apple TV takes this as it is; tap Play."
            StreamInfo.Container.HLS -> info.describe() + ". The Apple TV takes this as it is; tap Play."
            StreamInfo.Container.DASH ->
                "DASH. AirPlay cannot play this; pick another source for the title in Nuvio."
            else -> info.describe() +
                ". Tap Play and PocoCast repackages it as MP4 first, which takes a few minutes."
        }
    }

    private fun linkIn(text: String): String? =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'', ';')

    private fun displayName(uri: Uri): String? {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
                }
        } catch (ignored: Exception) {
        }
        return uri.lastPathSegment
    }

    @Suppress("DEPRECATION")
    private fun handleIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_VIEW) return

        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val link = linkIn(text)
            if (link != null) {
                setLink(link, intent)
            } else if (text.contains("magnet:", ignoreCase = true)) {
                setStatus(torrentHandoffMessage())
            } else {
                setStatus("That share had no http(s) link in it.")
            }
            return
        }

        val data = intent.data
        val scheme = data?.scheme?.lowercase(Locale.US)
        if (scheme == "magnet" || scheme == "stremio" || scheme == "torrent") {
            setStatus(torrentHandoffMessage())
            return
        }
        if (data != null && (data.scheme == "http" || data.scheme == "https")) {
            // Nuvio and other players hand their stream URL over this way.
            setLink(data.toString(), intent)
            return
        }

        val uri: Uri? = if (intent.action == Intent.ACTION_SEND) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } else {
            data
        }
        if (uri != null) setMedia(uri)
    }

    private fun torrentHandoffMessage(): String =
        "That is a torrent link, not a stream URL. Start the stream in Nuvio first, then use " +
            "Nuvio's \"Open in external player\" action: PocoCast needs the URL of the stream " +
            "Nuvio is already playing on this phone."

    // ---------------------------------------------------------------- playback

    private fun startPlayback() {
        if (busy) {
            setStatus("Still busy with the last request.")
            return
        }
        val host = targetHost
        if (host == null) {
            setStatus("Find or enter an Apple TV first.")
            return
        }
        val uri = mediaUri
        val link = remoteUrl
        if (uri == null && link == null) {
            setStatus("Choose a video or paste a link first.")
            return
        }

        busy = true
        worker.execute {
            try {
                val media = server ?: LocalMediaServer(applicationContext).also {
                    it.start()
                    server = it
                }
                val url: String
                if (uri != null) {
                    setStatus("Preparing the local media server…")
                    media.setMedia(uri, mediaName, mediaMime)
                    url = media.urlFor(requireLocalAddress())
                } else {
                    url = playableUrlFor(link!!, media)
                }
                setStatus("Asking the Apple TV to play $url")

                val active = session?.takeIf { it.address == host } ?: run {
                    session?.close()
                    AirPlaySession(host).also {
                        it.connect()
                        session = it
                    }
                }

                // A current tvOS answers 403 to /play until the connection has
                // completed HomeKit pairing, so that always comes first.
                var pairingNote = "already paired"
                if (!active.isPaired) {
                    setStatus("Pairing with " + (targetName ?: host) + "…")
                    var pairingError: String? = null
                    val pairing = try {
                        active.pair()
                    } catch (error: Throwable) {
                        pairingError = error.message ?: error.javaClass.simpleName
                        null
                    }
                    pairingNote = when {
                        pairingError != null -> "it failed: $pairingError"
                        pairing == null -> "it failed with no reply"
                        pairing.code != 200 -> "refused (" + pairing.code + " " + pairing.statusText + ")"
                        else -> "ok"
                    }
                    if (pairing == null || pairing.code != 200) {
                        setStatus(
                            "The Apple TV refused pairing (" + pairingNote + "). On the TV open " +
                                "Settings > AirPlay and HomeKit, set Allow Access to \"Anyone on the " +
                                "Same Network\", and turn Require Password off."
                        )
                    }
                }

                var response = active.play(url)
                if (response.code != 200) {
                    Thread.sleep(700)
                    response = active.play(url)
                }
                if (response.code != 200) {
                    response = active.playLegacy(url)
                }

                basePosition = 0.0
                baseTime = System.currentTimeMillis()
                playing = response.code == 200
                if (playing) {
                    setStatus("Playback started on " + (targetName ?: host) + ".")
                } else {
                    val report = try {
                        active.diagnose()
                    } catch (error: Throwable) {
                        "diagnosis failed: " + (error.message ?: error.javaClass.simpleName)
                    }
                    setStatus(
                        "AirPlay refused the request (" + response.code + " " + response.statusText + "). " +
                            "Pairing said " + pairingNote + ". The video is served at $url for a player " +
                            "app on the TV. Diagnosis:\n" + report
                    )
                }
            } catch (cancelled: CancelledException) {
                playing = false
                setStatus("Cancelled.")
            } catch (error: Throwable) {
                playing = false
                setStatus("Could not start playback: " + (error.message ?: error.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    /**
     * Works out the URL to hand the Apple TV for the stream that is selected.
     *
     * Three outcomes: the Apple TV fetches the link itself, the phone relays it
     * because it only exists on the phone (a P2P engine on 127.0.0.1), or it is
     * copied down and repackaged as MP4 because AirPlay will not take the
     * container the stream is in.
     */
    private fun playableUrlFor(link: String, media: LocalMediaServer): String {
        val prepared = preparedFile
        if (prepared != null && prepared.isFile && preparedFrom == link) {
            media.setFile(prepared, "pococast.mp4", "video/mp4")
            return media.urlFor(requireLocalAddress())
        }

        if (YouTube.videoIdIn(link) != null) {
            return playableYouTube(link, media)
        }

        setStatus("Checking the stream…")
        val info = try {
            StreamSource.probe(link, streamHeaders)
        } catch (error: Throwable) {
            throw IOException(
                "could not reach the stream: " + (error.message ?: error.javaClass.simpleName) +
                    ". Is the app serving it still open?"
            )
        }

        // Headers only a relay can add, and an address only this phone can
        // reach, both mean the Apple TV has to be given the phone's own URL.
        val needsRelay = isLoopback(link) || streamHeaders.isNotEmpty()
        return when (info.container) {
            StreamInfo.Container.DASH -> throw IOException(
                "this is a DASH stream, which the Apple TV's AirPlay player cannot take; " +
                    "pick a different source for the title in Nuvio"
            )

            StreamInfo.Container.HLS -> {
                if (needsRelay) {
                    media.setProxy(link, streamHeaders, playlist = true)
                    media.urlFor(requireLocalAddress())
                } else {
                    link
                }
            }

            StreamInfo.Container.MP4 -> {
                if (needsRelay) {
                    media.setProxy(link, streamHeaders)
                    media.urlFor(requireLocalAddress())
                } else {
                    link
                }
            }

            else -> {
                val address = requireLocalAddress()
                val output = prepare(link, info)
                media.setFile(output, "pococast.mp4", "video/mp4")
                media.urlFor(address)
            }
        }
    }

    /**
     * YouTube is cast without its player page ever loading, so there is nothing
     * to advertise on. A front-end resolves the video to direct streams: either
     * one muxed file, which is relayed as it is, or a video track and an audio
     * track, which are downloaded and joined into one MP4 here.
     */
    private fun playableYouTube(link: String, media: LocalMediaServer): String {
        val address = requireLocalAddress()
        val choice = resolveYouTube(link)

        if (choice.audioUrl == null) {
            setStatus("YouTube (" + choice.label + "), relaying " + choice.title)
            media.setProxy(choice.videoUrl, emptyMap())
            return media.urlFor(address)
        }

        val joined = combineYouTube(link, choice)
        media.setFile(joined, "youtube.mp4", "video/mp4")
        return media.urlFor(address)
    }

    private fun resolveYouTube(link: String): YouTubeChoice {
        val cached = youTubeChoice
        if (cached != null && youTubeFor == link) return cached
        val id = YouTube.videoIdIn(link)
            ?: throw IOException("that YouTube link has no video id in it")
        val choice = YouTube.resolve(id, { cancelRequested }, { setStatus(it) })
        youTubeFor = link
        youTubeChoice = choice
        return choice
    }

    private fun combineYouTube(link: String, choice: YouTubeChoice): File {
        val prepared = preparedFile
        if (prepared != null && prepared.isFile && preparedFrom == link) return prepared

        val audioUrl = choice.audioUrl
            ?: throw IOException("this stream has no separate audio track")

        val output = File(cacheFolder(), "youtube-" + Integer.toHexString(link.hashCode()) + ".mp4")
        output.delete()

        val video = downloadToCache(choice.videoUrl, "youtube-video", "Downloading the video track")
        val audio = try {
            downloadToCache(audioUrl, "youtube-audio", "Downloading the audio track")
        } catch (error: Throwable) {
            video.delete()
            throw error
        }
        try {
            setStatus("Joining the video and audio tracks…")
            val result = Remuxer.combine(video, audio, output, { cancelRequested }) { percent ->
                setStatus("Joining the video and audio tracks… $percent%")
            }
            preparedFile = result.file
            preparedFrom = link
            return result.file
        } finally {
            video.delete()
            audio.delete()
        }
    }

    /** Converts the stream ahead of time so tapping Play is instant. */
    private fun prepareForCast() {
        if (busy) {
            setStatus("Still busy with the last request.")
            return
        }
        val link = remoteUrl
        if (link == null) {
            setStatus("Paste or share a stream link first.")
            return
        }

        busy = true
        worker.execute {
            try {
                cancelRequested = false

                if (YouTube.videoIdIn(link) != null) {
                    val choice = resolveYouTube(link)
                    if (choice.audioUrl == null) {
                        setStatus(
                            "YouTube (" + choice.label + "): nothing to prepare, the single stream " +
                                "is relayed as it is. Tap Play."
                        )
                    } else {
                        val file = combineYouTube(link, choice)
                        setStatus(
                            "Ready to cast: YouTube " + choice.label + " joined into one MP4 (" +
                                StreamSource.formatSize(file.length()) + "). Tap Play."
                        )
                    }
                    return@execute
                }

                setStatus("Checking the stream…")
                val info = StreamSource.probe(link, streamHeaders)
                val relayed = isLoopback(link) || streamHeaders.isNotEmpty()
                when (info.container) {
                    StreamInfo.Container.MP4, StreamInfo.Container.HLS -> setStatus(
                        if (relayed) {
                            info.describe() + ". PocoCast relays this through the phone; tap Play."
                        } else {
                            info.describe() + ". The Apple TV fetches this itself; nothing to prepare, tap Play."
                        }
                    )

                    StreamInfo.Container.DASH ->
                        setStatus("This is a DASH stream, which AirPlay cannot play.")

                    else -> {
                        val output = prepare(link, info)
                        setStatus(
                            "Ready to cast: repackaged as MP4 (" +
                                StreamSource.formatSize(output.length()) + "). Tap Play."
                        )
                    }
                }
            } catch (cancelled: CancelledException) {
                setStatus("Cancelled.")
            } catch (error: Throwable) {
                setStatus("Could not prepare the stream: " + (error.message ?: error.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    private fun cancelPreparation() {
        if (!busy) {
            setStatus("Nothing is being prepared.")
            return
        }
        cancelRequested = true
        setStatus("Cancelling after the current step…")
    }

    /**
     * Copies a stream down to the app's cache and repackages it as MP4, which
     * is the only way a Matroska or MPEG-TS release — what P2P sources almost
     * always are — gets onto the Apple TV. Both steps report progress; the
     * source copy is deleted afterwards so the cache does not grow by a film
     * every time.
     */
    private fun prepare(link: String, info: StreamInfo): File {
        val folder = cacheFolder()
        val key = Integer.toHexString(link.hashCode())
        val source = File(folder, key + ".source")
        val output = File(folder, key + ".mp4")

        cancelRequested = false
        try {
            source.delete()
            output.delete()

            setStatus("Copying the stream (" + info.describe() + ") so it can be repackaged…")
            StreamSource.download(
                link, source, { cancelRequested },
                { written, total -> setStatus("Copying the stream… " + copyProgress(written, total)) },
                streamHeaders
            )

            setStatus("Repackaging for the Apple TV…")
            val result = Remuxer.remux(source, output, { cancelRequested }) { percent ->
                setStatus("Repackaging for the Apple TV… $percent%")
            }

            preparedFile = result.file
            preparedFrom = link
            if (result.audioMime == null) {
                setStatus(
                    "Repackaged without sound: this release's audio could not be converted on this " +
                        "phone. Play continues; pick a release with AAC or AC-3 for sound."
                )
            }
            return result.file
        } finally {
            source.delete()
        }
    }

    private fun cacheFolder(): File {
        val folder = File(cacheDir, "pococast")
        if (!folder.isDirectory && !folder.mkdirs()) {
            throw IOException("could not make room in the app's cache")
        }
        return folder
    }

    private fun downloadToCache(url: String, prefix: String, what: String): File {
        val file = File(cacheFolder(), prefix + "-" + Integer.toHexString(url.hashCode()) + ".part")
        file.delete()
        setStatus(what + "…")
        StreamSource.download(
            url, file, { cancelRequested },
            { written, total -> setStatus(what + "… " + copyProgress(written, total)) }
        )
        return file
    }

    private fun copyProgress(written: Long, total: Long): String {
        if (total <= 0L) return StreamSource.formatSize(written) + " of an unknown size"
        val percent = (written * 100 / total).coerceIn(0, 100)
        return "$percent% (" + StreamSource.formatSize(written) + " of " + StreamSource.formatSize(total) + ")"
    }

    private fun isLoopback(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.US) ?: return false
        return host == "localhost" || host == "0.0.0.0" || host == "::1" || host == "[::1]" ||
            host.startsWith("127.")
    }

    private fun requireLocalAddress(): String =
        localIp() ?: throw IOException(
            "This phone has no Wi-Fi IPv4 address, so the Apple TV cannot reach it."
        )

    private fun pause() {
        val active = session ?: return setStatus("Nothing is casting yet.")
        worker.execute {
            try {
                basePosition = currentPosition()
                playing = false
                val response = active.rate(0.0)
                setStatus("Paused (AirPlay replied " + response.code + ").")
            } catch (error: Throwable) {
                setStatus("Pause failed: ${error.message}")
            }
        }
    }

    private fun resume() {
        val active = session ?: return setStatus("Nothing is casting yet.")
        worker.execute {
            try {
                val response = active.rate(1.0)
                baseTime = System.currentTimeMillis()
                playing = response.code == 200
                setStatus("Resumed (AirPlay replied " + response.code + ").")
            } catch (error: Throwable) {
                setStatus("Resume failed: ${error.message}")
            }
        }
    }

    private fun seekRelative(delta: Double) {
        val active = session ?: return setStatus("Nothing is casting yet.")
        worker.execute {
            try {
                val target = maxOf(0.0, currentPosition() + delta)
                val response = active.scrub(target)
                basePosition = target
                baseTime = System.currentTimeMillis()
                setStatus("Sought to " + target.toInt() + " s (AirPlay replied " + response.code + ").")
            } catch (error: Throwable) {
                setStatus("Seek failed: ${error.message}")
            }
        }
    }

    private fun stopPlayback() {
        val active = session ?: return setStatus("Nothing is casting yet.")
        worker.execute {
            try {
                val response = active.stop()
                playing = false
                basePosition = 0.0
                setStatus("Stopped (AirPlay replied " + response.code + ").")
            } catch (error: Throwable) {
                setStatus("Stop failed: ${error.message}")
            }
        }
    }

    private fun currentPosition(): Double =
        if (playing) basePosition + (System.currentTimeMillis() - baseTime) / 1000.0 else basePosition

    // ---------------------------------------------------------------- helpers

    private fun setStatus(message: String) {
        main.post { statusView.text = message }
    }

    private fun localIp(): String? {
        try {
            val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val properties: LinkProperties = manager.getLinkProperties(manager.activeNetwork) ?: return null
            return properties.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        } catch (ignored: Exception) {
            return null
        }
    }

    private companion object {
        const val REQUEST_PICK = 4711
        const val EXTRA_TITLE = "title"
        const val EXTRA_HEADERS = "headers"
    }
}
