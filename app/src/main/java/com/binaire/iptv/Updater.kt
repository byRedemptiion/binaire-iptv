package com.binaire.iptv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Mises à jour depuis les « Releases » GitHub du projet. */
object Updater {

    data class Release(val version: String, val apkUrl: String, val notes: String)

    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Renvoie la nouvelle version si elle est plus récente que celle installée, sinon null. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = XtreamClient.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@withContext null
            r.body?.string().orEmpty()
        }
        val o = JSONObject(body)
        val tag = o.optString("tag_name").removePrefix("v")
        val assets = o.optJSONArray("assets")
        var apk: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    apk = a.optString("browser_download_url"); break
                }
            }
        }
        if (apk == null || !isNewer(tag, BuildConfig.VERSION_NAME)) null
        else Release(tag, apk, o.optString("body"))
    }

    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        // Même numéro : une version « -dev » installée est remplacée par la version publiée.
        return local.contains('-') && !remote.contains('-')
    }

    private suspend fun download(activity: Activity, release: Release, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "Binaire-IPTV-${release.version}.apk")
            val req = Request.Builder().url(release.apkUrl).build()
            XtreamClient.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("Téléchargement impossible (${r.code})")
                val body = r.body ?: throw IllegalStateException("Fichier vide")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != last) {
                                    last = pct
                                    withContext(Dispatchers.Main) { onProgress(pct) }
                                }
                            }
                        }
                    }
                }
            }
            out
        }

    /** Lance l'installeur Android. Renvoie false si l'autorisation doit d'abord être donnée. */
    private fun install(activity: Activity, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Une autorisation est nécessaire")
                .setMessage("Pour installer les mises à jour, autorise Binaire IPTV à installer des applications (à faire une seule fois). Reviens ensuite et relance la mise à jour.")
                .setPositiveButton("Ouvrir les réglages") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                        )
                    } catch (e: ActivityNotFoundException) {
                        activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    }
                }
                .setNegativeButton("Plus tard", null)
                .show()
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return true
    }

    /** Propose la mise à jour, la télécharge avec une barre de progression puis l'installe. */
    fun offer(activity: Activity, scope: LifecycleCoroutineScope, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Nouvelle version ${release.version}")
            .setMessage("Une mise à jour de Binaire IPTV est disponible. Tes réglages et favoris sont conservés.")
            .setPositiveButton("Installer") { _, _ -> runDownload(activity, scope, release) }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private fun runDownload(activity: Activity, scope: LifecycleCoroutineScope, release: Release) {
        val pad = BTheme.dp(activity, 20f)
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val label = TextView(activity).apply { text = "Téléchargement… 0 %"; gravity = Gravity.CENTER }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label)
            addView(bar)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("Mise à jour").setView(box).setCancelable(false).show()
        scope.launch {
            try {
                val file = download(activity, release) { pct ->
                    bar.progress = pct
                    label.text = "Téléchargement… $pct %"
                }
                dialog.dismiss()
                install(activity, file)
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                AlertDialog.Builder(activity)
                    .setTitle("Mise à jour impossible")
                    .setMessage(e.message ?: "Erreur inconnue")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}
