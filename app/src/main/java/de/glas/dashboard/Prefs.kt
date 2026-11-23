package de.glas.dashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Todo(val text: String, val done: Boolean)
data class Note(val id: Long, val title: String, val body: String, val updated: Long)

/** Kleine Hülle um SharedPreferences. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("dashboard", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    /** Höhe der unsichtbaren Wischzone oben, in dp. */
    fun stripHeight(c: Context) = sp(c).getInt("strip_height", 32)
    fun setStripHeight(c: Context, v: Int) = sp(c).edit().putInt("strip_height", v).apply()

    fun showHandle(c: Context) = sp(c).getBoolean("show_handle", true)
    fun setShowHandle(c: Context, v: Boolean) = sp(c).edit().putBoolean("show_handle", v).apply()

    /** 0 = Zwei-Finger-Wisch, 1 = Gedrückt halten, 2 = beides */
    fun openMode(c: Context) = sp(c).getInt("open_mode", 2)
    fun setOpenMode(c: Context, v: Int) = sp(c).edit().putInt("open_mode", v).apply()

    /** 0 = oben, 1 = rechter Rand, 2 = linker Rand */
    fun position(c: Context) = sp(c).getInt("position", 0)
    fun setPosition(c: Context, v: Int) = sp(c).edit().putInt("position", v).apply()

    /** Verschiebung, damit man mit „Weiter“ durch Zitate/Begriffe blättern kann */
    fun offset(c: Context, key: String) = sp(c).getInt("off_$key", 0)
    fun setOffset(c: Context, key: String, v: Int) = sp(c).edit().putInt("off_$key", v).apply()

    /** Fokus-Sitzungen pro Tag (Schlüssel = Tagesnummer) */
    fun focusCount(c: Context, day: Long) = sp(c).getInt("focus_$day", 0)
    fun addFocus(c: Context, day: Long) = sp(c).edit().putInt("focus_$day", focusCount(c, day) + 1).putInt("focus_total", focusTotal(c) + 1).apply()

    /** Gedankenbuch: Antworten auf die Denkfrage */
    fun journal(c: Context): MutableList<Triple<Long, String, String>> {
        val raw = sp(c).getString("journal", null) ?: return mutableListOf()
        return try {
            val a = JSONArray(raw)
            MutableList(a.length()) { i ->
                val o = a.getJSONObject(i)
                Triple(o.optLong("t"), o.optString("q"), o.optString("a"))
            }
        } catch (e: Exception) { mutableListOf() }
    }

    fun addJournal(c: Context, q: String, answer: String) {
        val list = journal(c)
        list.add(0, Triple(System.currentTimeMillis(), q, answer))
        val a = JSONArray()
        list.take(200).forEach { a.put(JSONObject().put("t", it.first).put("q", it.second).put("a", it.third)) }
        sp(c).edit().putString("journal", a.toString()).apply()
    }

    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    fun profile(c: Context, k: String) = sp(c).getString("prof_$k", "") ?: ""
    fun setProfile(c: Context, k: String, v: String) = sp(c).edit().putString("prof_$k", v).apply()

    fun focusTotal(c: Context) = sp(c).getInt("focus_total", 0)

    fun pageOrder(c: Context): List<String> =
        (sp(c).getString("page_order", null) ?: "start,logic,impl,games,notes,profile").split(',').filter { it.isNotBlank() }
    fun setPageOrder(c: Context, keys: List<String>) = sp(c).edit().putString("page_order", keys.joinToString(",")).apply()

    /** Griff-Position in Pixeln (y), -1 = Standard */
    fun handleY(c: Context) = sp(c).getInt("handle_y", -1)
    fun setHandleY(c: Context, v: Int) = sp(c).edit().putInt("handle_y", v).apply()

    fun notes(c: Context): List<Note> {
        val raw = sp(c).getString("notes", null)
        val list = mutableListOf<Note>()
        if (raw == null) {
            // alte Einzel-Notiz übernehmen
            val old = note(c)
            if (old.isNotBlank()) list += Note(1L, "Notiz", old, System.currentTimeMillis())
            return list
        }
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list += Note(o.optLong("id"), o.optString("title"), o.optString("body"), o.optLong("updated"))
            }
        } catch (e: Exception) {}
        return list.sortedByDescending { it.updated }
    }

    private fun writeNotes(c: Context, list: List<Note>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("body", it.body).put("updated", it.updated)) }
        sp(c).edit().putString("notes", a.toString()).apply()
    }

    fun saveNote(c: Context, n: Note) {
        val list = notes(c).filter { it.id != n.id }.toMutableList()
        list.add(0, n)
        writeNotes(c, list)
    }

    fun deleteNote(c: Context, id: Long) = writeNotes(c, notes(c).filter { it.id != id })

    fun note(c: Context) = sp(c).getString("note", "") ?: ""
    fun setNote(c: Context, v: String) = sp(c).edit().putString("note", v).apply()

    fun todos(c: Context): MutableList<Todo> {
        val raw = sp(c).getString("todos", null)
            ?: return mutableListOf(Todo("Wasser trinken", false), Todo("Mails checken", true))
        return try {
            val a = JSONArray(raw)
            MutableList(a.length()) { i ->
                val o = a.getJSONObject(i)
                Todo(o.optString("t"), o.optBoolean("d"))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun setTodos(c: Context, list: List<Todo>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.text).put("d", it.done)) }
        sp(c).edit().putString("todos", a.toString()).apply()
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.statusBarHeight(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
}
