package com.binaire.iptv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Synchronisation des profils famille avec le compte Binaire.
 * Chaque profil = un document users/{compte}/profiles/{id} contenant ses réglages et son historique.
 * Le plus récent l'emporte ; les envois se font à la sortie des écrans.
 */
object Sync {

    /** Clés propres à l'appareil, jamais envoyées (le profil « p1 » partage ce fichier). */
    private val deviceKeys = setOf("server", "user", "pass", "last_server", "last_user")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------- Instantanés ----------
    private fun dump(sp: SharedPreferences, skip: Set<String> = emptySet()): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all.toSortedMap()) {
            if (k in skip || v == null) continue
            o.put(k, when (v) {
                is String -> JSONObject().put("t", "s").put("v", v)
                is Boolean -> JSONObject().put("t", "b").put("v", v)
                is Int -> JSONObject().put("t", "i").put("v", v)
                is Long -> JSONObject().put("t", "l").put("v", v)
                is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }.sorted()))
                else -> continue
            })
        }
        return o
    }

    private fun restore(sp: SharedPreferences, o: JSONObject, keep: Set<String> = emptySet()) {
        val e = sp.edit()
        for (k in sp.all.keys) if (k !in keep) e.remove(k)
        for (k in o.keys()) {
            if (k in keep) continue
            val x = o.optJSONObject(k) ?: continue
            when (x.optString("t")) {
                "s" -> e.putString(k, x.optString("v"))
                "b" -> e.putBoolean(k, x.optBoolean("v"))
                "i" -> e.putInt(k, x.optInt("v"))
                "l" -> e.putLong(k, x.optLong("v"))
                "f" -> e.putFloat(k, x.optDouble("v").toFloat())
                "set" -> {
                    val a = x.optJSONArray("v") ?: JSONArray()
                    e.putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet())
                }
            }
        }
        e.commit()
    }

    private fun prefsFile(ctx: Context, pid: String) =
        ctx.applicationContext.getSharedPreferences(Profiles.prefsFile(pid), Context.MODE_PRIVATE)

    private fun watchFile(ctx: Context, pid: String) =
        ctx.applicationContext.getSharedPreferences(Profiles.watchFile(pid), Context.MODE_PRIVATE)

    private fun snapshot(ctx: Context, p: Profiles.Profile): Map<String, Any?> = mapOf(
        "name" to p.name,
        "color" to p.color,
        "kid" to p.kid,
        "pin" to (p.pin ?: ""),
        "prefs" to dump(prefsFile(ctx, p.id), deviceKeys).toString(),
        "watch" to dump(watchFile(ctx, p.id)).toString()
    )

    private fun hash(m: Map<String, Any?>): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update(m.toSortedMap().toString().toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---------- Envoi ----------
    /** Envoie les profils modifiés depuis le dernier envoi. */
    fun pushChanged(ctx: Context) {
        val owner = Cloud.ownerUid(ctx) ?: return
        val token = Cloud.token(ctx)
        val synced = Cloud.syncedIds(ctx).toMutableSet()
        for (p in Profiles.all(ctx)) {
            val snap = snapshot(ctx, p)
            val h = hash(snap)
            if (h == Cloud.pushedHash(ctx, p.id) && p.id in synced) continue
            val now = System.currentTimeMillis()
            Cloud.setDoc(token, "users/$owner/profiles/${p.id}", snap + ("updatedAt" to now))
            Cloud.setPushedHash(ctx, p.id, h)
            Cloud.setPulledAt(ctx, p.id, now)
            synced.add(p.id)
        }
        Cloud.setSyncedIds(ctx, synced)
    }

    /** Envoi en arrière-plan (sortie d'écran) ; les erreurs réseau sont ignorées. */
    fun pushLater(ctx: Context) {
        if (!Cloud.isLinked(ctx)) return
        val app = ctx.applicationContext
        scope.launch { runCatching { pushChanged(app) } }
    }

    fun deleteRemote(ctx: Context, pid: String) {
        if (!Cloud.isLinked(ctx)) return
        val app = ctx.applicationContext
        val owner = Cloud.ownerUid(app) ?: return
        scope.launch {
            runCatching { Cloud.deleteDoc(Cloud.token(app), "users/$owner/profiles/$pid") }
            Cloud.setSyncedIds(app, Cloud.syncedIds(app) - pid)
        }
    }

    // ---------- Réception ----------
    /**
     * Récupère les profils du compte : applique les plus récents, supprime ceux effacés ailleurs,
     * puis envoie les profils locaux nouveaux ou modifiés.
     */
    fun pull(ctx: Context) {
        val owner = Cloud.ownerUid(ctx) ?: return
        val token = Cloud.token(ctx)
        val remote = Cloud.listDocs(token, "users/$owner/profiles").toMap()
        val synced = Cloud.syncedIds(ctx)
        var list = Profiles.all(ctx).toMutableList()

        // Supprimés depuis un autre appareil.
        for (p in list.toList()) {
            if (p.id in synced && p.id !in remote && list.size > 1) {
                Profiles.remove(ctx, p.id, fromSync = true)
                list = Profiles.all(ctx).toMutableList()
            }
        }

        // Nouveaux ou plus récents dans le compte.
        for ((pid, m) in remote) {
            val updated = (m["updatedAt"] as? Long) ?: 0L
            val local = list.firstOrNull { it.id == pid }
            if (local != null && updated <= Cloud.pulledAt(ctx, pid)) continue
            val prof = Profiles.Profile(
                id = pid,
                name = (m["name"] as? String) ?: "Profil",
                color = ((m["color"] as? Long) ?: 0L).toInt(),
                kid = (m["kid"] as? Boolean) == true,
                pin = (m["pin"] as? String)?.ifBlank { null }
            )
            Profiles.upsertFromSync(ctx, prof)
            (m["prefs"] as? String)?.let { runCatching { restore(prefsFile(ctx, pid), JSONObject(it), deviceKeys) } }
            (m["watch"] as? String)?.let { runCatching { restore(watchFile(ctx, pid), JSONObject(it)) } }
            Cloud.setPulledAt(ctx, pid, updated)
            Cloud.setPushedHash(ctx, pid, hash(snapshot(ctx, prof)))
            list = Profiles.all(ctx).toMutableList()
        }
        Cloud.setSyncedIds(ctx, synced + remote.keys)
        pushChanged(ctx)
    }
}
