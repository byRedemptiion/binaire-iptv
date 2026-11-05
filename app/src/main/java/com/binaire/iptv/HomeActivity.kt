package com.binaire.iptv

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Accueil : les 4 grandes tuiles TV / FILMS / SÉRIES / RÉGLAGES. */
class HomeActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectIfNotReady()) return
        setContentView(R.layout.activity_home)
        applyBackground(findViewById(R.id.root))
        setupHeader(null, showBack = false)
        findViewById<ImageView>(R.id.headerLogo).layoutParams.height = dp(46f)

        val nLive = Session.items(Kind.LIVE).size
        val nMovies = Session.items(Kind.MOVIE).size
        val nSeries = Session.items(Kind.SERIES).size
        setupTile(R.id.tileTv, R.drawable.ic_tile_tv, "TV", "$nLive chaînes en direct") {
            startActivity(Intent(this, LiveActivity::class.java))
        }
        setupTile(R.id.tileFilms, R.drawable.ic_tile_film, "FILMS", "$nMovies films") {
            startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_KIND, Kind.MOVIE.name))
        }
        setupTile(R.id.tileSeries, R.drawable.ic_tile_series, "SÉRIES", "$nSeries séries") {
            startActivity(Intent(this, VodActivity::class.java).putExtra(VodActivity.EXTRA_KIND, Kind.SERIES.name))
        }
        setupTile(R.id.tileSettings, R.drawable.ic_tile_settings, "RÉGLAGES", "Couleurs, lecture, compte") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        val acc = Session.account
        val tvAccount: TextView = findViewById(R.id.tvAccount)
        tvAccount.text = when {
            acc == null -> ""
            acc.expDate != null && acc.expDate > 0 ->
                "Abonnement ${statusFr(acc.status)} · expire le " +
                    SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(acc.expDate * 1000))
            else -> "Abonnement ${statusFr(acc.status)}"
        }

        findViewById<View>(R.id.tileTv).requestFocus()
    }

    /** Taille des tuiles adaptée à l'écran (TV, tablette ou téléphone). */
    private val tileSize: Pair<Int, Int> by lazy {
        val dm = resources.displayMetrics
        val wDp = dm.widthPixels / dm.density
        val hDp = dm.heightPixels / dm.density
        val w = ((wDp - 96f - 4 * 20f) / 4f).coerceIn(110f, 180f)
        val h = (w * 1.33f).coerceAtMost((hDp - 170f).coerceAtLeast(140f))
        dp(w) to dp(h)
    }

    private fun setupTile(id: Int, icon: Int, label: String, sub: String, onClick: () -> Unit) {
        val tile = findViewById<View>(id)
        tile.layoutParams = tile.layoutParams.apply {
            width = tileSize.first
            height = tileSize.second
        }
        if (tileSize.first < dp(150f)) {
            tile.findViewById<TextView>(R.id.tileLabel).textSize = 17f
            tile.findViewById<ImageView>(R.id.tileIcon).layoutParams.let { it.width = dp(42f); it.height = dp(42f) }
        }
        tile.background = BTheme.selector(this, 20f, BTheme.PANEL, accent, BTheme.WHITE, null, 3f)
        tile.findViewById<ImageView>(R.id.tileIcon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(BTheme.WHITE)
        }
        tile.findViewById<TextView>(R.id.tileLabel).text = label
        tile.findViewById<TextView>(R.id.tileSub).text = sub
        tile.setOnClickListener { onClick() }
        BTheme.zoomOnFocus(tile, 1.08f)
    }

    /** Les nombres changent quand on choisit des pays ou qu'on masque des catégories. */
    private fun refreshCounts() {
        findViewById<View>(R.id.tileTv)?.findViewById<TextView>(R.id.tileSub)?.text = "${Session.items(Kind.LIVE).size} chaînes en direct"
        findViewById<View>(R.id.tileFilms)?.findViewById<TextView>(R.id.tileSub)?.text = "${Session.items(Kind.MOVIE).size} films"
        findViewById<View>(R.id.tileSeries)?.findViewById<TextView>(R.id.tileSub)?.text = "${Session.items(Kind.SERIES).size} séries"
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        refreshCounts()
        if (!Session.updateChecked && Updater.enabled) {
            Session.updateChecked = true
            lifecycleScope.launch {
                val release = runCatching { Updater.check() }.getOrNull()
                if (release != null && !isFinishing) Updater.offer(this@HomeActivity, lifecycleScope, release)
            }
        }
    }
}
