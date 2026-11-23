package de.glas.dashboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.min

/** Farben des „Arktis-Kommando“-Designs: Marineblau, Eisblau, Weiß. */
object Holo {
    val ICE = 0xFF7FDBFF.toInt()
    val ICE_DIM = 0x997FDBFF.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val TEXT_DIM = 0xB3D6EEFF.toInt()
    val NAVY = 0xFF050B18.toInt()
    val PANEL = 0xCC0A1830.toInt()
    val PANEL_DEEP = 0xE6061024.toInt()
    val LINE = 0x597FDBFF
    val SCRIM = 0xFF020814.toInt()

    val label: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val body: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
}

/**
 * Fläche mit abgeschrägten Ecken (oben links, unten rechts) und einer
 * hellen Kante entlang der Schräge – der kantige Tech-Look.
 */
class CutDrawable(
    private val cut: Float,
    fill: Int,
    stroke: Int,
    private val strokeW: Float,
    accent: Int = Holo.ICE
) : Drawable() {
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill }
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = stroke; strokeWidth = strokeW }
    private val pAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = accent; strokeWidth = strokeW * 2.5f; strokeCap = Paint.Cap.SQUARE
    }
    private val path = Path()
    private val accentPath = Path()

    override fun onBoundsChange(bounds: Rect) {
        val h = strokeW / 2f
        val l = bounds.left + h
        val t = bounds.top + h
        val r = bounds.right - h
        val b = bounds.bottom - h
        val c = min(cut, min(r - l, b - t) / 2f)
        path.reset()
        path.moveTo(l + c, t); path.lineTo(r, t); path.lineTo(r, b - c)
        path.lineTo(r - c, b); path.lineTo(l, b); path.lineTo(l, t + c); path.close()
        accentPath.reset()
        accentPath.moveTo(l, t + c); accentPath.lineTo(l + c, t)
        accentPath.moveTo(r - c, b); accentPath.lineTo(r, b - c)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, pFill)
        if (strokeW > 0f) {
            canvas.drawPath(path, pStroke)
            canvas.drawPath(accentPath, pAccent)
        }
    }

    override fun setAlpha(alpha: Int) { pFill.alpha = alpha; pStroke.alpha = alpha; pAccent.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
