package com.binaire.iptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Base commune : thème, horloge, retour au démarrage si les données ne sont pas chargées. */
abstract class BaseActivity : AppCompatActivity() {

    protected lateinit var prefs: Prefs
    protected lateinit var watch: WatchStore
    protected var accent: Int = 0
    private var themeStamp = ""

    /** false pour les écrans qui n'ont pas besoin des listes chargées (démarrage, connexion). */
    protected open val needsData = true

    private val handler = Handler(Looper.getMainLooper())
    private var clockView: TextView? = null
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val tick = object : Runnable {
        override fun run() {
            clockView?.text = clockFmt.format(Date())
            handler.postDelayed(this, 15_000)
        }
    }

    protected val client: XtreamClient get() = Session.client!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        watch = WatchStore(this)
        accent = BTheme.accent(this)
        themeStamp = BTheme.stamp(this)
    }

    /** À appeler en tête d'onCreate : renvoie true si l'écran a été fermé. */
    protected fun redirectIfNotReady(): Boolean {
        if (needsData && (!Session.ready || Session.client == null)) {
            startActivity(
                Intent(this, SplashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
            return true
        }
        return false
    }

    protected fun applyBackground(root: View) {
        root.background = BTheme.background(this)
    }

    /** En-tête commun (logo, titre de rubrique, heure) inclus via layout/header.xml. */
    protected fun setupHeader(title: String?, showBack: Boolean = true) {
        val back: TextView? = findViewById(R.id.headerBack)
        back?.let {
            it.visibility = if (showBack) View.VISIBLE else View.GONE
            it.background = BTheme.selector(this, 17f, BTheme.PANEL, accent, BTheme.WHITE, null, 2f)
            val size = dp(16f)
            it.compoundDrawablesRelative[0]?.setBounds(0, 0, size, size)
            it.setCompoundDrawablesRelative(it.compoundDrawablesRelative[0], null, null, null)
            it.setOnClickListener { finish() }
        }
        val titleView: TextView? = findViewById(R.id.headerTitle)
        titleView?.let {
            it.text = title.orEmpty()
            it.visibility = if (title.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        val divider: View? = findViewById(R.id.headerDivider)
        divider?.visibility = if (title.isNullOrEmpty()) View.GONE else View.VISIBLE
        clockView = findViewById(R.id.clock)
        clockView?.visibility = if (prefs.showClock) View.VISIBLE else View.GONE
    }

    /** Plein écran : pas de barres système (téléphones), comme sur une TV. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (BTheme.stamp(this) != themeStamp) {
            recreate()
            return
        }
        handler.removeCallbacks(tick)
        if (clockView != null) handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    protected fun dp(v: Float) = BTheme.dp(this, v)

    /** Écran peu haut (téléphone en paysage) : on simplifie certaines mises en page. */
    protected val isShortScreen: Boolean
        get() = resources.displayMetrics.let { it.heightPixels / it.density < 480f }
}
