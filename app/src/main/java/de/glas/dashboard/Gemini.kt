package de.glas.dashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Direkter Zugriff auf die Gemini-API – ohne die Gemini-App zu öffnen. */
object Gemini {
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    private fun sp(c: Context) = c.getSharedPreferences("gemini", Context.MODE_PRIVATE)
    fun key(c: Context) = sp(c).getString("key", "") ?: ""
    fun setKey(c: Context, v: String) = sp(c).edit().putString("key", v.trim()).apply()
    fun model(c: Context) = (sp(c).getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL).ifBlank { DEFAULT_MODEL }
    fun setModel(c: Context, v: String) = sp(c).edit().putString("model", v.trim()).apply()

    data class Msg(val user: Boolean, val text: String)

    fun history(c: Context): MutableList<Msg> = try {
        val a = JSONArray(sp(c).getString("chat", "[]"))
        MutableList(a.length()) { i -> a.getJSONObject(i).let { Msg(it.optBoolean("u"), it.optString("t")) } }
    } catch (e: Exception) { mutableListOf() }

    fun saveHistory(c: Context, h: List<Msg>) {
        val a = JSONArray()
        h.takeLast(60).forEach { a.put(JSONObject().put("u", it.user).put("t", it.text)) }
        sp(c).edit().putString("chat", a.toString()).apply()
    }

    class GeminiError(msg: String) : Exception(msg)

    /** Blockierend – nur im Hintergrund-Thread aufrufen. */
    fun generate(c: Context, messages: List<Msg>, system: String? = null, json: Boolean = false): String {
        val key = key(c)
        if (key.isBlank()) throw GeminiError("Kein API-Schlüssel eingetragen.")
        val body = JSONObject()
        val contents = JSONArray()
        messages.forEach { m ->
            contents.put(JSONObject().put("role", if (m.user) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
        }
        body.put("contents", contents)
        if (system != null) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        if (json) body.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${model(c)}:generateContent")
        val con = url.openConnection() as HttpURLConnection
        try {
            con.requestMethod = "POST"
            con.connectTimeout = 15000
            con.readTimeout = 90000
            con.doOutput = true
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            con.setRequestProperty("x-goog-api-key", key)
            con.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = con.responseCode
            val stream = if (code in 200..299) con.inputStream else con.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull()
                throw GeminiError(when (code) {
                    400 -> "Anfrage abgelehnt: ${msg ?: code}. Ist der API-Schlüssel richtig?"
                    401, 403 -> "Schlüssel ungültig oder ohne Berechtigung (${msg ?: code})."
                    404 -> "Modell „${model(c)}“ nicht gefunden. Trage unter Einstellungen ein anderes Modell ein."
                    429 -> "Kontingent erreicht – kurz warten und erneut versuchen."
                    else -> "Fehler $code: ${msg ?: text.take(200)}"
                })
            }
            val root = JSONObject(text)
            val cands = root.optJSONArray("candidates")
            if (cands == null || cands.length() == 0) {
                val block = root.optJSONObject("promptFeedback")?.optString("blockReason")
                throw GeminiError(if (!block.isNullOrBlank()) "Gemini hat die Anfrage blockiert ($block)." else "Keine Antwort erhalten.")
            }
            val parts = cands.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (p.optBoolean("thought")) continue
                sb.append(p.optString("text"))
            }
            return sb.toString().trim().ifEmpty { throw GeminiError("Leere Antwort.") }
        } catch (e: GeminiError) {
            throw e
        } catch (e: java.io.IOException) {
            throw GeminiError("Keine Verbindung: ${e.message}")
        } finally {
            con.disconnect()
        }
    }

    /** Einfaches Markdown für die Anzeige glätten. */
    fun plain(md: String): String = md
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[*-]\\s+"), "• ")
        .replace("`", "")
}
