package com.pococast

import android.content.Context

/**
 * Optional behaviour, all of it off-by-request. Every switch on the extras
 * screen is one entry here, so the screen, the defaults and the code that reads
 * a setting never drift apart.
 */
object Prefs {

    private const val FILE = "pococast-settings"

    data class Feature(
        val key: String,
        val section: String,
        val title: String,
        val summary: String,
        val default: Boolean
    )

    val features = listOf(
        Feature(
            "update.notify", "Updates",
            "Notify me about new versions",
            "Posts a notification instead of only showing the card in the app.", true
        ),
        Feature(
            "update.auto", "Updates",
            "Install updates on their own",
            "Downloads and installs a new version on launch when the phone allows it.", false
        ),
        Feature(
            "update.wifi_only", "Updates",
            "Only download updates on Wi-Fi",
            "Skips the download on mobile data so a big APK cannot eat a data plan.", true
        ),
        Feature(
            "diag.device_info", "Diagnostics",
            "Include phone and network details",
            "Adds the model, Android version and Wi-Fi details to reports.", true
        ),
        Feature(
            "diag.logcat", "Diagnostics",
            "Include the system log",
            "Adds this process's logcat lines, which is where AirPlay statuses show up.", true
        ),
        Feature(
            "diag.redact", "Diagnostics",
            "Strip tokens from URLs",
            "Keeps the shape of a media URL but drops its query string.", true
        ),
        Feature(
            "diag.crash", "Diagnostics",
            "Write a report if PocoCast crashes",
            "Saves Download/pococast-crash.txt with the stack trace on the way down.", true
        ),
        Feature(
            "update.check_launch", "Updates",
            "Check for updates when PocoCast opens",
            "Looks for a newer release on launch; off means only the in-app check runs.", true
        ),
        Feature(
            "extra.quickcast", "Shortcuts",
            "Cast the copied link shortcut",
            "Adds a long-press shortcut that opens PocoCast with whatever link is on the clipboard.", true
        ),
        Feature(
            "extra.tile", "Shortcuts",
            "Quick Settings tile",
            "Shows a PocoCast tile in the pull-down shade for one-tap access.", true
        ),
        Feature(
            "extra.tidy_cache", "Housekeeping",
            "Clean old downloads on launch",
            "Deletes cached casts and stale update files every time the app starts.", false
        )
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isOn(context: Context, key: String): Boolean {
        val feature = features.firstOrNull { it.key == key } ?: return false
        return prefs(context).getBoolean(key, feature.default)
    }

    fun set(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
        Diag.log("settings: " + key + "=" + value)
    }

    /** Handy for screens that want to show what changed. */
    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
        Diag.log("settings: everything back to defaults")
    }
}
