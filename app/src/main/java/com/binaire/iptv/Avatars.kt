package com.binaire.iptv

import android.content.Context

/**
 * Bibliothèque d'avatars embarquée dans l'app (assets/avatars/<Univers>/<fichier>.webp).
 * Un avatar est référencé par son chemin relatif, ex. « Anime/anime_001.webp ».
 */
object Avatars {

    private const val DIR = "avatars"

    /** Univers, dans l'ordre d'affichage. */
    val categories = listOf("Anime", "Animaux", "Chibi", "Fantasy", "Tech")

    private var cache: List<String>? = null

    /** Tous les avatars, groupés et triés par univers puis par nom de fichier. */
    fun all(ctx: Context): List<String> {
        cache?.let { return it }
        val out = mutableListOf<String>()
        val am = ctx.applicationContext.assets
        for (cat in categories) {
            val files = runCatching { am.list("$DIR/$cat") }.getOrNull()?.toList().orEmpty()
                .filter { it.endsWith(".webp", true) }
                .sorted()
            for (f in files) out.add("$cat/$f")
        }
        cache = out
        return out
    }

    /** URI chargeable par Coil pour un avatar embarqué. */
    fun uri(path: String) = "file:///android_asset/$DIR/$path"

    /** Vrai si le chemin correspond à un avatar réellement présent. */
    fun exists(ctx: Context, path: String?): Boolean =
        path != null && path in all(ctx)
}
