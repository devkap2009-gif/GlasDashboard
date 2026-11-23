package de.glas.dashboard

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Kachel für die Schnelleinstellungen: Zwei Finger von ganz oben nach unten
 * öffnen bei Android direkt die Schnelleinstellungen – ein Tipp auf „Dashboard“
 * schließt sie und zeigt das Glas-Panel.
 */
class DashboardTile : TileService() {

    override fun onStartListening() {
        qsTile?.apply {
            state = if (OverlayService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val intent = Intent(this, OpenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
