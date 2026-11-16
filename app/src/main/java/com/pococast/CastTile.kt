package com.pococast

import android.content.Intent
import android.service.quicksettings.TileService

/** Pull-down tile: one tap back into PocoCast from anywhere. */
class CastTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "PocoCast"
            state = android.service.quicksettings.Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivityAndCollapse(intent) }
    }
}
