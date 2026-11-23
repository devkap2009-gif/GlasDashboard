package de.glas.dashboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Unsichtbare Activity, die den Standard-Assistenten (Gemini-Overlay) über dem
 * aktuellen Bildschirm öffnet. Optional liegt vorher eine Frage in der Zwischenablage.
 */
class AssistActivity : Activity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let { t ->
            runCatching {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Frage an Gemini", t))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (started) { finish(); return }
        started = true
        // 1. offizieller Weg: Assistent über dieser Activity zeigen (wie langes Drücken der Ein-Taste)
        val ok = runCatching { showAssist(Bundle()) }.getOrDefault(false) ||
            runCatching { startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false) ||
            runCatching { startActivity(Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, if (ok) 700 else 0)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        fun open(ctx: android.content.Context, text: String?) {
            ctx.startActivity(Intent(ctx, AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra("text", text))
        }
    }
}
