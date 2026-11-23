package de.glas.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Implikations-Karte: Knoten (Ereignisse/Zustände) und Pfeile (A führt zu B mit Wahrscheinlichkeit p,
 * oder A verhindert B). Wahrscheinlichkeiten pflanzen sich durch das Netz fort (Noisy-OR).
 */
class MapModel {
    class N(val id: Int, var text: String, var base: Double, var value: Double?) {
        var p = 0.0
        var depth = 0
        var x = 0f; var y = 0f; var w = 0f; var h = 0f
        var layout: StaticLayout? = null
    }
    class E(val a: Int, val b: Int, var p: Double, var neg: Boolean)

    val nodes = mutableListOf<N>()
    val edges = mutableListOf<E>()
    private var nextId = 1

    fun node(id: Int) = nodes.firstOrNull { it.id == id }
    fun incoming(id: Int) = edges.filter { it.b == id }
    fun outgoing(id: Int) = edges.filter { it.a == id }
    fun isRoot(id: Int) = edges.none { it.b == id }

    fun add(text: String, base: Double = 0.0, value: Double? = null): N {
        val n = N(nextId++, text, base, value); nodes += n; return n
    }

    fun link(a: Int, b: Int, p: Double, neg: Boolean = false) {
        if (a == b || edges.any { it.a == a && it.b == b }) return
        edges += E(a, b, p.coerceIn(0.0, 1.0), neg)
    }

    fun remove(id: Int) { nodes.removeAll { it.id == id }; edges.removeAll { it.a == id || it.b == id } }

    /** Wahrscheinlichkeiten: P(B) = (1 − (1−Basis)·Π(1 − P(A)·p)) · Π_verhindernd(1 − P(A)·p) */
    fun compute() {
        nodes.forEach { it.p = it.base }
        repeat(nodes.size + 2) {
            for (n in nodes) {
                val inc = incoming(n.id)
                if (inc.isEmpty()) { n.p = n.base; continue }
                var notPos = 1.0 - n.base
                var keep = 1.0
                for (e in inc) {
                    val pa = node(e.a)?.p ?: 0.0
                    if (e.neg) keep *= 1.0 - pa * e.p else notPos *= 1.0 - pa * e.p
                }
                n.p = ((1.0 - notPos) * keep).coerceIn(0.0, 1.0)
            }
        }
        // Tiefe = längster Weg von einem Ausgangspunkt (Zyklen begrenzt)
        nodes.forEach { it.depth = 0 }
        repeat(nodes.size) {
            for (e in edges) {
                val a = node(e.a) ?: continue; val b = node(e.b) ?: continue
                if (b.depth < a.depth + 1 && a.depth + 1 < nodes.size) b.depth = a.depth + 1
            }
        }
    }

