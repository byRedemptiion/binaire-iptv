package com.binaire.iptv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Guide TV « en ce moment / ensuite », mis en cache et limité à quelques requêtes à la fois. */
object EpgCache {
    private class Entry(val at: Long, val list: List<EpgEntry>)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val gate = Semaphore(4)
    private const val TTL = 10 * 60 * 1000L

    fun peek(streamId: String): List<EpgEntry>? {
        val e = cache[streamId] ?: return null
        val now = System.currentTimeMillis()
        if (now - e.at > TTL) return null
        val first = e.list.firstOrNull()
        if (first != null && first.end > 0 && first.end * 1000 < now) return null
        return e.list
    }

    suspend fun get(client: XtreamClient, streamId: String): List<EpgEntry> {
        peek(streamId)?.let { return it }
        return gate.withPermit {
            peek(streamId) ?: withContext(Dispatchers.IO) {
                runCatching { client.shortEpg(streamId) }.getOrDefault(emptyList())
            }.also { cache[streamId] = Entry(System.currentTimeMillis(), it) }
        }
    }

    fun clear() = cache.clear()

    private val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)
    fun time(t: Long): String = if (t > 0) hm.format(Date(t * 1000)) else "--:--"

    /** Avancement du programme en cours (0..1). */
    fun progress(e: EpgEntry): Float {
        if (e.start <= 0 || e.end <= e.start) return 0f
        val now = System.currentTimeMillis() / 1000
        return ((now - e.start).toFloat() / (e.end - e.start)).coerceIn(0f, 1f)
    }
}
