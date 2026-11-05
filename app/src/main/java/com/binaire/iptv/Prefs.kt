package com.binaire.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Réglages et identifiants, stockés uniquement sur l'appareil. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("binaire", Context.MODE_PRIVATE)

    // --- Abonnement ---
    val server: String? get() = sp.getString("server", null)
    val user: String? get() = sp.getString("user", null)
    val pass: String? get() = sp.getString("pass", null)
    val lastServer: String get() = sp.getString("last_server", "") ?: ""
    val lastUser: String get() = sp.getString("last_user", "") ?: ""

    fun hasLogin() = !server.isNullOrBlank() && !user.isNullOrBlank() && !pass.isNullOrBlank()

    fun saveLogin(server: String, user: String, pass: String) {
        sp.edit()
            .putString("server", server).putString("user", user).putString("pass", pass)
            .putString("last_server", server).putString("last_user", user)
            .apply()
    }

    fun clearLogin() {
        sp.edit().remove("server").remove("user").remove("pass").apply()
    }

    // --- Favoris (chaînes) ---
    fun favorites(): Set<String> = HashSet(sp.getStringSet("fav", emptySet()) ?: emptySet())

    fun toggleFavorite(id: String): Boolean {
        val set = favorites().toMutableSet()
        val added = if (set.contains(id)) {
            set.remove(id); false
        } else {
            set.add(id); true
        }
        sp.edit().putStringSet("fav", set).apply()
        return added
    }

    // --- Apparence ---
    var accentIndex: Int
        get() = sp.getInt("accent", 0)
        set(v) = sp.edit().putInt("accent", v).apply()

    var background: String
        get() = sp.getString("background", BTheme.BG_BINAIRE) ?: BTheme.BG_BINAIRE
        set(v) = sp.edit().putString("background", v).apply()

    var showClock: Boolean
        get() = sp.getBoolean("clock", true)
        set(v) = sp.edit().putBoolean("clock", v).apply()

    // --- Lecture ---
    var autoFallback: Boolean
        get() = sp.getBoolean("fallback", true)
        set(v) = sp.edit().putBoolean("fallback", v).apply()

    var autoNext: Boolean
        get() = sp.getBoolean("auto_next", true)
        set(v) = sp.edit().putBoolean("auto_next", v).apply()

    var largeBuffer: Boolean
        get() = sp.getBoolean("large_buffer", false)
        set(v) = sp.edit().putBoolean("large_buffer", v).apply()

    /** Format d'image : fit (ajusté), zoom (recadré), fill (étiré). */
    var resizeMode: String
        get() = sp.getString("resize", "fit") ?: "fit"
        set(v) = sp.edit().putString("resize", v).apply()

    var livePreview: Boolean
        get() = sp.getBoolean("live_preview", true)
        set(v) = sp.edit().putBoolean("live_preview", v).apply()

    fun removeFavorites(ids: Collection<String>) {
        val set = favorites().toMutableSet()
        set.removeAll(ids.toSet())
        sp.edit().putStringSet("fav", set).apply()
    }

    // --- Grand ménage des listes ---
    var countriesConfigured: Boolean
        get() = sp.getBoolean("countries_done", false)
        set(v) = sp.edit().putBoolean("countries_done", v).apply()

    /** Pays affichés (codes). Vide = tous. */
    var allowedCountries: Set<String>
        get() = HashSet(sp.getStringSet("countries", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("countries", v).apply()

    var cleanNames: Boolean
        get() = sp.getBoolean("clean_names", true)
        set(v) = sp.edit().putBoolean("clean_names", v).apply()

    var mergeDuplicates: Boolean
        get() = sp.getBoolean("merge_dups", true)
        set(v) = sp.edit().putBoolean("merge_dups", v).apply()

    /** Qualité préférée pour les chaînes en plusieurs versions : auto, 4K, FHD, HD, SD. */
    var preferredQuality: String
        get() = sp.getString("pref_quality", "auto") ?: "auto"
        set(v) = sp.edit().putString("pref_quality", v).apply()

    var hideAdult: Boolean
        get() = sp.getBoolean("hide_adult", true)
        set(v) = sp.edit().putBoolean("hide_adult", v).apply()

    /** Catégories masquées à la main, par type (clés stables, voir Library). */
    fun hiddenCats(kind: Kind): Set<String> = HashSet(sp.getStringSet("hidden_" + kind.name, emptySet()) ?: emptySet())

    fun setCatHidden(kind: Kind, key: String, hidden: Boolean) {
        val set = hiddenCats(kind).toMutableSet()
        if (hidden) set.add(key) else set.remove(key)
        sp.edit().putStringSet("hidden_" + kind.name, set).apply()
    }

    /** Noms personnalisés des catégories : clé → nom. */
    fun catRenames(kind: Kind): Map<String, String> {
        val raw = sp.getString("rename_" + kind.name, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setCatRename(kind: Kind, key: String, name: String?) {
        val m = catRenames(kind).toMutableMap()
        if (name.isNullOrBlank()) m.remove(key) else m[key] = name.trim()
        sp.edit().putString("rename_" + kind.name, JSONObject(m as Map<*, *>).toString()).apply()
    }

    /** Ordre personnalisé des catégories (clés). */
    fun catOrder(kind: Kind): List<String> {
        val raw = sp.getString("order_" + kind.name, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setCatOrder(kind: Kind, keys: List<String>) {
        sp.edit().putString("order_" + kind.name, JSONArray(keys).toString()).apply()
    }

    fun resetOrganize(kind: Kind) {
        sp.edit().remove("hidden_" + kind.name).remove("rename_" + kind.name).remove("order_" + kind.name).apply()
    }
}
