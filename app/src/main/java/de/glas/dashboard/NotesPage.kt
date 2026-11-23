package de.glas.dashboard

import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList

/** Notizen (beliebig viele, großer Editor) und Aufgaben. */
class NotesPage(private val ui: Ui) : Page {
    override val key = "notes"
    override val title = "Notizen"

    private val ctx = ui.ctx
    private val col = ui.column()
    override val view: View = ui.page(col)
    private var editing: Long? = null

    init { render() }

    private fun render() {
        col.removeAllViews()
        val id = editing
        if (id != null) editor(id) else list()
    }

    private fun list() {
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ui.label("Notizen"), ui.weight())
        head.addView(ui.pill("+ Neue Notiz", true) {
            val n = Note(System.currentTimeMillis(), "", "", System.currentTimeMillis())
            Prefs.saveNote(ctx, n); editing = n.id; render()
        })
        col.addView(head, ui.lpMatch(bottom = 10))

        val search = ui.input("Notizen durchsuchen …")
        col.addView(search, ui.lpMatch(bottom = 10))
        val listBox = ui.column()
        col.addView(listBox)

        fun fill(q: String) {
            listBox.removeAllViews()
            val notes = Prefs.notes(ctx).filter { q.isBlank() || it.title.contains(q, true) || it.body.contains(q, true) }
            if (notes.isEmpty()) {
                listBox.addView(ui.tile().apply { addView(ui.small(if (q.isBlank()) "Noch keine Notizen. Tippe auf „+ Neue Notiz“." else "Nichts gefunden.")) })
            }
            val fmt = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            notes.forEach { n ->
                listBox.addView(ui.tile().apply {
                    addView(ui.label(fmt.format(java.util.Date(n.updated))).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f) })
                    addView(ui.title(n.title.ifBlank { "Ohne Titel" }).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); ui.ellipsize(this, 1) }, ui.lpMatch(top = 4))
                    if (n.body.isNotBlank()) addView(ui.small(n.body).also { ui.ellipsize(it, 3) }, ui.lpMatch(top = 4))
                    setOnClickListener { editing = n.id; render() }
                })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fill(s?.toString() ?: "") }
        })
        fill("")

        // Aufgaben
        val t = ui.tile()
        t.addView(ui.label("Aufgaben"))
        val field = ui.input("Neue Aufgabe …")
        val box = ui.column()
        fun renderTodos() {
            box.removeAllViews()
            Prefs.todos(ctx).forEachIndexed { i, todo ->
                val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = ui.dp(44) }
                val cb = CheckBox(ctx).apply {
                    text = todo.text; isChecked = todo.done
                    setTextColor(if (todo.done) Holo.ICE_DIM else Holo.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    buttonTintList = ColorStateList.valueOf(Holo.ICE)
                    paintFlags = if (todo.done) paintFlags or Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                }
                val del = TextView(ctx).apply {
                    text = "×"; contentDescription = "Löschen"; setTextColor(Holo.ICE_DIM)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); gravity = Gravity.CENTER
                }
                line.addView(cb, ui.weight())
                line.addView(del, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
                box.addView(line)
                cb.setOnCheckedChangeListener { _, checked ->
                    val l = Prefs.todos(ctx); if (i < l.size) { l[i] = l[i].copy(done = checked); Prefs.setTodos(ctx, l) }
                    box.post { renderTodos() }
                }
                del.setOnClickListener {
                    val l = Prefs.todos(ctx); if (i < l.size) { l.removeAt(i); Prefs.setTodos(ctx, l) }
                    box.post { renderTodos() }
                }
            }
        }
        field.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                val s = v.text.toString().trim()
                if (s.isNotEmpty()) { val l = Prefs.todos(ctx); l.add(Todo(s, false)); Prefs.setTodos(ctx, l); v.text = ""; renderTodos() }
                true
            } else false
        }
        t.addView(field, ui.lpMatch(top = 8))
        t.addView(box)
        renderTodos()
        col.addView(t, ui.lpMatch(top = 6))
    }

    private fun editor(id: Long) {
        val note = Prefs.notes(ctx).firstOrNull { it.id == id } ?: run { editing = null; list(); return }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        lateinit var titleIn: EditText
        lateinit var bodyIn: EditText
        fun save() {
            Prefs.saveNote(ctx, note.copy(title = titleIn.text.toString(), body = bodyIn.text.toString(), updated = System.currentTimeMillis()))
        }
        head.addView(ui.pill("← Fertig", true) {
            save()
            val n = Prefs.notes(ctx).firstOrNull { it.id == id }
            if (n != null && n.title.isBlank() && n.body.isBlank()) Prefs.deleteNote(ctx, id)
            editing = null; render()
        })
        head.addView(View(ctx), ui.weight())
        head.addView(ui.pill("Löschen") { Prefs.deleteNote(ctx, id); editing = null; render() })
        col.addView(head, ui.lpMatch(bottom = 10))

        titleIn = ui.input("Titel").apply {
            setText(note.title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        bodyIn = ui.input("Schreib los …", lines = 16).apply { setText(note.body) }
        col.addView(titleIn, ui.lpMatch(bottom = 8))
        col.addView(bodyIn, ui.lpMatch(bottom = 8))
        val count = ui.small("")
        col.addView(count)
        val w = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                save()
                val words = bodyIn.text.split(Regex("\\s+")).count { it.isNotBlank() }
                count.text = "$words Wörter · automatisch gespeichert"
            }
        }
        titleIn.addTextChangedListener(w); bodyIn.addTextChangedListener(w)
        w.afterTextChanged(null)
    }

    override fun onHide() { /* Notizen speichern sich beim Tippen */ }
}
