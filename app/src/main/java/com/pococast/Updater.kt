package com.pococast

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Self-update against GitHub releases: read the newest tagged release, fetch the
 * APK it carries and install it.
 *
 * A normal app cannot install packages on its own, so the install runs through
 * the first privileged channel it can find — Shizuku (an ADB shell the user
 * starts once, no root) or a rooted su — and that path replaces the running app
 * with no installer screen at all. Only when neither is available does it fall
 * back to the system "Install" prompt.
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
     * Installs [apk]: silently through Shizuku or root when one of them is
     * available, otherwise by opening the system installer. Returns true when
     * progress can stop — the install is either running or handed off.
     */
    fun install(context: Context, apk: File): Boolean {
        if (shizukuAllowed() || hasRoot()) {
            installInBackground(context.applicationContext, apk)
            return true
        }
        if (shizukuReachable()) {
            pendingContext = context.applicationContext
            pendingApk = apk
            if (!listenerAdded) {
                runCatching { Shizuku.addRequestPermissionResultListener(shizukuResult) }
                listenerAdded = true
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                requestShizukuPermission(SHIZUKU_CODE)
            } else {
                post { requestShizukuPermission(SHIZUKU_CODE) }
            }
            return true
        }
        return promptInstaller(context, apk)
    }

    /** Runs the privileged install off the UI thread and falls back on failure. */
    private fun installInBackground(context: Context, apk: File) {
        Thread {
            val error = installSilently(apk)
            if (error != null) {
                Diag.log("update: falling back to the installer: " + error)
                post { promptInstaller(context, apk) }
            }
        }.apply { isDaemon = true; start() }
    }

    /** The plain route: let Android ask the user to confirm the package. */
    private fun promptInstaller(context: Context, apk: File): Boolean {
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

    // ---- silent install ----

    private const val SHIZUKU_CODE = 4711

    private var pendingContext: Context? = null
    private var pendingApk: File? = null
    private var listenerAdded = false

    /** Shizuku answers its permission dialog here, not through onActivityResult. */
    private val shizukuResult = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            if (requestCode != SHIZUKU_CODE) return
            Diag.log("update: Shizuku permission result=" + grantResult)
            val apk = pendingApk ?: return
            val context = pendingContext ?: return
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                installInBackground(context, apk)
            } else {
                post { promptInstaller(context, apk) }
            }
        }
    }

    private fun post(block: () -> Unit) {
        runCatching { Handler(Looper.getMainLooper()).post(block) }
    }

    fun shizukuReachable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuAllowed(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun requestShizukuPermission(code: Int) {
        Diag.log("update: asking Shizuku for shell permission")
        runCatching { Shizuku.requestPermission(code) }
            .onFailure { Diag.log("update: Shizuku permission request failed: " + it.message) }
    }

    fun hasRoot(): Boolean {
        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su")
        return su.any { File(it).exists() }
    }

    /** True when an update can be installed without the system installer screen. */
    fun canInstallSilently(): Boolean = shizukuAllowed() || hasRoot()

    /**
     * Installs [apk] over the running app. Returns null on success, otherwise
     * the reason it could not run. Blocking; call it off the main thread.
     */
    fun installSilently(apk: File): String? {
        if (!apk.exists() || apk.length() == 0L) return "update file missing"
        if (shizukuAllowed()) {
            val error = runInstall(apk, viaShizuku = true)
            if (error == null) return null
            Diag.log("update: Shizuku install failed: " + error)
            if (!hasRoot()) return error
        }
        if (hasRoot()) {
            val error = runInstall(apk, viaShizuku = false)
            if (error == null) return null
            Diag.log("update: root install failed: " + error)
            return error
        }
        return "no silent install channel"
    }

    /** `newProcess` is private in the Shizuku API, so the shell is reached by reflection. */
    private fun shizukuProcess(command: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(null, command, null, null) as Process
    }

    /**
     * Pipes the APK into `pm install -S <bytes>` as the privileged user, so the
     * package is replaced in place and the app restarts by itself.
     */
    private fun runInstall(apk: File, viaShizuku: Boolean): String? {
        val command = arrayOf("sh", "-c", "pm install -r -S " + apk.length())
        val process = try {
            if (viaShizuku) {
                shizukuProcess(command)
            } else {
                ProcessBuilder("su", "-c", "pm install -r -S " + apk.length())
                    .redirectErrorStream(true)
                    .start()
            }
        } catch (t: Throwable) {
            return t.javaClass.simpleName + ": " + t.message
        }
        val output = StringBuilder()
        val drain = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
            }
        }.apply { isDaemon = true; start() }
        try {
            process.outputStream.use { out ->
                apk.inputStream().use { input -> input.copyTo(out) }
            }
        } catch (t: Throwable) {
            Diag.log("update: could not stream the apk: " + t.message)
        }
        val finished = runCatching { process.waitFor(180, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroy() }
            return "install timed out"
        }
        runCatching { drain.join(4000) }
        val text = output.toString().trim()
        val code = runCatching { process.exitValue() }.getOrDefault(-1)
        Diag.log("update: silent install exit=" + code + " " + text.takeLast(400))
        return if (code == 0 && !text.contains("Failure", ignoreCase = true)) null
        else text.ifBlank { "installer exit " + code }
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
