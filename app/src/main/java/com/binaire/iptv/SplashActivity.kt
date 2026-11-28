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
        // Pas d'abonnement et pas de compte : on propose le compte Binaire (QR code).
        if (!prefs.hasLogin() && !Cloud.isLinked(this)) {
            startActivity(Intent(this, ActivateActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_splash)
        // Notifications de nouveaux épisodes : planifie la vérification + demande l'autorisation (Android 13+).
        SeriesNotifier.schedule(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 && SeriesNotifier.enabled(this) &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7) }
        }
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
        status.text = getString(R.string.splash_connecting)
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            if (Cloud.isLinked(this@SplashActivity)) {
                status.text = getString(R.string.splash_sync)
                val ok = kotlinx.coroutines.withTimeoutOrNull(12_000) {
                    withContext(Dispatchers.IO) { syncAccount() }
                }
                if (ok == false) toast(getString(R.string.splash_device_removed))
            }
            if (!prefs.hasLogin()) {
                startActivity(Intent(this@SplashActivity, ActivateActivity::class.java))
                finish()
                return@launch
            }
            status.text = getString(R.string.splash_connecting)
            val c = Session.client ?: XtreamClient.create(prefs.server!!, prefs.user!!, prefs.pass!!).also { Session.client = it }

            try {
                Session.account = withContext(Dispatchers.IO) { c.login() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: XtreamException) {
                if (e.message?.contains("Identifiants refusés") == true) {
                    openLogin(getString(R.string.splash_bad_credentials))
                    return@launch
                }
                showError(e.message ?: getString(R.string.splash_connect_failed))
                return@launch
            } catch (e: Exception) {
                showError(getString(R.string.splash_connect_failed_detail, e.message ?: ""))
                return@launch
            }

            progress.progress = 10
            val names = mapOf(Kind.LIVE to getString(R.string.word_channels), Kind.MOVIE to getString(R.string.word_movies), Kind.SERIES to getString(R.string.word_series))
            val finished = mutableSetOf<Kind>()
            fun loadingText() = getString(R.string.splash_loading_prefix) + Kind.values().joinToString("  ·  ") { k ->
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
                                Kind.LIVE -> getString(R.string.word_channels)
                                Kind.MOVIE -> getString(R.string.word_movies)
                                Kind.SERIES -> getString(R.string.word_series)
                            }
                        )
                    }
                    finished.add(kind)
                    progress.progress = 10 + 20 * done.incrementAndGet()
                    status.text = loadingText()
                }
            }

            if (failures.size == 3) {
                showError(getString(R.string.splash_load_failed_all))
                return@launch
            }
            if (failures.isNotEmpty()) toast(getString(R.string.splash_load_incomplete, failures.joinToString(", ")))

            if (Profiles.all(this@SplashActivity).size > 1 && Profiles.askOnStart(this@SplashActivity)) {
                Session.ready = true
                startActivity(Intent(this@SplashActivity, ProfilesActivity::class.java).putExtra(ProfilesActivity.EXTRA_START, true))
                finish()
                return@launch
            }
            status.text = getString(R.string.splash_organizing, 0)
            val finishedInTime = withTimeoutOrNull(60_000) {
                withContext(Dispatchers.Default) {
                    Library.rebuild(prefs) { pct ->
                        runOnUiThread {
                            progress.progress = 70 + pct * 30 / 100
                            status.text = getString(R.string.splash_organizing, pct)
                        }
                    }
                }
                true
            }
            if (finishedInTime == null) {
                Library.useRawLists()
                android.widget.Toast.makeText(
                    this@SplashActivity,
                    getString(R.string.splash_organize_slow),
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

    /**
     * Compte Binaire : vérifie l'autorisation, récupère les profils et l'abonnement à jour.
     * Renvoie false si l'appareil a été retiré du compte. Hors ligne, on continue avec l'existant.
     */
    private fun syncAccount(): Boolean {
        if (!Cloud.stillAuthorized(this)) {
            Cloud.setOwner(this, null, null)
            return false
        }
        runCatching { Sync.pull(this) }
        runCatching {
            val subs = Cloud.subs(this)
            if (subs.isEmpty()) {
                // Abonnement saisi dans l'appli avant d'être lié au compte : on le range sur le compte.
                if (prefs.hasLogin()) runCatching { Cloud.registerSub(this, prefs.server!!, prefs.user!!, prefs.pass!!) }
                return true
            }
            val chosen = subs.firstOrNull { it.id == Cloud.subId(this) } ?: if (!prefs.hasLogin()) subs.first() else null
            if (chosen != null) {
                val server = XtreamClient.normalize(chosen.server)
                // Abonnement modifié depuis le téléphone : on prend la nouvelle version.
                if (server != prefs.server || chosen.user != prefs.user || chosen.pass != prefs.pass) {
                    prefs.saveLogin(server, chosen.user, chosen.pass)
                    Session.client = null
                }
                Cloud.setSubId(this, chosen.id)
            }
        }
        return true
    }

    private fun openLogin(error: String?) {
        startActivity(Intent(this, LoginActivity::class.java).putExtra(LoginActivity.EXTRA_ERROR, error))
        finish()
    }
}
