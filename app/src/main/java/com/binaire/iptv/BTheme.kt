package com.binaire.iptv

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.BitmapFactory
import android.view.View
import kotlin.math.max

/** Couleurs, fonds et styles de sélection de Binaire IPTV. */
object BTheme {
    const val BG_BINAIRE = "binaire"
    const val BG_AURORE = "aurore"
    const val BG_NOIR = "noir"
    const val BG_IMAGE = "image"

    val backgrounds = listOf(
        BG_BINAIRE to "Thème Binaire",
        BG_AURORE to "Aurore (suit ta couleur)",
        BG_NOIR to "Noir profond",
        BG_IMAGE to "Image floutée"
    )

    val accents = listOf(
        "Rouge Binaire" to 0xFFE5203A.toInt(),
        "Bleu" to 0xFF3B7BF6.toInt(),
        "Vert" to 0xFF16A34A.toInt(),
        "Orange" to 0xFFEA7A0C.toInt(),
        "Violet" to 0xFF8B5CF6.toInt(),
        "Rose" to 0xFFDB2777.toInt(),
        "Turquoise" to 0xFF0E9F9A.toInt(),
        "Or" to 0xFFC9A227.toInt()
    )

    const val PANEL = 0xB812141C.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val WHITE12 = 0x1FFFFFFF
    const val WHITE18 = 0x2EFFFFFF

    fun accent(ctx: Context): Int = accents.getOrElse(Prefs(ctx).accentIndex) { accents[0] }.second
    fun accentName(ctx: Context): String = accents.getOrElse(Prefs(ctx).accentIndex) { accents[0] }.first
    fun backgroundName(ctx: Context): String =
        backgrounds.firstOrNull { it.first == Prefs(ctx).background }?.second ?: backgrounds[0].second

    /** Change quand l'apparence change : les écrans ouverts se redessinent. */
    fun stamp(ctx: Context): String {
        val p = Prefs(ctx)
        return "${p.accentIndex}|${p.background}|${p.showClock}"
    }

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun glow(color: Int, cx: Float, cy: Float, radius: Float): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(color, Color.TRANSPARENT)).apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientRadius(radius)
            setGradientCenter(cx, cy)
        }

    /** Fond d'écran complet selon le réglage choisi. */
    fun background(ctx: Context): Drawable {
        val dm = ctx.resources.displayMetrics
        val r = max(dm.widthPixels, dm.heightPixels) * 0.55f
        val accent = accent(ctx)
        return when (Prefs(ctx).background) {
            BG_NOIR -> ColorDrawable(0xFF08090C.toInt())
            BG_AURORE -> LayerDrawable(
                arrayOf(
                    ColorDrawable(0xFF0A0B12.toInt()),
                    glow(withAlpha(accent, 0xB3), 0.1f, 0.05f, r),
                    glow(withAlpha(accent, 0x80), 0.95f, 0.95f, r),
                    glow(0x668B5CF6, 0.72f, 0.18f, r * 0.7f)
                )
            )
            BG_IMAGE -> {
                val bmp = BitmapFactory.decodeResource(ctx.resources, R.drawable.bg_image)
                LayerDrawable(arrayOf(BitmapDrawable(ctx.resources, bmp), ColorDrawable(0x7306070A)))
            }
            else -> LayerDrawable(
                arrayOf(
                    ColorDrawable(0xFF070912.toInt()),
                    glow(0xB31E6BFF.toInt(), 0f, 0f, r * 0.85f),
                    glow(0xA6FF1F3D.toInt(), 1f, 1f, r * 0.85f),
                    glow(0x665B2A9E, 0.8f, 0.15f, r * 0.6f),
                    glow(0x593A1F7A, 0.15f, 0.85f, r * 0.6f)
                )
            )
        }
    }

    fun rounded(ctx: Context, radiusDp: Float, fill: Int, strokeColor: Int? = null, strokeDp: Float = 2f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fill)
            if (strokeColor != null) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    /**
     * Fond « sélectionnable » à la télécommande.
     * focused : quand l'élément a le focus ; selected : élément actif (catégorie choisie, onglet…).
     */
    fun selector(
        ctx: Context,
        radiusDp: Float,
        normal: Int,
        focused: Int,
        focusedStroke: Int? = WHITE,
        selected: Int? = null,
        strokeDp: Float = 2.5f
    ): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, radiusDp, focused, focusedStroke, strokeDp))
        addState(intArrayOf(android.R.attr.state_pressed), rounded(ctx, radiusDp, focused, focusedStroke, strokeDp))
        if (selected != null) addState(intArrayOf(android.R.attr.state_selected), rounded(ctx, radiusDp, selected))
        addState(intArrayOf(), rounded(ctx, radiusDp, normal))
    }

    fun zoom(v: View, has: Boolean, scale: Float) {
        v.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(140).start()
        v.translationZ = if (has) 8f else 0f
    }

    /** Petit zoom quand l'élément prend le focus (tuiles, affiches). */
    fun zoomOnFocus(v: View, scale: Float = 1.06f) {
        val previous = v.onFocusChangeListener
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) scale else 1f).scaleY(if (has) scale else 1f).setDuration(140).start()
            view.translationZ = if (has) 8f else 0f
            previous?.onFocusChange(view, has)
        }
    }

    /**
     * Commandes du lecteur bien visibles à la télécommande : le bouton qui a le focus
     * prend la couleur principale avec un contour blanc, et grossit un peu.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun stylePlayerControls(root: View, accent: Int) {
        val ctx = root.context
        fun walk(v: View) {
            val name = v.javaClass.simpleName
            if (name.contains("TimeBar")) {
                (v as? androidx.media3.ui.DefaultTimeBar)?.let {
                    it.setPlayedColor(accent)
                    it.setScrubberColor(accent)
                }
                return
            }
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
                return
            }
            if (v.isFocusable && v.isClickable) {
                v.background = selector(ctx, 24f, android.graphics.Color.TRANSPARENT, accent, WHITE, null, 2.5f)
                zoomOnFocus(v, 1.15f)
            }
        }
        walk(root)
    }

    /** Formats d'image proposés (clé enregistrée → libellé). */
    val imageFormats = listOf(
        "fit" to "Ajusté (image entière)",
        "zoom" to "Plein écran (recadré)",
        "fill" to "Étiré",
        "z115" to "Zoom léger (+15 %)",
        "z133" to "Zoom cinéma (enlève les bandes noires)"
    )

    fun imageFormatName(key: String): String = imageFormats.firstOrNull { it.first == key }?.second ?: imageFormats[0].second

    /**
     * Applique le format : les 3 premiers jouent sur le cadre de la vidéo, les zooms agrandissent
     * l'image elle-même (utile quand les bandes noires sont incrustées dans le film).
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun applyImageFormat(view: androidx.media3.ui.PlayerView, key: String) {
        view.resizeMode = when (key) {
            "zoom" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            "fill" -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        val scale = when (key) { "z115" -> 1.15f; "z133" -> 1.34f; else -> 1f }
        view.videoSurfaceView?.let { it.scaleX = scale; it.scaleY = scale }
    }
}
