package com.pococast

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything needed to explain a failed cast: our own trace, the AirPlay
 * exchanges the sender library writes to logcat, the controller's last status,
 * and the phone's environment. None of this can be reproduced without the
 * user's TV, so it is written to a file they can send back.
 */
object Diag {

    private const val TAG = "PocoCast"
    private const val MAX_LINES = 3000
    private const val STAMP = "yyyy-MM-dd HH:mm:ss"

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private val stacks = ArrayDeque<String>()
    private val lock = Any()

    fun reset() {
        synchronized(lock) {
            lines.clear()
            stacks.clear()
        }
        log("---- new cast attempt ----")
    }

    fun log(message: String) {
        val line = clock.format(Date()) + " " + Thread.currentThread().name + ": " + message
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        android.util.Log.i(TAG, message)
    }

    fun error(message: String, cause: Throwable? = null) {
        log("ERROR $message" + (cause?.let { " -- " + it.javaClass.name + ": " + it.message } ?: ""))
        if (cause != null) {
            synchronized(lock) {
                stacks.addLast(cause.stackTraceToString())
                while (stacks.size > 20) stacks.removeFirst()
            }
        }
    }

    fun writeReport(context: Context, about: String): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")
        if (!dir.exists()) dir.mkdirs()
        val name = "pococast-diag-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".txt"
        val file = File(dir, name)
        file.writeText(report(context, about))
        return file
    }

    /**
     * Copies the report into the phone's Downloads folder through MediaStore,
     * so it is visible in the Files app without any storage permission.
     * Returns the visible path, or null if the copy failed.
     */
    fun saveToDownloads(context: Context, file: File): String? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        Environment.DIRECTORY_DOWNLOADS + "/" + file.name
    }.getOrNull()

    // ---- report ----

    private fun report(context: Context, about: String): String {
        val out = StringBuilder()
        out.appendLine("PocoCast diagnostics")
        out.appendLine("generated: " + SimpleDateFormat(STAMP, Locale.US).format(Date()))
        out.appendLine("app: " + versionOf(context))
        out.appendLine(
            "device: " + Build.MANUFACTURER + " " + Build.MODEL +
                " / Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
        )
        out.appendLine("abis: " + Build.SUPPORTED_ABIS.joinToString())
        out.appendLine("network: " + network(context))
        out.appendLine("interfaces: " + interfaces())
        out.appendLine()
        out.appendLine("== cast context ==")
        out.appendLine(about.ifBlank { "none" })
        out.appendLine()
        out.appendLine("== trace (our steps) ==")
        synchronized(lock) { lines.forEach { out.appendLine(it) } }
        out.appendLine()
        if (synchronized(lock) { stacks.isNotEmpty() }) {
            out.appendLine("== exceptions ==")
            synchronized(lock) { stacks.forEach { out.appendLine(it); out.appendLine() } }
        }
        out.appendLine("== logcat (this process; AirPlay SETUP//command statuses live here) ==")
        out.appendLine(logcat())
        return redact(out.toString())
    }

    private fun versionOf(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.versionName + " (" + info.longVersionCode + ")"
    }.getOrDefault("unknown")

    private fun network(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val transport = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        transport + " validated=" +
            (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false)
    }.getOrElse { "unavailable: " + it.javaClass.simpleName }

    private fun interfaces(): String = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<java.net.Inet4Address>()
                    .map { nic.name + "=" + it.hostAddress }
            }
            .joinToString(", ")
            .ifBlank { "none" }
    }.getOrElse { "unavailable" }

    private fun logcat(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val pid = Process.myPid().toString()
        val kept = text.lineSequence().filter { line ->
            val parts = line.trim().split(Regex("\\s+"))
            parts.size >= 5 && parts[2] == pid
        }.toList()
        if (kept.isEmpty()) "no logcat lines for pid $pid (the trace above still applies)"
        else kept.joinToString("\n")
    }.getOrElse { "logcat unavailable: " + it.javaClass.simpleName + ": " + it.message }

    /** Media URLs carry short-lived tokens; keep the shape, drop the query. */
    private fun redact(text: String): String =
        Regex("(https?://[^\\s?]+)\\?[^\\s]*").replace(text) { it.groupValues[1] + "?<query stripped>" }
}
