package com.binaire.iptv

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Connexion au compte Binaire : QR code à scanner avec le téléphone, ou e-mail directement sur la TV. */
class ActivateActivity : BaseActivity() {

    override val needsData = false

    private lateinit var qr: ImageView
    private lateinit var qrLoading: View
    private lateinit var codeView: TextView
    private lateinit var status: TextView
    private lateinit var manualUrl: TextView
    private var code: String? = null
    private var pollJob: Job? = null
    private var linking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activate)
        applyBackground(findViewById(R.id.root))
        setupHeader("COMPTE BINAIRE", showBack = prefs.hasLogin())

        qr = findViewById(R.id.qr)
        qrLoading = findViewById(R.id.qrLoading)
        codeView = findViewById(R.id.code)
        status = findViewById(R.id.status)
        manualUrl = findViewById(R.id.manualUrl)
        codeView.setTextColor(accent)
        findViewById<View>(R.id.qrBox).background = BTheme.rounded(this, 16f, Color.WHITE)
        manualUrl.text = "Pas de QR ? Va sur ${Cloud.WEB_URL.removePrefix("https://")} et saisis :"

        val btnEmail: Button = findViewById(R.id.btnEmail)
        val btnManual: Button = findViewById(R.id.btnManual)
        val btnLater: Button = findViewById(R.id.btnLater)
        styleButtons(this, accent, btnEmail, listOf(btnManual, btnLater))
        btnEmail.setOnClickListener { emailDialog() }
        btnManual.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            if (!prefs.hasLogin()) finish()
        }
        if (prefs.hasLogin()) {
            btnLater.visibility = View.VISIBLE
            btnLater.setOnClickListener { finish() }
        }
        btnEmail.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        if (code == null) startCode() else startPolling()
    }

    override fun onStop() {
        super.onStop()
        pollJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        code?.let { c -> if (!linking) Thread { Cloud.cancelActivation(applicationContext, c) }.start() }
    }

    private fun startCode() {
        status.text = "Préparation du code…"
        qrLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val c = withContext(Dispatchers.IO) { Cloud.startActivation(this@ActivateActivity) }
                code = c
                codeView.text = c.substring(0, 3) + "-" + c.substring(3)
                qr.setImageBitmap(withContext(Dispatchers.Default) { qrBitmap(Cloud.WEB_URL + "?code=" + c, 600) })
                qrLoading.visibility = View.GONE
                status.text = "En attente de ton téléphone…"
                startPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                qrLoading.visibility = View.GONE
                status.text = "Impossible de joindre le serveur Binaire. Vérifie la connexion Internet. (${e.message})"
            }
        }
    }

    private fun startPolling() {
        val c = code ?: return
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            val started = System.currentTimeMillis()
            while (true) {
                delay(3000)
                if (System.currentTimeMillis() - started > 15 * 60_000) {
                    // Code trop vieux : on en propose un nouveau.
                    withContext(Dispatchers.IO) { Cloud.cancelActivation(this@ActivateActivity, c) }
                    code = null
                    startCode()
                    return@launch
                }
                val result = try {
                    withContext(Dispatchers.IO) { Cloud.checkActivation(this@ActivateActivity, c) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Cloud.CloudException) {
                    if (e.code == 404) {
                        code = null
                        startCode()
                        return@launch
                    }
                    null
                } catch (e: Exception) {
                    null
                }
                if (result != null) {
                    linking = true
                    status.text = "Téléphone validé ! Connexion…"
                    try {
                        withContext(Dispatchers.IO) { Cloud.finishActivation(this@ActivateActivity, c, result.first, result.second) }
                        afterLink()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        linking = false
                        status.text = "Presque : ${e.message}"
                        continue
                    }
                    return@launch
                }
            }
        }
    }

    private fun emailDialog() {
        val pad = dp(22f)
        val email = EditText(this).apply {
            hint = "E-mail du compte Binaire"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val pass = EditText(this).apply {
            hint = "Mot de passe"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8f), pad, 0)
            addView(email)
            addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle("Se connecter")
            .setMessage("Pas encore de compte ? Crée-le en scannant le QR code avec ton téléphone.")
            .setView(box)
            .setPositiveButton("Connexion") { _, _ ->
                val e = email.text.toString().trim()
                val p = pass.text.toString()
                if (e.isEmpty() || p.isEmpty()) {
                    toast("Remplis l'e-mail et le mot de passe")
                    return@setPositiveButton
                }
                linking = true
                pollJob?.cancel()
                status.text = "Connexion…"
                lifecycleScope.launch {
                    try {
                        val current = if (prefs.hasLogin()) Triple(prefs.server!!, prefs.user!!, prefs.pass!!) else null
                        withContext(Dispatchers.IO) { Cloud.linkWithEmail(this@ActivateActivity, e, p, current) }
                        code?.let { c -> withContext(Dispatchers.IO) { Cloud.cancelActivation(this@ActivateActivity, c) } }
                        afterLink()
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        linking = false
                        status.text = ex.message ?: "Connexion impossible"
                        startPolling()
                    }
                }
            }
            .setNeutralButton("Mot de passe oublié ?") { _, _ ->
                val e = email.text.toString().trim()
                if (e.isEmpty()) {
                    toast("Écris d'abord ton e-mail")
                    emailDialog()
                    return@setNeutralButton
                }
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { Cloud.sendPasswordReset(e) }.isSuccess }
                    AlertDialog.Builder(this@ActivateActivity)
                        .setTitle(if (ok) "E-mail envoyé" else "Envoi impossible")
                        .setMessage(
                            if (ok) "Si un compte existe pour $e, tu vas recevoir un lien pour choisir un nouveau mot de passe (regarde aussi dans les spams). Ensuite, reconnecte-toi ici."
                            else "Vérifie l'adresse e-mail et ta connexion Internet, puis réessaie."
                        )
                        .setPositiveButton("OK") { _, _ -> if (ok) emailDialog() }
                        .show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Appareil rattaché : on récupère les profils puis l'abonnement. */
    private suspend fun afterLink() {
        status.text = "Récupération de ton compte…"
        withContext(Dispatchers.IO) { runCatching { Sync.pull(this@ActivateActivity) } }
        var subs = withContext(Dispatchers.IO) { runCatching { Cloud.subs(this@ActivateActivity) }.getOrDefault(emptyList()) }
        if (subs.isEmpty() && !prefs.hasLogin()) {
            status.text = "Ton compte n'a pas encore d'abonnement : ajoute-le depuis ton téléphone (page Mon compte). J'attends…"
            while (subs.isEmpty()) {
                delay(4000)
                subs = withContext(Dispatchers.IO) { runCatching { Cloud.subs(this@ActivateActivity) }.getOrDefault(emptyList()) }
            }
        }
        when {
            subs.isEmpty() -> done()
            subs.size == 1 -> useSub(subs[0])
            else -> AlertDialog.Builder(this)
                .setTitle("Quel abonnement utiliser sur cet appareil ?")
                .setItems(subs.map { it.name }.toTypedArray()) { _, w -> useSub(subs[w]) }
                .setCancelable(false)
                .show()
        }
    }

    private fun useSub(s: Cloud.Sub) {
        prefs.saveLogin(XtreamClient.normalize(s.server), s.user, s.pass)
        Cloud.setSubId(this, s.id)
        done()
    }

    private fun done() {
        toast("Appareil connecté à ${Cloud.ownerEmail(this) ?: "ton compte"}")
        Session.clear()
        startActivity(Intent(this, SplashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) 0xFF0E1116.toInt() else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