    fun expectedValue(): Double? {
        val withV = nodes.filter { it.value != null }
        if (withV.isEmpty()) return null
        return withV.sumOf { it.p * (it.value ?: 0.0) }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("next", nextId)
        .put("nodes", JSONArray().also { a ->
            nodes.forEach { n -> a.put(JSONObject().put("id", n.id).put("t", n.text).put("base", n.base).put("v", n.value ?: JSONObject.NULL)) }
        })
        .put("edges", JSONArray().also { a ->
            edges.forEach { e -> a.put(JSONObject().put("a", e.a).put("b", e.b).put("p", e.p).put("neg", e.neg)) }
        })

    fun load(o: JSONObject) {
        nodes.clear(); edges.clear()
        val ns = o.optJSONArray("nodes") ?: JSONArray()
        for (i in 0 until ns.length()) {
            val j = ns.getJSONObject(i)
            nodes += N(j.getInt("id"), j.optString("t"), j.optDouble("base", 0.0), if (j.isNull("v")) null else j.optDouble("v"))
        }
        val es = o.optJSONArray("edges") ?: JSONArray()
        for (i in 0 until es.length()) {
            val j = es.getJSONObject(i)
            edges += E(j.getInt("a"), j.getInt("b"), j.optDouble("p", 0.5), j.optBoolean("neg"))
        }
        nextId = max(o.optInt("next", 1), (nodes.maxOfOrNull { it.id } ?: 0) + 1)
        compute()
    }

    /** Text für Gemini oder zum Teilen. */
    fun describe(): String {
        val sb = StringBuilder()
        val roots = nodes.filter { isRoot(it.id) }
        sb.append("Ausgangspunkte: ").append(roots.joinToString(", ") { "${it.text} (${pct(it.p)})" }).append('\n')
        sb.append("Implikationen:\n")
        edges.forEach { e ->
            val a = node(e.a); val b = node(e.b)
            if (a != null && b != null) sb.append("- ${a.text} ${if (e.neg) "verhindert" else "führt zu"} ${b.text} (bedingt ${pct(e.p)}) → ${b.text} gesamt ${pct(b.p)}\n")
        }
        nodes.filter { it.value != null }.forEach { sb.append("- Wert von ${it.text}: ${it.value}\n") }
        expectedValue()?.let { sb.append("Erwartungswert: ${"%.1f".format(it)}\n") }
        return sb.toString().trimEnd()
    }

    companion object {
        fun pct(p: Double) = "${(p * 100).toInt()} %"

        fun example() = MapModel().apply {
            val a = add("Ich gründe ein Startup", 1.0)
            val b = add("Erstes Jahr ohne Gehalt", 0.0, -20.0)
            val c = add("Produkt findet Kunden")
            val d = add("Finanzieller Druck", 0.0, -30.0)
            val e = add("Investoren steigen ein")
            val f = add("Profitabel nach 3 Jahren", 0.0, 150.0)
            val g = add("Viel gelernt, Netzwerk aufgebaut", 0.0, 40.0)
            link(a.id, b.id, 0.9); link(a.id, c.id, 0.35); link(a.id, g.id, 0.85)
            link(b.id, d.id, 0.7); link(d.id, c.id, 0.3, neg = true)
            link(c.id, e.id, 0.5); link(c.id, f.id, 0.4); link(e.id, f.id, 0.5)
            compute()
        }
    }
}

/** Zeichnet die Karte als animierte Mindmap. Ziehen = verschieben, zwei Finger = zoomen, Tippen = auswählen. */
@SuppressLint("ViewConstructor")
class ImplMapView(ctx: Context, val model: MapModel, val onSelect: (Int?) -> Unit) : View(ctx) {

    var selected: Int? = null
        set(v) { field = v; invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private var tx = 0f; private var ty = 0f; private var scale = 1f
    private var bornAt = System.currentTimeMillis()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Holo.WHITE; textSize = 13.5f * d }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d; typeface = Holo.label }
    private val dash = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
    private val path = Path()
    private val rect = RectF()
    private val NEG = 0xFFFF6B7A.toInt()

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun replay() { bornAt = System.currentTimeMillis(); invalidate() }
    fun center() { tx = 0f; ty = 0f; scale = 1f; invalidate() }

