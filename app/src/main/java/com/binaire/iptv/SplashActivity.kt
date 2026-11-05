package com.binaire.iptv

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

/** Écran de démarrage : connexion à l'abonnement et chargement des chaînes, films et séries. */
class SplashActivity : BaseActivity() {

    override val needsData = false

    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var errorButtons: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.hasLogin()) {
            openLogin(null)
            return
        }
        setContentView(R.layout.activity_splash)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        errorButtons = findViewById(R.id.errorButtons)
        progress.progressTintList = ColorStateList.valueOf(accent)

        val btnRetry: Button = findViewById(R.id.btnRetry)
        val btnChange: Button = findViewById(R.id.btnChange)
        for (b in listOf(btnRetry, btnChange)) {
            b.background = BTheme.selector(this, 20f, BTheme.WHITE18, accent)
        }
        btnRetry.setOnClickListener { load() }
        btnChange.setOnClickListener { openLogin(null) }

        load()
    }

    private fun load() {
        errorButtons.visibility = View.GONE
        progress.progress = 0
        status.text = "Connexion à ton abonnement…"
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            val c = Session.client ?: XtreamClient(prefs.server!!, prefs.user!!, prefs.pass!!).also { Session.client = it }

            try {
                Session.account = withContext(Dispatchers.IO) { c.login() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XtreamException) {
                if (e.message?.contains("Identifiants refusés") == true) {
                    openLogin("Tes identifiants ont été refusés par le serveur. Vérifie-les.")
                    return@launch
                }
                showError(e.message ?: "Connexion impossible")
                return@launch
            } catch (e: Exception) {
                showError("Connexion impossible : ${e.message}")
                return@launch
            }

            progress.progress = 10
            val names = mapOf(Kind.LIVE to "chaînes", Kind.MOVIE to "films", Kind.SERIES to "séries")
            val finished = mutableSetOf<Kind>()
            fun loadingText() = "Chargement de ta liste : " + Kind.values().joinToString("  ·  ") { k ->
                names.getValue(k) + if (k in finished) " ✓" else "…"
            }
            status.text = loadingText()
            val done = AtomicInteger(0)
            val failures = mutableListOf<String>()
            // Une liste après l'autre : moins de mémoire utilisée en même temps.
            for (kind in Kind.values()) {
                run {
                    try {
                        withContext(Dispatchers.IO) {
                            Session.rawCategories[kind] = c.categories(kind)
                            Session.rawStreams[kind] = c.streams(kind)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Session.rawCategories[kind] = emptyList()
                        Session.rawStreams[kind] = emptyList()
                        failures.add(
                            when (kind) {
                                Kind.LIVE -> "chaînes"
                                Kind.MOVIE -> "films"
                                Kind.SERIES -> "séries"
                            }
                        )
                    }
                    finished.add(kind)
                    progress.progress = 10 + 20 * done.incrementAndGet()
                    status.text = loadingText()
                }
            }

            if (failures.size == 3) {
                showError("Impossible de charger le contenu de ton abonnement.")
                return@launch
            }
            if (failures.isNotEmpty()) toast("Chargement incomplet : ${failures.joinToString(", ")}")

            status.text = "Rangement de ta liste… 0 %"
            val finishedInTime = withTimeoutOrNull(60_000) {
                withContext(Dispatchers.Default) {
                    Library.rebuild(prefs) { pct ->
                        runOnUiThread {
                            progress.progress = 70 + pct * 30 / 100
                            status.text = "Rangement de ta liste… $pct %"
                        }
                    }
                }
                true
            }
            if (finishedInTime == null) {
                Library.useRawLists()
                android.widget.Toast.makeText(
                    this@SplashActivity,
                    "Le rangement prend trop de temps sur cet appareil : ta liste d'origine est affichée. Tu peux couper « Nettoyer les noms » dans les Réglages.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            Session.ready = true
            // Laisse le temps d'apercevoir l'écran de démarrage.
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < 1200) delay(1200 - elapsed)
            val needCountries = !prefs.countriesConfigured &&
                Session.countries.count { it.code != Library.UNKNOWN } >= 2
            startActivity(
                if (needCountries) Intent(this@SplashActivity, CountriesActivity::class.java).putExtra(CountriesActivity.EXTRA_FIRST, true)
                else Intent(this@SplashActivity, HomeActivity::class.java)
            )
            finish()
        }
    }

    private fun showError(msg: String) {
        status.text = msg
        errorButtons.visibility = View.VISIBLE
        findViewById<View>(R.id.btnRetry).requestFocus()
    }

    private fun openLogin(error: String?) {
        startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ERROR, error))
        finish()
    }
}
