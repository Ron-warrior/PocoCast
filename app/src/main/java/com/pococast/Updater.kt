package com.pococast

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update against GitHub releases: read the newest tagged release, fetch the
 * APK it carries and hand it to Android's installer.
 *
 * A sideloaded app can never install silently, so the last step is always the
 * system "Install" prompt — this just removes the browser and file manager
 * detour before it.
 */
object Updater {

    private const val API = "https://api.github.com/repos/Ron-warrior/PocoCast/releases/latest"
    private const val AGENT = "PocoCast-Android"

    data class Release(val version: String, val url: String, val size: Long)

    fun installedVersion(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0"

    /** Blocking; returns null when up to date, offline or the release is unreadable. */
    fun check(context: Context): Release? {
        val body = try {
            get(API)
        } catch (t: Throwable) {
            Diag.log("update: check failed: " + t.message)
            return null
        }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val version = json.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty()) return null
        if (!isNewer(version, installedVersion(context))) {
            Diag.log("update: up to date (" + installedVersion(context) + ")")
            return null
        }
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (url.isNotEmpty() && name.endsWith(".apk", ignoreCase = true)) {
                val size = asset.optLong("size")
                Diag.log("update: found " + version + " asset=" + name + " " + size + "B")
                return Release(version, url, size)
            }
        }
        return null
    }

    /** Blocking download into the app cache; [onProgress] gets 0–100. */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "PocoCast-" + release.version + ".apk")
        if (apk.exists() && release.size > 0 && apk.length() == release.size) return apk
        apk.delete()

        val connection = open(release.url)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("download failed: HTTP " + code)
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        val percent = if (total > 0) ((done * 100) / total).toInt() else 0
                        if (percent != last) {
                            last = percent
                            onProgress(percent)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        Diag.log("update: downloaded " + apk.length() + "B to " + apk.name)
        return apk
    }

    /**
     * Opens the system installer. Returns false when Android still needs the
     * user to allow installs from PocoCast — in that case Settings was opened.
     */
    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + context.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            Diag.log("update: install permission missing, opened settings")
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
        Diag.log("update: installer opened for " + apk.name)
        return true
    }

    // ---- plumbing ----

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", AGENT)
        }

    private fun get(url: String): String {
        val connection = open(url)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP " + code)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left > right
        }
        return false
    }
}
