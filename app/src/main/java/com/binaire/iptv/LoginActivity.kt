package com.binaire.iptv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.content.res.ColorStateList
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : BaseActivity() {

    companion object {
        const val EXTRA_ERROR = "error"
    }

    override val needsData = false

    private lateinit var etServer: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var btnLogin: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        applyBackground(findViewById(R.id.root))
        etServer = findViewById(R.id.etServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        btnLogin = findViewById(R.id.btnLogin)
        progress = findViewById(R.id.progress)
        tvError = findViewById(R.id.tvError)

        val inputBg = { BTheme.selector(this, 10f, BTheme.WHITE12, BTheme.WHITE12, accent, null, 2f) }
        etServer.background = inputBg()
        etUser.background = inputBg()
        etPass.background = inputBg()
        btnLogin.background = BTheme.selector(this, 22f, accent, accent, BTheme.WHITE)

        etServer.setText(prefs.lastServer)
        etUser.setText(prefs.lastUser)
        tvError.text = intent.getStringExtra(EXTRA_ERROR).orEmpty()

        val cbShowPass: CheckBox = findViewById(R.id.cbShowPass)
        cbShowPass.buttonTintList = ColorStateList.valueOf(accent)
        cbShowPass.background = BTheme.selector(this, 8f, 0, BTheme.WHITE12, accent, null, 2f)
        cbShowPass.setOnCheckedChangeListener { _, show ->
            val cursor = etPass.selectionEnd
            etPass.inputType = InputType.TYPE_CLASS_TEXT or
                if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            etPass.typeface = etUser.typeface // garde la police de l'appli
            etPass.setSelection(cursor.coerceIn(0, etPass.text.length))
        }

        btnLogin.setOnClickListener { attemptLogin() }
        val btnAccount: Button = findViewById(R.id.btnAccount)
        btnAccount.background = BTheme.selector(this, 20f, BTheme.WHITE18, accent, BTheme.WHITE, null, 2.5f)
        btnAccount.setTextColor(BTheme.WHITE)
        btnAccount.setOnClickListener {
            startActivity(Intent(this, ActivateActivity::class.java))
            finish()
        }
        etPass.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                attemptLogin(); true
            } else false
        }
        etServer.doAfterTextChanged { parseM3uLink(it?.toString().orEmpty()) }
    }

    /** Lien du type http://serveur:port/get.php?username=X&password=Y → remplit les trois champs. */
    private fun parseM3uLink(text: String) {
        if (!text.contains("username=")) return
        val raw = text.trim()
        val uri = Uri.parse(if (raw.contains("://")) raw else "http://$raw")
        val host = uri.host ?: return
        val user = uri.getQueryParameter("username")
        val pass = uri.getQueryParameter("password")
        val base = "${uri.scheme ?: "http"}://$host" + if (uri.port != -1) ":${uri.port}" else ""
        if (user != null) etUser.setText(user)
        if (pass != null) etPass.setText(pass)
        etServer.setText(base)
        etServer.setSelection(base.length)
        toast("Lien M3U détecté : identifiants remplis")
    }

    private fun attemptLogin() {
        var server = etServer.text.toString().trim()
        var user = etUser.text.toString().trim()
        var pass = etPass.text.toString().trim()
        // Portail Stalker/MAG : « Utilisateur » contient une MAC (00:1A:79:…).
        val mac = StalkerClient.cleanMac(user)
        if (mac != null && pass.isEmpty() && server.contains("://")) {
            server = StalkerClient.normalizePortal(server)
            user = mac
            pass = ""
        } else if (user.isEmpty() && pass.isEmpty() && server.contains("://") && server.length > 12) {
            // Lien M3U simple (sans identifiant dedans) : collé seul dans « Serveur ».
            server = XtreamClient.M3U_PREFIX + server
            user = "m3u"
            pass = "m3u"
        }
        val isSpecial = server.startsWith(XtreamClient.M3U_PREFIX) || server.startsWith(StalkerClient.STALKER_PREFIX)
        if (server.isEmpty() || (!isSpecial && (user.isEmpty() || pass.isEmpty()))) {
            tvError.text = "Remplis les trois champs, colle ton lien M3U dans « Serveur », ou mets une MAC dans « Utilisateur » pour un portail."
            return
        }
        setBusy(true)
        tvError.text = ""
        lifecycleScope.launch {
            try {
                val c = XtreamClient.create(server, user, pass)
                val account = withContext(Dispatchers.IO) { c.login() }
                prefs.saveLogin(c.server, user, pass)
                Session.clear()
                Session.client = c
                Session.account = account
                startActivity(
                    Intent(this@LoginActivity, SplashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tvError.text = e.message ?: "Connexion impossible"
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !busy
    }
}
