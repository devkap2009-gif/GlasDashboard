package de.glas.dashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Startet den Dienst nach einem Neustart wieder, wenn er vorher an war. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.enabled(context) && Settings.canDrawOverlays(context)) {
            runCatching { OverlayService.start(context) }
        }
    }
}
