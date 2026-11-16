package com.pococast

import android.app.Application

/**
 * Process-wide setup: the crash handler, the update channel, and the optional
 * launch-time update check. Everything it starts is guarded so a failure here
 * can never stop the app from opening.
 */
class PocoCastApp : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { Notify.channel(this) }
        runCatching { CrashGuard.install(this) }
        if (!Prefs.isOn(this, "update.check_launch")) return
        Thread {
            runCatching { UpdateWatch.run(this) }
        }.apply { isDaemon = true; name = "pococast-update-watch" }.start()
    }
}
