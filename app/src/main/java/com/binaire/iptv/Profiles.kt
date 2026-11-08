package com.binaire.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profils famille, sur l'appareil.
 * Le premier profil (« p1 ») réutilise les réglages existants : rien n'est perdu à la mise à jour.
 */
object Profiles {

    data class Profile(
        val id: String,
        val name: String,
        val color: Int,          // index dans BTheme.accents
        val kid: Boolean,
        val pin: String?         // code à 4 chiffres, ou null
    )

    const val MAX = 6
    private const val FILE = "binaire_profiles"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Profile> {
        val raw = sp(ctx).getString("list", null)
        val list = mutableListOf<Profile>()
        if (raw != null) {
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    list.add(
                        Profile(
                            id = o.optString("id"),
                            name = o.optString("name", "Profil"),
                            color = o.optInt("color", 0),
                            kid = o.optBoolean("kid", false),
                            pin = o.optString("pin", "").ifBlank { null }
                        )
                    )
                }
            } catch (e: Exception) {
                // liste illisible : on repart du profil par défaut
            }
        }
        if (list.isEmpty()) {
            val first = Profile("p1", "Moi", Prefs.legacyAccent(ctx), false, null)
            save(ctx, listOf(first))
            return listOf(first)
        }
        return list
    }

    private fun save(ctx: Context, list: List<Profile>) {
        val a = JSONArray()
        for (p in list) {
            a.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("color", p.color); put("kid", p.kid)
                put("pin", p.pin ?: "")
            })
        }
        sp(ctx).edit().putString("list", a.toString()).apply()
    }

    fun currentId(ctx: Context): String {
        val id = sp(ctx).getString("current", null)
        val list = all(ctx)
        return if (id != null && list.any { it.id == id }) id else list.first().id
    }

    fun current(ctx: Context): Profile = all(ctx).let { l -> l.firstOrNull { it.id == currentId(ctx) } ?: l.first() }

    fun setCurrent(ctx: Context, id: String) {
        sp(ctx).edit().putString("current", id).apply()
    }

    fun get(ctx: Context, id: String): Profile? = all(ctx).firstOrNull { it.id == id }

    fun update(ctx: Context, p: Profile) {
        save(ctx, all(ctx).map { if (it.id == p.id) p else it })
    }

    /** Crée un profil ; il reprend le choix des pays du profil actuel. */
    fun add(ctx: Context, name: String, color: Int, kid: Boolean): Profile {
        val list = all(ctx)
        // Identifiant aléatoire : pas de collision entre profils créés sur deux appareils.
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        var id: String
        do {
            id = "p" + (1..7).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        } while (list.any { it.id == id })
        val p = Profile(id, name.trim().ifBlank { "Profil ${list.size + 1}" }, color, kid, null)
        save(ctx, list + p)
        val from = Prefs(ctx)
        val to = Prefs.forProfile(ctx, p.id)
        to.accentIndex = color
        to.allowedCountries = from.allowedCountries
        to.countriesConfigured = from.countriesConfigured
        if (kid) to.hideAdult = true
        return p
    }

    /** Ajoute ou met à jour un profil reçu du compte Binaire. */
    fun upsertFromSync(ctx: Context, p: Profile) {
        val list = all(ctx)
        save(ctx, if (list.any { it.id == p.id }) list.map { if (it.id == p.id) p else it } else list + p)
    }

    fun remove(ctx: Context, id: String, fromSync: Boolean = false) {
        val list = all(ctx)
        if (list.size <= 1) return
        if (!fromSync) Sync.deleteRemote(ctx, id)
        save(ctx, list.filter { it.id != id })
        if (currentId(ctx) == id) setCurrent(ctx, list.first { it.id != id }.id)
        if (id != "p1") {
            Prefs.forProfile(ctx, id).wipe()
            WatchStore.forProfile(ctx, id).wipe()
        }
    }

    // --- Réglages communs à tous les profils ---
    fun askOnStart(ctx: Context): Boolean = sp(ctx).getBoolean("ask", true)
    fun setAskOnStart(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("ask", v).apply()

    /** Code parental : protège les Réglages des profils Enfant. */
    fun parentalPin(ctx: Context): String? = sp(ctx).getString("parental", null)?.ifBlank { null }

    fun setParentalPin(ctx: Context, pin: String?) {
        sp(ctx).edit().putString("parental", pin ?: "").apply()
    }

    /** Nom du fichier de réglages d'un profil (« p1 » garde le fichier historique). */
    fun prefsFile(id: String) = if (id == "p1") "binaire" else "binaire_p_$id"
    fun watchFile(id: String) = if (id == "p1") "binaire_watch" else "binaire_watch_$id"
}
