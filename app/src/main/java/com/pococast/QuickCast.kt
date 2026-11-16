package com.pococast

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible entry point behind the "Cast copied link" shortcut: takes whatever
 * is on the clipboard, hands it to the main screen as if it had been shared,
 * and gets out of the way. Nothing is cast until the user taps Cast there.
 */
class QuickCast : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs.isOn(this, "extra.quickcast")) {
            Toast.makeText(this, "The copied-link shortcut is off in Extras.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val copied = runCatching {
            val manager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        }.getOrNull()

        if (copied != null && copied.startsWith("http", ignoreCase = true) && copied.length < 4096) {
            Diag.log("ui: quick cast picked up a copied link")
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, copied)
            )
        } else {
            Toast.makeText(this, "Copy a link first, then use this shortcut.", Toast.LENGTH_LONG)
                .show()
        }
        finish()
    }
}
