package de.glas.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** Brücke zu Claude (Standard) oder Gemini – ohne API-Schlüssel. */
object AiBridge {
    const val CLAUDE_PKG = "com.anthropic.claude"

    /** 0 = Claude, 1 = Gemini */
    fun provider(c: Context) = Prefs.int(c, "ai_provider", 0)
    fun setProvider(c: Context, v: Int) = Prefs.setInt(c, "ai_provider", v)
    fun name(c: Context) = if (provider(c) == 0) "Claude" else "Gemini"

    /** Panel schließen und die Frage an die gewählte KI geben. */
    fun ask(c: Context, text: String?, close: () -> Unit) {
        close()
        Handler(Looper.getMainLooper()).postDelayed({
            if (provider(c) == 0) openClaude(c, text) else AssistActivity.open(c, text)
        }, 300)
    }

    /** Den eingestellten Standard-Assistenten als Overlay öffnen (Claude, wenn er als Assistent gesetzt ist). */
    fun overlay(c: Context, text: String?, close: () -> Unit) {
        close()
        Handler(Looper.getMainLooper()).postDelayed({ AssistActivity.open(c, text) }, 300)
    }

    fun claudeInstalled(c: Context) = runCatching { c.packageManager.getLaunchIntentForPackage(CLAUDE_PKG) != null }.getOrDefault(false)

    private fun openClaude(c: Context, text: String?) {
        if (!text.isNullOrBlank()) {
            runCatching { c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Frage an Claude", text)) }
            // Text direkt in einen neuen Claude-Chat geben
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .setPackage(CLAUDE_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { c.startActivity(send) }.isSuccess) return
        }
        val launch = runCatching { c.packageManager.getLaunchIntentForPackage(CLAUDE_PKG) }.getOrNull()
        if (launch != null && runCatching { c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        // Claude nicht installiert → Play Store
        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$CLAUDE_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$CLAUDE_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Einstellungen für den Standard-Assistenten öffnen. */
    fun openAssistantSettings(c: Context, close: () -> Unit) {
        close()
        val ok = runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
