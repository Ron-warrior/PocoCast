package com.pococast

import android.content.Intent
import android.service.quicksettings.TileService

/** Pull-down tile: one tap back into PocoCast from anywhere. */
class CastTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val on = Prefs.isOn(this, "extra.tile")
        qsTile?.apply {
            label = if (on) "PocoCast" else "PocoCast (off)"
            state = android.service.quicksettings.Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (!Prefs.isOn(this, "extra.tile")) {
            android.widget.Toast
                .makeText(this, "The PocoCast tile is off in Extras.", android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivityAndCollapse(intent) }
    }
}
