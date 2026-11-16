package com.pococast

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last line of defence: if something throws on a thread nobody guarded, write
 * the trace into Downloads before the process dies, then let Android show its
 * own "app stopped" dialog.
 */
object CrashGuard {

    fun install(context: Context) {
        if (!Prefs.isOn(context, "diag.crash")) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
        Diag.log("crash: handler armed")
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("PocoCast crash report")
            appendLine("when: " + stamp)
            appendLine("thread: " + thread.name)
            appendLine("app: " + runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.versionName + " (" + info.longVersionCode + ")"
            }.getOrDefault("unknown"))
            appendLine()
            appendLine(error.stackTraceToString())
            appendLine()
            appendLine("-- recent steps --")
            appendLine(Diag.trace())
        }
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "pococast-crash.txt")
        file.writeText(text)
        Diag.saveToDownloads(context, file, "pococast-crash.txt")
    }
}
