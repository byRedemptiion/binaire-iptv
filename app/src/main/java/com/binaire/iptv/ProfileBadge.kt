package com.binaire.iptv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Locale

/**
 * Pastille ronde d'un profil, identique partout (entête d'accueil, notifications) :
 * son avatar s'il en a un, sinon un rond de la couleur du profil avec son initiale.
 */
object ProfileBadge {

    fun bitmap(ctx: Context, p: Profiles.Profile, sizePx: Int): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = size / 2f

        val avatar = if (Avatars.exists(ctx, p.avatar)) decodeAvatar(ctx, p.avatar!!) else null
        if (avatar != null) {
            val square = cropSquare(avatar)
            val scaled = Bitmap.createScaledBitmap(square, size, size, true)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            canvas.drawCircle(r, r, r, paint)
        } else {
            val color = BTheme.accents.getOrElse(p.color) { BTheme.accents[0] }.second
            canvas.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            val initial = p.name.trim().take(1).uppercase(Locale.FRANCE).ifEmpty { "?" }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textAlign = Paint.Align.CENTER
                textSize = size * 0.5f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val baseline = r - (text.descent() + text.ascent()) / 2f
            canvas.drawText(initial, r, baseline, text)
        }
        return out
    }

    private fun decodeAvatar(ctx: Context, path: String): Bitmap? =
        runCatching { ctx.assets.open("avatars/$path").use { BitmapFactory.decodeStream(it) } }.getOrNull()

    private fun cropSquare(b: Bitmap): Bitmap {
        val s = minOf(b.width, b.height)
        return Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
    }
}
