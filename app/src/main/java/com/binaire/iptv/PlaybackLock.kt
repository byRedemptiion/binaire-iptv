package com.binaire.iptv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Abonnement à 1 seul écran : le dernier appareil qui lance une lecture la prend,
 * les autres appareils du même compte Binaire coupent la leur pour libérer la connexion.
 * Le « jeton » est le document users/{compte}/live/current.
 */
object PlaybackLock {

    private fun path(ctx: Context) = "users/${Cloud.ownerUid(ctx)}/live/current"

    /** Actif seulement si l'appareil est lié à un compte et que l'abonnement n'a qu'une connexion. */
    fun enabled(ctx: Context): Boolean {
        if (!Cloud.isLinked(ctx)) return false
        val max = Session.account?.maxConnections?.trim()?.toIntOrNull() ?: 1
        return max <= 1
    }

    data class Holder(val device: String, val name: String, val at: Long)

    fun claim(ctx: Context) {
        val me = Cloud.deviceUid(ctx) ?: Cloud.token(ctx).let { Cloud.deviceUid(ctx) } ?: return
        Cloud.setDoc(
            Cloud.token(ctx), path(ctx),
            mapOf("device" to me, "name" to Cloud.deviceName(), "at" to System.currentTimeMillis())
        )
    }

    fun holder(ctx: Context): Holder? {
        val m = Cloud.getDoc(Cloud.token(ctx), path(ctx)) ?: return null
        return Holder((m["device"] as? String).orEmpty(), (m["name"] as? String) ?: "Un autre appareil", (m["at"] as? Long) ?: 0L)
    }
}

/**
 * Surveille, pendant une lecture, si un autre appareil du compte a pris la main.
 * claim = true : on prend la main (lecteur plein écran) ; false : on se contente de céder (aperçu).
 */
class PlaybackGuard(private val activity: AppCompatActivity, private val onKicked: (String) -> Unit) {

    private var job: Job? = null
    /** Moment où on a pris la main : sert à réessayer le temps que le fournisseur libère la place. */
    var claimedAt = 0L
        private set

    /** Vrai tant qu'un autre appareil occupait peut-être l'abonnement quand on a pris la main. */
    @Volatile var mayBeBusy = false
        private set

    fun start(claim: Boolean) {
        val ctx = activity.applicationContext
        if (!PlaybackLock.enabled(ctx)) return
        job?.cancel()
        if (claim) {
            claimedAt = System.currentTimeMillis()
            mayBeBusy = true
        }
        job = activity.lifecycleScope.launch {
            try {
                val baseline: PlaybackLock.Holder? = withContext(Dispatchers.IO) {
                    if (claim) {
                        val me = Cloud.deviceUid(ctx)
                        val prev = runCatching { PlaybackLock.holder(ctx) }.getOrNull()
                        // Un autre appareil avait la main il y a moins de 12 h : il va devoir libérer.
                        mayBeBusy = prev != null && prev.device != me && System.currentTimeMillis() - prev.at < 12 * 3600_000L
                        runCatching { PlaybackLock.claim(ctx) }
                        null
                    } else runCatching { PlaybackLock.holder(ctx) }.getOrNull()
                }
                val me = Cloud.deviceUid(ctx)
                while (isActive) {
                    delay(7000)
                    val h = withContext(Dispatchers.IO) { runCatching { PlaybackLock.holder(ctx) }.getOrNull() } ?: continue
                    if (h.device == me || h.device.isBlank()) continue
                    if (baseline != null && h.device == baseline.device && h.at == baseline.at) continue
                    onKicked(h.name)
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // réseau indisponible : on laisse la lecture tranquille
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
