package com.binaire.iptv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        Sync.pushLater(this) // envoie favoris, reprises, rangement… au compte Binaire
    }

    /** Reconstruit les listes en affichant une fenêtre avec le pourcentage d'avancement. */
    protected fun rebuildWithProgress(after: () -> Unit = {}) {
        val pad = dp(22f)
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        val label = TextView(this).apply { text = "Rangement de ta liste… 0 %"; setPadding(0, 0, 0, dp(10f)) }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label)
            addView(bar)
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(box).setCancelable(false).show()
        lifecycleScope.launch {
            val ok = kotlinx.coroutines.withTimeoutOrNull(90_000) {
                withContext(Dispatchers.Default) {
                    Library.rebuild(Prefs(this@BaseActivity)) { pct ->
                        runOnUiThread {
                            bar.progress = pct
                            label.text = "Rangement de ta liste… $pct %"
                        }
                    }
                }
                true
            }
            if (ok == null) {
                Library.useRawLists()
                toast("Rangement trop long : liste d'origine affichée")
            }
            dialog.dismiss()
            after()
        }
    }

    private fun pinField(): android.widget.EditText = android.widget.EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        hint = "4 chiffres"
        gravity = android.view.Gravity.CENTER
        textSize = 22f
        letterSpacing = 0.4f
    }

    private fun padded(v: View): View = android.widget.FrameLayout(this).apply {
        setPadding(dp(24f), dp(8f), dp(24f), 0)
        addView(v)
    }

    /** Demande un code ; onOk n'est appelé que s'il est correct. */
    protected fun askPin(title: String, expected: String, onOk: () -> Unit) {
        val field = pinField()
        val d = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(padded(field))
            .setPositiveButton("Valider", null)
            .setNegativeButton("Annuler", null)
            .create()
        d.setOnShowListener {
            val check = {
                if (field.text.toString() == expected) {
                    d.dismiss(); onOk()
                } else {
                    field.text.clear(); field.error = "Code incorrect"
                }
            }
            d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { check() }
            field.setOnEditorActionListener { _, _, _ -> check(); true }
            field.requestFocus()
        }
        d.show()
    }

    /** Choisir un nouveau code (saisi deux fois). */
    protected fun newPin(title: String, onSet: (String) -> Unit) {
        val first = pinField()
        val d = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Choisis un code à 4 chiffres.")
            .setView(padded(first))
            .setPositiveButton("Suivant", null)
            .setNegativeButton("Annuler", null)
            .create()
        d.setOnShowListener {
            d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = first.text.toString()
                if (code.length != 4) {
                    first.error = "4 chiffres"
                    return@setOnClickListener
                }
                d.dismiss()
                askPin("Confirme le code", code) { onSet(code) }
            }
        }
        d.show()
    }

    protected fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    protected fun dp(v: Float) = BTheme.dp(this, v)

    /** Écran peu haut (téléphone en paysage) : on simplifie certaines mises en page. */
    protected val isShortScreen: Boolean
        get() = resources.displayMetrics.let { it.heightPixels / it.density < 480f }
}
