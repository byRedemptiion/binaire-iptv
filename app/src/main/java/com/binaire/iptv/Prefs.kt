package com.binaire.iptv

import android.content.Context

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

    var livePreview: Boolean
        get() = sp.getBoolean("live_preview", true)
        set(v) = sp.edit().putBoolean("live_preview", v).apply()
}
