package com.pococast

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.airsonic.sender.api.AirDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val cast = CastController()

    private lateinit var status: TextView
    private lateinit var deviceBox: LinearLayout
    private lateinit var link: EditText
    private lateinit var castButton: Button

    private var selected: AirDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 72, 56, 48)
        }
        root.addView(TextView(this).apply {
            text = "PocoCast"
            textSize = 28f
            setTextColor(Color.parseColor("#111111"))
        })
        root.addView(TextView(this).apply {
            text = "Cast links and YouTube to an Apple TV over AirPlay."
            textSize = 14f
            setPadding(0, 8, 0, 24)
        })

        status = TextView(this).apply {
            text = "Looking for Apple TVs…"
            textSize = 15f
            setPadding(0, 16, 0, 16)
        }

        root.addView(Button(this).apply {
            text = "Find Apple TVs"
            setOnClickListener { discover() }
        })

        deviceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceBox)

        root.addView(TextView(this).apply {
            text = "Video or YouTube link"
            textSize = 14f
            setPadding(0, 28, 0, 6)
        })
        link = EditText(this).apply {
            hint = "https://youtube.com/watch?v=… or a stream URL"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(link, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        castButton = Button(this).apply {
            text = "Cast"
            setOnClickListener {
                val url = link.text.toString().trim()
                if (url.isEmpty()) say("Paste a link first.") else startCast(url)
            }
        }
        root.addView(castButton)
        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { worker.execute { cast.stop(); say("Stopped.") } }
        })
        root.addView(status.also { root.removeView(it) })

        setContentView(ScrollView(this).apply { addView(root) })

        discover()

        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (shared.isNotEmpty()) {
                link.setText(shared)
                say("Shared link received — pick an Apple TV, then tap Cast.")
            }
        }
    }

    private fun discover() {
        cast.startDiscovery(
            this,
            onChanged = { runOnUiThread { showDevices() } },
            onError = { message -> say("Discovery failed: $message") }
        )
    }

    private fun showDevices() {
        deviceBox.removeAllViews()
        if (cast.devices.isEmpty()) {
            deviceBox.addView(TextView(this).apply {
                text = "No Apple TVs yet. Same Wi-Fi, and AirPlay on in the TV settings."
                textSize = 13f
            })
            return
        }
        if (selected == null) selected = cast.devices.first()
        for (device in cast.devices) {
            val chosen = selected?.id == device.id
            deviceBox.addView(Button(this).apply {
                text = (if (chosen) "● " else "○ ") + device.name + "  ·  " + device.host
                setOnClickListener { selected = device; showDevices(); say("Selected ${device.name}") }
            })
        }
    }

    private fun startCast(raw: String) {
        val target = selected ?: return say("Pick an Apple TV first.")
        castButton.isEnabled = false
        say("Connecting to ${target.name}…")
        worker.execute {
            try {
                cast.cast(
                    context = this,
                    target = target,
                    raw = raw,
                    say = { message -> say(message) },
                    askPin = { askPinBlocking() }
                )
                say("Sent to ${target.name}. If the TV shows nothing, the stream URL was refused.")
            } catch (t: Throwable) {
                say("Failed: " + (t.message ?: t.javaClass.simpleName))
            } finally {
                runOnUiThread { castButton.isEnabled = true }
            }
        }
    }

    /** Runs on the worker thread; blocks until the dialog is answered. */
    private fun askPinBlocking(): String? {
        val latch = CountDownLatch(1)
        var pin: String? = null
        runOnUiThread {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                hint = "1234"
            }
            AlertDialog.Builder(this)
                .setTitle("Apple TV PIN")
                .setMessage("Type the 4-digit code shown on the TV.")
                .setView(input)
                .setPositiveButton("Pair") { _, _ ->
                    pin = input.text.toString().trim()
                    latch.countDown()
                }
                .setNegativeButton("Cancel") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(120, TimeUnit.SECONDS)
        return pin
    }

    private fun say(message: String) {
        runOnUiThread { status.text = message }
    }
}
