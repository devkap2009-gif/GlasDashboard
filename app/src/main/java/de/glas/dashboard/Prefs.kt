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
