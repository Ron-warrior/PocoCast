package com.pococast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * The launch-time half of the updater: look once, optionally shout, optionally
 * install without asking. Runs on its own thread from [PocoCastApp].
 */
object UpdateWatch {

    fun run(context: Context) {
        tidy(context)
        val release = runCatching { Updater.check(context) }.getOrNull() ?: return
        Diag.log("update: launch check found " + release.version)
        Notify.updateReady(context, release.version)
        if (!Prefs.isOn(context, "update.auto")) return
        if (!onWifi(context) && Prefs.isOn(context, "update.wifi_only")) {
            Diag.log("update: waiting for Wi-Fi before downloading")
            return
        }

        val apk = runCatching {
            Updater.download(context, release) { percent ->
                Diag.log("update: auto download " + percent + "%")
            }
        }.getOrNull() ?: return
        Diag.log("update: auto install starting for " + apk.name)
        val error = if (AdbWireless.isPaired(context)) {
            AdbWireless.install(context, apk)
        } else {
            Updater.installSilently(apk)
        }
        if (error != null) {
            Diag.log("update: auto install could not run: " + error)
        } else {
            Diag.log("update: auto install handed off")
        }
    }

    /** Optional housekeeping: cached casts and old update files do not pile up. */
    private fun tidy(context: Context) {
        if (!Prefs.isOn(context, "extra.tidy_cache")) return
        val cutoff = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        listOf("cast", "updates").forEach { name ->
            val dir = java.io.File(context.cacheDir, name)
            runCatching {
                dir.listFiles()?.forEach { file ->
                    if (file.lastModified() < cutoff) file.deleteRecursively()
                }
            }
        }
        Diag.log("update: cache housekeeping done")
    }

    private fun onWifi(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(true)
}