    /** Ebenen von oben nach unten, innerhalb einer Ebene nach Lage der Eltern sortiert. */
    fun relayout() {
        model.compute()
        val maxW = 150 * d
        val padX = 12 * d; val padY = 10 * d
        for (n in model.nodes) {
            val tw = min(maxW, text.measureText(n.text) + 1)
            n.layout = StaticLayout.Builder.obtain(n.text, 0, n.text.length, text, tw.toInt().coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(4).build()
            n.w = (n.layout?.width ?: 0) + padX * 2
            n.h = (n.layout?.height ?: 0) + padY * 2 + 16 * d
        }
        val layers = model.nodes.groupBy { it.depth }.toSortedMap()
        val gapX = 18 * d; val gapY = 58 * d
        var y = 0f
        var prev: List<MapModel.N> = emptyList()
        for ((_, layer0) in layers) {
            val layer = if (prev.isEmpty()) layer0 else layer0.sortedBy { n ->
                val ps = model.incoming(n.id).mapNotNull { e -> prev.firstOrNull { it.id == e.a }?.x ?: model.node(e.a)?.x }
                if (ps.isEmpty()) 0f else ps.average().toFloat()
            }
            val total = layer.sumOf { it.w.toDouble() }.toFloat() + gapX * (layer.size - 1)
            var x = -total / 2
            val rowH = layer.maxOf { it.h }
            for (n in layer) { n.x = x + n.w / 2; n.y = y + rowH / 2; x += n.w + gapX }
            y += rowH + gapY
            prev = layer
        }
        invalidate()
    }

    private fun easeOut(t: Float) = 1 - (1 - t) * (1 - t) * (1 - t)

    private fun appear(n: MapModel.N): Float {
        val t = (System.currentTimeMillis() - bornAt - n.depth * 140) / 420f
        return easeOut(t.coerceIn(0f, 1f))
    }

    private fun bez(a: MapModel.N, b: MapModel.N, t: Float, out: FloatArray) {
        val x0 = a.x; val y0 = a.y + a.h / 2
        val x3 = b.x; val y3 = b.y - b.h / 2
        val dy = max(40 * d, abs(y3 - y0) / 2)
        val x1 = x0; val y1 = y0 + dy; val x2 = x3; val y2 = y3 - dy
        val u = 1 - t
        out[0] = u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3
        out[1] = u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3
    }

    override fun onDraw(c: Canvas) {
        val now = System.currentTimeMillis()
        val accent = Holo.ICE
        c.save()
        c.translate(width / 2f + tx, 36 * d + ty)
        c.scale(scale, scale)

        // Pfeile
        val pt = FloatArray(2)
        for (e in model.edges) {
            val a = model.node(e.a) ?: continue; val b = model.node(e.b) ?: continue
            val k = min(appear(a), appear(b))
            if (k <= 0f) continue
            val col = if (e.neg) NEG else accent
            path.reset()
            bez(a, b, 0f, pt); path.moveTo(pt[0], pt[1])
            val steps = 24
            for (i in 1..(steps * k).toInt().coerceAtLeast(1)) { bez(a, b, i / steps.toFloat(), pt); path.lineTo(pt[0], pt[1]) }
            val strength = (a.p * e.p).toFloat()
            edgeP.color = col; edgeP.alpha = (70 + 150 * strength).toInt().coerceIn(0, 255)
            edgeP.strokeWidth = (1.5f + 4f * strength) * d
            edgeP.pathEffect = if (e.neg) dash else null
            c.drawPath(path, edgeP)
            if (k >= 1f) {
                // Pfeilspitze
                bez(a, b, 0.97f, pt); val px = pt[0]; val py = pt[1]
                bez(a, b, 1f, pt)
                val ang = Math.atan2((pt[1] - py).toDouble(), (pt[0] - px).toDouble())
                val s = 8 * d
                path.reset()
                path.moveTo(pt[0], pt[1])
                path.lineTo((pt[0] - s * Math.cos(ang - 0.45)).toFloat(), (pt[1] - s * Math.sin(ang - 0.45)).toFloat())
                path.lineTo((pt[0] - s * Math.cos(ang + 0.45)).toFloat(), (pt[1] - s * Math.sin(ang + 0.45)).toFloat())
                path.close()
                fill.color = col; fill.alpha = edgeP.alpha
                c.drawPath(path, fill)
                // fließende Punkte – je wahrscheinlicher, desto schneller und heller
                val speed = 0.15f + 0.6f * strength
                for (i in 0 until 3) {
                    val t = (((now / 1000f) * speed + i / 3f) % 1f)
                    bez(a, b, t, pt)
                    dot.color = col; dot.alpha = (60 + 195 * strength * sin(Math.PI * t).toFloat()).toInt().coerceIn(0, 255)
                    c.drawCircle(pt[0], pt[1], (2f + 2.5f * strength) * d, dot)
                }
                // Beschriftung
                bez(a, b, 0.5f, pt)
                val label = (if (e.neg) "−" else "") + "${(e.p * 100).toInt()}%"
                small.color = col
                val lw = small.measureText(label) + 10 * d
                rect.set(pt[0] - lw / 2, pt[1] - 9 * d, pt[0] + lw / 2, pt[1] + 9 * d)
                fill.color = Holo.NAVY; fill.alpha = 230
                c.drawRoundRect(rect, 9 * d, 9 * d, fill)
                c.drawText(label, pt[0] - lw / 2 + 5 * d, pt[1] + 4 * d, small)
            }
        }

        // Knoten
        for (n in model.nodes) {
            val k = appear(n)
            if (k <= 0f) continue
            c.save()
            c.translate(n.x, n.y)
            c.scale(0.6f + 0.4f * k, 0.6f + 0.4f * k)
            val l = -n.w / 2; val t = -n.h / 2; val r = n.w / 2; val b = n.h / 2
            val cut = 12 * d
            path.reset()
            path.moveTo(l + cut, t); path.lineTo(r, t); path.lineTo(r, b - cut); path.lineTo(r - cut, b); path.lineTo(l, b); path.lineTo(l, t + cut); path.close()
            val p = n.p.toFloat()
            // Glühen nach Wahrscheinlichkeit
            fill.color = accent; fill.alpha = (40 * p * k).toInt()
            c.drawCircle(0f, 0f, max(n.w, n.h) * (0.55f + 0.1f * p), fill)
            fill.color = Holo.PANEL_DEEP; fill.alpha = (255 * k).toInt()
            c.drawPath(path, fill)
            val sel = selected == n.id
            stroke.color = accent
            stroke.alpha = ((if (sel) 255 else (90 + 140 * p).toInt()) * k).toInt()
            stroke.strokeWidth = (if (sel) 2.5f else 1.2f) * d
            c.drawPath(path, stroke)
            if (sel) {
                val pulse = (now % 1400) / 1400f
                stroke.alpha = ((1 - pulse) * 200).toInt()
                stroke.strokeWidth = 1.5f * d
                c.save(); c.scale(1 + 0.12f * pulse, 1 + 0.25f * pulse); c.drawPath(path, stroke); c.restore()
            }
            // Text
            n.layout?.let { lay ->
                text.alpha = (255 * k).toInt()
                c.save(); c.translate(-lay.width / 2f, t + 10 * d); lay.draw(c); c.restore()
            }
            // Wahrscheinlichkeit + Balken
            val barY = b - 9 * d
            fill.color = 0x33FFFFFF; c.drawRect(l + 10 * d, barY, r - 10 * d, barY + 3 * d, fill)
            fill.color = if (n.value != null && (n.value ?: 0.0) < 0) NEG else accent
            c.drawRect(l + 10 * d, barY, l + 10 * d + (n.w - 20 * d) * p * k, barY + 3 * d, fill)
            small.color = accent; small.alpha = (255 * k).toInt()
            val label = MapModel.pct(n.p) + (n.value?.let { "  ·  " + (if (it > 0) "+" else "") + it.toInt() } ?: "")
            c.drawText(label, -small.measureText(label) / 2, barY - 4 * d, small)
            c.restore()
        }
        c.restore()
        if (model.nodes.isEmpty()) {
            small.color = Holo.TEXT_DIM
            val m = "Leer – füge einen Ausgangspunkt hinzu"
            c.drawText(m, (width - small.measureText(m)) / 2, height / 2f, small)
        }
        if (isShown) postInvalidateOnAnimation()
    }

    // ---------- Gesten ----------

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            scale = (scale * det.scaleFactor).coerceIn(0.35f, 2.5f); invalidate(); return true
        }
    })
    private var lastX = 0f; private var lastY = 0f; private var downX = 0f; private var downY = 0f
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = e.x; lastY = e.y; downX = e.x; downY = e.y; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaler.isInProgress && e.pointerCount == 1) {
                    if (abs(e.x - downX) > 8 * d || abs(e.y - downY) > 8 * d) moved = true
                    if (moved) { tx += e.x - lastX; ty += e.y - lastY; invalidate() }
                } else moved = true
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> moved = true
            MotionEvent.ACTION_UP -> {
                if (!moved) {
                    val wx = (e.x - width / 2f - tx) / scale
                    val wy = (e.y - 36 * d - ty) / scale
                    val hit = model.nodes.lastOrNull { abs(wx - it.x) <= it.w / 2 && abs(wy - it.y) <= it.h / 2 }
                    selected = hit?.id
                    onSelect(hit?.id)
                }
            }
        }
        return true
    }
}
