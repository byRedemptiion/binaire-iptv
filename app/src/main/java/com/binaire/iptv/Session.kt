package com.binaire.iptv

import java.util.concurrent.ConcurrentHashMap

/** État partagé entre les écrans (en mémoire, rechargé au démarrage). */
object Session {
    @Volatile var client: XtreamClient? = null
    @Volatile var account: AccountInfo? = null
    @Volatile var ready = false
    @Volatile var updateChecked = false

    val categories = ConcurrentHashMap<Kind, List<Category>>()
    val streams = ConcurrentHashMap<Kind, List<Item>>()

    var playlist: List<PlayItem> = emptyList()
    var playIndex = 0

    fun clear() {
        client = null
        account = null
        ready = false
        categories.clear()
        streams.clear()
        playlist = emptyList()
        playIndex = 0
    }

    fun items(kind: Kind): List<Item> = streams[kind] ?: emptyList()
    fun cats(kind: Kind): List<Category> = categories[kind] ?: emptyList()

    /** Flux direct : HLS si le serveur l'autorise, sinon MPEG-TS, avec l'autre format en secours. */
    fun livePlayItem(c: XtreamClient, item: Item, fallback: Boolean): PlayItem {
        val hls = account?.formats?.contains("m3u8") == true
        val primary = if (hls) "m3u8" else "ts"
        val other = if (hls) "ts" else "m3u8"
        return PlayItem(
            title = item.name,
            url = c.liveUrl(item.id, primary),
            isLive = true,
            liveId = item.id,
            fallbackUrl = if (fallback) c.liveUrl(item.id, other) else null
        )
    }
}

/** Statut renvoyé par le fournisseur (souvent en anglais), traduit. */
internal fun statusFr(raw: String): String = when (raw.trim().lowercase()) {
    "", "active" -> "actif"
    "expired" -> "expiré"
    "disabled" -> "désactivé"
    "banned" -> "suspendu"
    "trial" -> "d'essai"
    else -> raw
}
