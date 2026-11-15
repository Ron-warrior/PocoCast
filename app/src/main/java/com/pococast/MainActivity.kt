package com.pococast

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.airsonic.sender.api.AirDevice
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val cast = CastController()

    private lateinit var skin: Skin
    private lateinit var statusText: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var deviceBox: LinearLayout
    private lateinit var link: EditText
    private lateinit var castButton: TextView
    private lateinit var stopButton: TextView

    private var selected: AirDevice? = null
    private var busy = false
    private var lastReport: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skin = Skin(this)

        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(buildHeader())
        page.addView(
            View(this).apply { setBackgroundColor(skin.cyan) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        )

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(30))
        }
        page.addView(body)

        body.addView(sectionRow("Choose a screen", miniButton("Refresh") { discover() }))
        deviceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(deviceBox, matchWrap(top = 10))

        body.addView(buildStatusCard(), matchWrap(top = 14))

        body.addView(sectionRow("Or paste a link"), matchWrap(top = 24))
        body.addView(buildInputCard(), matchWrap(top = 8))
        body.addView(buildSourceTags(), matchWrap(top = 10))
        body.addView(buildButtons(), matchWrap(top = 18))

        body.addView(
            label(
                "The first cast shows a 4-digit PIN on the TV. After that PocoCast reconnects on its own.",
                11.5f,
                skin.muted
            ).apply { setLineSpacing(dp(3).toFloat(), 1f) },
            matchWrap(top = 14)
        )
        body.addView(
            miniButton("Export diagnostics") { exportDiagnostics() },
            matchWrap(top = 16)
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(skin.paper)
            isFillViewport = true
            addView(page)
        })

        showDevices()
        discover()

        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (shared.isNotEmpty()) {
                link.setText(shared)
                say("Link received — pick a screen, then tap Cast.")
            }
        }
    }

    // ---- layout ----

    private fun buildHeader(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = gradient(
                skin.color(R.color.navy),
                skin.color(R.color.navy_mid),
                skin.color(R.color.navy_end)
            )
            setPadding(dp(22), dp(26), dp(22), dp(22))
        }

        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(
            label("PC", 14f, skin.color(R.color.cyan), medium = true).apply {
                gravity = Gravity.CENTER
                letterSpacing = 0.06f
                background = rounded(
                    0x14FFFFFF,
                    dp(12).toFloat(),
                    skin.color(R.color.cyan),
                    dp(1.5).toFloat()
                )
            },
            LinearLayout.LayoutParams(dp(46), dp(46))
        )
        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        words.addView(label("PocoCast", 21f, skin.color(R.color.on_navy), medium = true))
        words.addView(
            label("Phone → Apple TV, over AirPlay", 12.5f, skin.color(R.color.on_navy_muted)).apply {
                setPadding(0, dp(3), 0, 0)
            }
        )
        brand.addView(words)
        box.addView(brand)

        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        listOf("AirPlay 2", "1080p", "Ad-free").forEach { text ->
            meta.addView(
                label(text.uppercase(), 9f, skin.color(R.color.on_navy_meta), medium = true).apply {
                    letterSpacing = 0.12f
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background = rounded(
                        0x0FFFFFFF,
                        dp(999).toFloat(),
                        0x24FFFFFF,
                        dp(1).toFloat()
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            )
        }
        box.addView(meta)
        return box
    }

    private fun sectionRow(title: String, action: View? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(label(title.uppercase(), 10f, skin.muted, medium = true).apply {
            letterSpacing = 0.14f
        })
        if (action != null) {
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            row.addView(action)
        }
        return row
    }

    private fun buildStatusCard(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(skin.card, dp(12).toFloat(), skin.line, dp(1).toFloat())
            clipToOutline = true
            elevation = dp(2).toFloat()
        }
        row.addView(
            View(this).apply { setBackgroundColor(skin.cyan) },
            LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
        )
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        statusText = label("Looking for Apple TVs…", 13.5f, skin.ink).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        inner.addView(
            statusText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        statusSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(skin.cyan)
            visibility = View.GONE
        }
        inner.addView(
            statusSpinner,
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(10) }
        )
        row.addView(
            inner,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return row
    }

    private fun buildInputCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(skin.card, dp(12).toFloat(), skin.line, dp(1).toFloat())
            setPadding(dp(14), 0, dp(14), 0)
            elevation = dp(2).toFloat()
        }
        link = EditText(this).apply {
            hint = "youtube.com/watch?v=…  ·  stream URL"
            setHintTextColor(skin.muted)
            setTextColor(skin.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            background = null
            setPadding(0, dp(14), 0, dp(14))
            maxLines = 2
        }
        card.addView(
            link,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        return card
    }

    private fun buildSourceTags(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("YouTube · 1080p", "Nuvio link", "HLS / MP4").forEach { text ->
            row.addView(
                label(text.uppercase(), 9f, skin.muted, medium = true).apply {
                    letterSpacing = 0.12f
                    setPadding(dp(9), dp(5), dp(9), dp(5))
                    background = rounded(
                        skin.color(R.color.chip),
                        dp(999).toFloat(),
                        skin.line,
                        dp(1).toFloat()
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
            )
        }
        return row
    }

    private fun buildButtons(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        castButton = cta("Cast to Apple TV", primary = true) {
            val url = link.text.toString().trim()
            if (url.isEmpty()) say("Paste a link first.") else startCast(url)
        }
        row.addView(castButton, LinearLayout.LayoutParams(0, dp(52), 1f))

        stopButton = cta("Stop", primary = false) { stopCasting() }
        row.addView(
            stopButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(52))
                .apply { marginStart = dp(10) }
        )
        return row
    }

    private fun deviceCard(device: AirDevice, chosen: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(
                rounded(
                    if (chosen) skin.color(R.color.card_selected) else skin.card,
                    dp(12).toFloat(),
                    if (chosen) skin.cyan else skin.line,
                    dp(1).toFloat()
                ),
                skin.color(R.color.ripple)
            )
            setPadding(dp(14), dp(14), dp(14), dp(14))
            elevation = dp(2).toFloat()
            isClickable = true
            isFocusable = true
        }

        card.addView(
            View(this).apply {
                background = if (chosen) circle(skin.cyan) else circle(
                    0x00000000,
                    skin.color(R.color.line_strong),
                    dp(1.5).toFloat()
                )
            },
            LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(12) }
        )

        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(label(device.name, 15f, skin.ink, medium = true))
        words.addView(label(device.host, 12f, skin.muted).apply { setPadding(0, dp(3), 0, 0) })
        card.addView(
            words,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        if (chosen) {
            card.addView(label("Selected", 9f, skin.cyanInk, medium = true).apply {
                letterSpacing = 0.12f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                background = rounded(skin.color(R.color.cyan_soft), dp(999).toFloat())
            })
        }

        card.setOnClickListener {
            selected = device
            showDevices()
            say("Selected ${device.name}")
        }
        return card
    }

    private fun miniButton(text: String, onClick: () -> Unit): TextView =
        label(text, 12f, skin.muted, medium = true).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = ripple(
                rounded(skin.card, dp(999).toFloat(), skin.line, dp(1).toFloat()),
                skin.color(R.color.ripple)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun cta(text: String, primary: Boolean, onClick: () -> Unit): TextView {
        val fill = if (primary) skin.color(R.color.cta) else Color.TRANSPARENT
        val stroke = if (primary) fill else skin.line
        return label(
            text,
            15f,
            if (primary) skin.color(R.color.cta_text) else skin.ink,
            medium = true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = ripple(
                rounded(fill, dp(12).toFloat(), stroke, dp(1).toFloat()),
                skin.color(if (primary) R.color.ripple_cta else R.color.ripple)
            )
            isClickable = true
            isFocusable = true
            if (primary) elevation = dp(3).toFloat()
            setOnClickListener { onClick() }
        }
    }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    // ---- discovery ----

    private fun discover() {
        say("Looking for Apple TVs…")
        cast.startDiscovery(
            this,
            onChanged = { runOnUiThread { showDevices() } },
            onError = { message -> say("Discovery failed: $message") }
        )
    }

    private fun showDevices() {
        deviceBox.removeAllViews()
        val found = cast.devices
        if (found.isEmpty()) {
            deviceBox.addView(
                label(
                    "No Apple TVs yet.\nSame Wi-Fi as the TV, and AirPlay switched on in its settings.",
                    13f,
                    skin.muted
                ).apply {
                    gravity = Gravity.CENTER
                    setLineSpacing(dp(4).toFloat(), 1f)
                    setPadding(dp(18), dp(22), dp(18), dp(22))
                    background = rounded(
                        skin.card,
                        dp(12).toFloat(),
                        skin.color(R.color.line_strong),
                        dashed = true
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return
        }

        if (selected == null || found.none { it.id == selected?.id }) selected = found.first()
        found.forEachIndexed { index, device ->
            deviceBox.addView(
                deviceCard(device, selected?.id == device.id),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = if (index == 0) 0 else dp(8) }
            )
        }
    }

    // ---- casting ----

    private fun startCast(raw: String) {
        val target = selected ?: return say("Pick an Apple TV first.")
        if (busy) return
        Diag.reset()
        Diag.log("ui: cast tapped for " + target.name + " (" + target.host + ")")
        setBusy(true)
        say("Connecting to ${target.name}…")
        worker.execute {
            try {
                cast.cast(this, target, raw, { message -> say(message) }, { askPinBlocking() })
                say("Sent to ${target.name}. If the TV stays black, it refused the stream URL.")
            } catch (t: Throwable) {
                Diag.error("cast failed", t)
                val saved = saveReport()
                say(
                    "Failed: " + (t.message ?: t.javaClass.simpleName) +
                        if (saved != null) " — log saved to $saved" else " — log could not be saved"
                )
            } finally {
                runOnUiThread { setBusy(false) }
            }
        }
    }

    private fun stopCasting() {
        if (busy) return
        worker.execute {
            cast.stop()
            runOnUiThread { say("Stopped.") }
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        castButton.isEnabled = !value
        castButton.alpha = if (value) 0.55f else 1f
        castButton.text = if (value) "Casting…" else "Cast to Apple TV"
        stopButton.alpha = if (value) 0.55f else 1f
        statusSpinner.visibility = if (value) View.VISIBLE else View.GONE
    }

    /** Runs on the worker thread; blocks until the dialog is answered. */
    private fun askPinBlocking(): String? {
        val latch = CountDownLatch(1)
        var pin: String? = null

        runOnUiThread {
            val dialog = Dialog(this)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(22), dp(22), dp(16))
                background = rounded(skin.card, dp(18).toFloat(), skin.line, dp(1).toFloat())
            }
            card.addView(
                label("APPLE TV", 9.5f, skin.cyanInk, medium = true).apply { letterSpacing = 0.16f }
            )
            card.addView(
                label("Enter the PIN", 21f, skin.ink, medium = true).apply {
                    setPadding(0, dp(8), 0, 0)
                }
            )
            card.addView(
                label("Your TV is showing a 4-digit code.", 13f, skin.muted).apply {
                    setPadding(0, dp(6), 0, 0)
                }
            )

            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(4))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(skin.ink)
                gravity = Gravity.CENTER
                letterSpacing = 0.35f
                hint = "0000"
                setHintTextColor(skin.color(R.color.line_strong))
                background = rounded(skin.paper, dp(12).toFloat(), skin.line, dp(1).toFloat())
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            card.addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
            )

            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(16), 0, 0)
            }
            actions.addView(
                cta("Cancel", primary = false) {
                    latch.countDown()
                    dialog.dismiss()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
            )
            actions.addView(
                cta("Pair", primary = true) {
                    pin = input.text.toString().trim()
                    latch.countDown()
                    dialog.dismiss()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
                    .apply { marginStart = dp(8) }
            )
            card.addView(actions)

            dialog.setContentView(card)
            dialog.setCanceledOnTouchOutside(false)
            dialog.setOnCancelListener { latch.countDown() }
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    resources.displayMetrics.widthPixels - dp(48),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            }
            input.requestFocus()
        }

        latch.await(120, TimeUnit.SECONDS)
        return pin
    }

    /** Writes the report into Downloads; returns the visible path, or null. */
    private fun saveReport(): String? {
        val file = runCatching { Diag.writeReport(this, cast.about()) }.getOrNull() ?: return null
        lastReport = file
        return Diag.saveToDownloads(this, file)
    }

    private fun exportDiagnostics() {
        val saved = saveReport()
        if (saved == null) {
            say("Could not write the diagnostics file.")
            return
        }
        Diag.log("ui: diagnostics saved to " + saved)
        say("Saved to $saved")
    }

    private fun say(message: String) {
        runOnUiThread { statusText.text = message }
    }
}
