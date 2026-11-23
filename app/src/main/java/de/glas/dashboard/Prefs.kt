package de.glas.dashboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Todo(val text: String, val done: Boolean)

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
    fun addFocus(c: Context, day: Long) = sp(c).edit().putInt("focus_$day", focusCount(c, day) + 1).apply()

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
