package de.glas.dashboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** Unsichtbar: startet den Dienst (falls nötig) und öffnet das Dashboard. */
class OpenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            OverlayService.start(this, OverlayService.ACTION_OPEN)
        } else {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
