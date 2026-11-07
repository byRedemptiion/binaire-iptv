package com.binaire.iptv

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan

/** Petite pastille carrée arrondie avec une lettre (avatar du profil dans l'en-tête). */
class RoundedBadgeSpan(private val color: Int, private val size: Int) : ReplacementSpan() {

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = size

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint
    ) {
        val cy = (top + bottom) / 2f
        val rect = RectF(x, cy - size / 2f, x + size, cy + size / 2f)
        val old = paint.color
        paint.color = color
        canvas.drawRoundRect(rect, size * 0.28f, size * 0.28f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        val label = text.subSequence(start, end).toString().trim()
        val w = paint.measureText(label)
        val fm = paint.fontMetrics
        canvas.drawText(label, rect.centerX() - w / 2f, cy - (fm.ascent + fm.descent) / 2f, paint)
        paint.color = old
    }
}
