package com.binaire.iptv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Prévient (notification) quand un nouvel épisode sort dans une série suivie (« Ma liste »).
 * Vérification discrète toutes les 12 h en arrière-plan.
 */
object SeriesNotifier {

    private const val CHANNEL = "nouveaux_episodes"
    private const val WORK = "binaire-episodes"
    private const val TRACK_FILE = "binaire_series_track"

    fun enabled(ctx: Context) =
        ctx.getSharedPreferences("binaire_profiles", Context.MODE_PRIVATE).getBoolean("notifs", true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("binaire_profiles", Context.MODE_PRIVATE).edit().putBoolean("notifs", on).apply()
        if (on) schedule(ctx) else WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
    }

    fun schedule(ctx: Context) {
        if (!enabled(ctx)) return
        val req = PeriodicWorkRequestBuilder<Worker>(12, TimeUnit.HOURS).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Nouveaux épisodes", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Quand un nouvel épisode sort dans une série suivie" }
            )
        }
    }

    /** Signature d'une série : nombre total d'épisodes + libellé du dernier. */
    private data class Sig(val count: Int, val label: String, val name: String)

    private fun latest(info: SeriesInfo): Sig {
        var total = 0
        var bestS = -1; var bestE = -1
        for ((season, eps) in info.episodes) {
            total += eps.size
            val s = season.toIntOrNull() ?: 0
            for (e in eps) {
                val n = e.number.toIntOrNull() ?: 0
                if (s > bestS || (s == bestS && n > bestE)) { bestS = s; bestE = n }
            }
        }
        val label = "S%02dE%02d".format(bestS.coerceAtLeast(0), bestE.coerceAtLeast(0))
        return Sig(total, label, info.name)
    }

    /** Séries suivies, tous profils confondus. */
    private fun followedSeries(ctx: Context): Set<String> {
        val out = HashSet<String>()
        for (p in Profiles.all(ctx)) out += WatchStore.forProfile(ctx, p.id).myListIds(Kind.SERIES)
        return out
    }

    suspend fun check(ctx: Context) = withContext(Dispatchers.IO) {
        if (!enabled(ctx)) return@withContext
        val prefs = Prefs(ctx)
        if (!prefs.hasLogin()) return@withContext
        val ids = followedSeries(ctx)
        if (ids.isEmpty()) return@withContext
        val client = runCatching { XtreamClient.create(prefs.server!!, prefs.user!!, prefs.pass!!) }.getOrNull() ?: return@withContext
        runCatching { client.login() }
        val track = ctx.getSharedPreferences(TRACK_FILE, Context.MODE_PRIVATE)
        for (id in ids) {
            val info = runCatching { client.seriesInfo(id) }.getOrNull() ?: continue
            val sig = latest(info)
            if (sig.count <= 0) continue
            val prev = track.getInt("count_$id", -1)
            // Premier passage : on mémorise sans notifier (évite de spammer au départ).
            if (prev >= 0 && sig.count > prev) notify(ctx, id, sig)
            track.edit().putInt("count_$id", sig.count).putString("name_$id", sig.name).apply()
        }
    }

    private fun notify(ctx: Context, seriesId: String, sig: Sig) {
        channel(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val open = Intent(ctx, SeriesDetailActivity::class.java)
            .putExtra(SeriesDetailActivity.EXTRA_ID, seriesId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            ctx, seriesId.hashCode(), open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(sig.name.ifBlank { "Nouvel épisode" })
            .setContentText("Nouvel épisode disponible : ${sig.label}")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(seriesId.hashCode(), notif) }
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            runCatching { check(applicationContext) }
            return Result.success()
        }
    }
}
