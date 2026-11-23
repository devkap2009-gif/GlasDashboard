package de.glas.dashboard

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Eigenes Profil ansehen und bearbeiten, Bereiche anordnen. */
class ProfilePage(
    private val ui: Ui,
    private val pageTitles: () -> List<Pair<String, String>>,   // key to title, in aktueller Reihenfolge
    private val onOrderChanged: (List<String>) -> Unit,
    private val onAccent: (Int) -> Unit
) : Page {
    override val key = "profile"
    override val title = "Profil"

    private val ctx = ui.ctx
    private val col = ui.column()
    override val view: View = ui.page(col)
    private var edit = false

    private val fields = listOf(
        Triple("name", "Name", 1),
        Triple("role", "Rolle / Titel", 1),
        Triple("bio", "Über mich", 4),
        Triple("interests", "Interessen (mit Komma trennen)", 1),
        Triple("reading", "Lese gerade", 1),
        Triple("goals", "Ziele", 4)
    )

    init { render() }

    override fun onShow() { if (!edit) render() }

    private fun render() {
        col.removeAllViews()
        if (edit) editView() else showView()
        accentTile()
        orderTile()
    }

    private fun showView() {
        val t = ui.tile()
        val name = Prefs.profile(ctx, "name")
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(ctx).apply {
            text = initials(name); gravity = Gravity.CENTER; setTextColor(Holo.NAVY); typeface = Holo.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            background = ui.cut(Holo.ICE, Holo.ICE, 14f)
        }, LinearLayout.LayoutParams(ui.dp(76), ui.dp(76)).apply { marginEnd = ui.dp(16) })
        val texts = ui.column()
        texts.addView(ui.title(name.ifBlank { "Noch kein Name" }).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f) })
        texts.addView(ui.label(Prefs.profile(ctx, "role").ifBlank { "Rolle / Titel" }), ui.lpMatch(top = 4))
        row.addView(texts, ui.weight())
        t.addView(row)

        fun section(key: String, label: String) {
            val v = Prefs.profile(ctx, key)
            if (v.isBlank()) return
            t.addView(ui.hline(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(1)).apply { topMargin = ui.dp(14) })
            t.addView(ui.label(label), ui.lpMatch(top = 12))
            if (key == "interests") {
                val wrap = ui.column()
                var line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                var count = 0
                v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { tag ->
                    if (count == 3) { wrap.addView(line, ui.lpMatch(top = 6)); line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }; count = 0 }
                    line.addView(TextView(ctx).apply {
                        text = tag; setTextColor(Holo.ICE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
                        background = ui.cut(Holo.PANEL_DEEP, Holo.LINE, 8f)
                    }, ui.lpWrap(end = 6))
                    count++
                }
                wrap.addView(line, ui.lpMatch(top = 6))
                t.addView(wrap)
            } else t.addView(ui.body(v), ui.lpMatch(top = 6))
        }
        section("bio", "Über mich")
        section("interests", "Interessen")
        section("reading", "Lese gerade")
        section("goals", "Ziele")

        // Statistik
        t.addView(ui.hline(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(1)).apply { topMargin = ui.dp(14) })
        val stats = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun stat(n: String, l: String) = ui.column().apply {
            addView(ui.big(n)); addView(ui.small(l))
        }
        stats.addView(stat("${Prefs.focusTotal(ctx)}", "Fokus-Sitzungen"), ui.weight())
        stats.addView(stat("${Prefs.notes(ctx).size}", "Notizen"), ui.weight())
        stats.addView(stat("${Prefs.todos(ctx).count { it.done }}", "Erledigt"), ui.weight())
        t.addView(stats, ui.lpMatch(top = 12))

        t.addView(ui.pill("Profil bearbeiten", true) { edit = true; render() }, ui.lpWrap(top = 14))
        col.addView(t)
    }

    private fun editView() {
        val t = ui.tile()
        t.addView(ui.label("Profil bearbeiten"))
        val inputs = mutableMapOf<String, EditText>()
        fields.forEach { (key, label, lines) ->
            t.addView(ui.small(label), ui.lpMatch(top = 12))
            val e = ui.input(label, lines = lines).apply {
                setText(Prefs.profile(ctx, key))
                if (lines == 1) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            }
            inputs[key] = e
            t.addView(e, ui.lpMatch(top = 6))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(ui.pill("Speichern", true) {
            inputs.forEach { (k, e) -> Prefs.setProfile(ctx, k, e.text.toString().trim()) }
            edit = false; render()
        })
        row.addView(ui.pill("Abbrechen") { edit = false; render() }, ui.lpWrap(start = 8))
        t.addView(row, ui.lpMatch(top = 14))
        col.addView(t)
    }

    private fun accentTile() {
        val t = ui.tile()
        t.addView(ui.label("Akzentfarbe"))
        val current = Prefs.accent(ctx)
        var row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        Holo.presets.forEachIndexed { i, (name, c) ->
            if (i % 4 == 0 && i > 0) { t.addView(row, ui.lpMatch(top = 8)); row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL } }
            row.addView(ui.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(View(ctx).apply {
                    background = CutDrawable(ui.dpf(10f), c, if (c == current) Holo.WHITE else 0x33FFFFFF, ui.dpf(if (c == current) 2.5f else 1f), Holo.WHITE)
                    contentDescription = name
                    setOnClickListener { onAccent(c) }
                }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(44)))
                addView(ui.small(name).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); gravity = Gravity.CENTER })
            }, ui.weight())
        }
        t.addView(row, ui.lpMatch(top = 8))
        // eigene Farbe
        val hex = ui.input("#RRGGBB").apply { setText(String.format("#%06X", current and 0xFFFFFF)) }
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(hex, ui.weight())
        val err = ui.small("")
        r.addView(ui.pill("Übernehmen", true) {
            val txt = hex.text.toString().trim().removePrefix("#")
            val v = if (txt.length == 6) txt.toLongOrNull(16) else null
            if (v == null) err.text = "Bitte 6 Hex-Stellen eingeben, z. B. #7FDBFF"
            else onAccent((0xFF000000 or v).toInt())
        }, ui.lpWrap(start = 8))
        t.addView(ui.small("Eigene Farbe (Hex)"), ui.lpMatch(top = 14))
        t.addView(r, ui.lpMatch(top = 6))
        t.addView(err)
        col.addView(t)
    }

    private fun orderTile() {
        val t = ui.tile()
        t.addView(ui.label("Bereiche anordnen"))
        t.addView(ui.small("Reihenfolge, in der du nach links und rechts wischst."), ui.lpMatch(top = 4))
        val order = pageTitles()
        order.forEachIndexed { i, (key, title) ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ui.body("${i + 1}.  $title", 16f), ui.weight())
            fun move(d: Int) {
                val keys = order.map { it.first }.toMutableList()
                val j = i + d
                if (j !in keys.indices) return
                keys[i] = keys[j].also { keys[j] = keys[i] }
                onOrderChanged(keys)
                render()
            }
            row.addView(ui.pill("↑") { move(-1) }.apply { alpha = if (i == 0) 0.3f else 1f })
            row.addView(ui.pill("↓") { move(1) }.apply { alpha = if (i == order.size - 1) 0.3f else 1f }, ui.lpWrap(start = 6))
            t.addView(row, ui.lpMatch(top = 8))
        }
        col.addView(t)
    }
}
