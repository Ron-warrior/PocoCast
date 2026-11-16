package com.pococast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.io.File

/**
 * Every optional behaviour in one place. Nothing here is required for casting;
 * each switch just changes how PocoCast behaves around it.
 */
class SettingsActivity : Activity() {

    private lateinit var skin: Skin
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skin = Skin(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(skin.paper)
        }
        page.addView(buildHeader())
        page.addView(
            View(this).apply { setBackgroundColor(skin.cyan) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4))
        )

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(34))
        }
        page.addView(body)

        var section = ""
        Prefs.features.forEach { feature ->
            if (feature.section != section) {
                section = feature.section
                body.addView(
                    label(section.uppercase(), 10f, skin.cyanInk, medium = true).apply {
                        letterSpacing = 0.16f
                    },
                    matchWrap(top = 20)
                )
            }
            body.addView(featureRow(feature), matchWrap(top = 8))
        }

        status = label("", 12f, skin.muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        body.addView(status, matchWrap(top = 22))

        body.addView(sectionLabel("Housekeeping"), matchWrap(top = 26))
        body.addView(action("Save a diagnostics report now") { saveReport() }, matchWrap(top = 8))
        body.addView(action("Clear downloaded update files") { clearUpdates() }, matchWrap(top = 8))
        body.addView(action("Reset every switch to its default") { reset() }, matchWrap(top = 8))

        body.addView(
            label(
                "Version " + versionName() + " · silent installs " +
                    if (AdbWireless.isPaired(this)) "ready" else "not paired yet",
                11.5f,
                skin.muted
            ),
            matchWrap(top = 20)
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(skin.paper)
            isFillViewport = true
            addView(page)
        })

        updateStatus("Switches apply immediately.")
    }

    // ---- pieces ----

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
        box.addView(
            label("POCOCAST", 9.5f, skin.color(R.color.on_navy_muted), medium = true).apply {
                letterSpacing = 0.2f
            }
        )
        box.addView(
            label("Extras", 24f, skin.color(R.color.on_navy), medium = true).apply {
                setPadding(0, dp(6), 0, 0)
            }
        )
        box.addView(
            label(
                "Everything here is optional. Off means PocoCast behaves exactly as it did before this screen existed.",
                12.5f,
                skin.color(R.color.on_navy_meta)
            ).apply { setLineSpacing(dp(4).toFloat(), 1f); setPadding(0, dp(8), 0, 0) }
        )
        box.addView(
            pill("Back to casting") { finish() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
                .apply { topMargin = dp(16) }
        )
        return box
    }

    private fun sectionLabel(text: String): TextView =
        label(text.uppercase(), 10f, skin.cyanInk, medium = true).apply { letterSpacing = 0.16f }

    private fun featureRow(feature: Prefs.Feature): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(
                skin.card,
                dp(12).toFloat(),
                skin.line,
                dp(1).toFloat()
            )
            setPadding(dp(14), dp(12), dp(12), dp(12))
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(feature.title, 14f, skin.ink, medium = true))
        text.addView(
            label(feature.summary, 11.5f, skin.muted).apply {
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(4), 0, 0)
            }
        )
        card.addView(
            text,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val toggle = Switch(this).apply {
            isChecked = Prefs.isOn(this@SettingsActivity, feature.key)
            setOnCheckedChangeListener { _, checked ->
                Prefs.set(this@SettingsActivity, feature.key, checked)
                if (checked && feature.key == "update.notify") askNotificationPermission()
                if (checked && feature.key == "diag.crash") {
                    CrashGuard.install(this@SettingsActivity)
                }
                updateStatus(feature.title + if (checked) " on" else " off")
            }
        }
        card.addView(
            toggle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        )
        return card
    }

    private fun action(text: String, onClick: () -> Unit): View =
        label(text, 13f, skin.ink, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = ripple(
                rounded(skin.card, dp(12).toFloat(), skin.line, dp(1).toFloat()),
                skin.color(R.color.ripple)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun pill(text: String, onClick: () -> Unit) =
        label(text, 12.5f, skin.color(R.color.cta_text), medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = ripple(
                rounded(skin.color(R.color.cyan), dp(999).toFloat()),
                skin.color(R.color.ripple_cta)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun matchWrap(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    // ---- actions ----

    private fun saveReport() {
        val file = runCatching { Diag.writeReport(this, "exported from Extras") }.getOrNull()
        val path = file?.let { Diag.saveToDownloads(this, it) }
        updateStatus(
            if (path == null) "Could not write the report."
            else "Saved to " + path
        )
    }

    private fun clearUpdates() {
        val dir = File(cacheDir, "updates")
        val freed = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        updateStatus(
            if (removed) "Cleared " + (freed / 1024 / 1024) + " MB of downloaded updates."
            else "Nothing to clear."
        )
    }

    private fun reset() {
        Prefs.resetAll(this)
        recreate()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71)
    }

    private fun updateStatus(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun versionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrDefault("?")

    @Suppress("unused")
    private fun openLink(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
