package com.binaire.iptv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Reprise de lecture, épisodes vus et « Ma liste », sur l'appareil. */
class WatchStore private constructor(context: Context, profileId: String?) {

    constructor(context: Context) : this(context, null)

    companion object {
        fun forProfile(context: Context, id: String) = WatchStore(context, id)
    }

    fun wipe() {
        sp.edit().clear().apply()
    }


    data class Resume(
        val meta: WatchMeta,
        val url: String,
        val position: Long,
        val duration: Long,
        val updated: Long
    ) {
        val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val remainingMin: Long get() = ((duration - position).coerceAtLeast(0) / 60000)
    }

    private val sp = context.applicationContext.getSharedPreferences(
        Profiles.watchFile(profileId ?: Profiles.currentId(context)), Context.MODE_PRIVATE
    )

    // ---------- Reprise ----------
    fun resumes(): List<Resume> {
        val raw = sp.getString("resumes", null) ?: return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Resume>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val m = WatchMeta(
                key = o.optString("key"),
                kind = o.optString("kind"),
                title = o.optString("title"),
                image = o.str("image"),
                contentId = o.optString("contentId"),
                episodeId = o.str("episodeId"),
                season = o.str("season"),
                episode = o.str("episode"),
                ext = o.str("ext")
            )
            out.add(Resume(m, o.optString("url"), o.optLong("pos"), o.optLong("dur"), o.optLong("updated")))
        }
        return out.sortedByDescending { it.updated }
    }

    fun resume(key: String): Resume? = resumes().firstOrNull { it.meta.key == key }

    fun movieResumes() = resumes().filter { it.meta.kind == "movie" }

    /** Dernier épisode en cours pour chaque série (une entrée par série). */
    fun episodeResumes(): List<Resume> =
        resumes().filter { it.meta.kind == "episode" }.distinctBy { it.meta.contentId }

    fun lastEpisodeOf(seriesId: String): Resume? =
        resumes().firstOrNull { it.meta.kind == "episode" && it.meta.contentId == seriesId }

    fun save(meta: WatchMeta, url: String, position: Long, duration: Long) {
        val list = resumes().filter { it.meta.key != meta.key }.toMutableList()
        // Pour une série, on ne garde que l'épisode le plus récent.
        if (meta.kind == "episode") list.removeAll { it.meta.kind == "episode" && it.meta.contentId == meta.contentId }
        list.add(0, Resume(meta, url, position, duration, System.currentTimeMillis()))
        write(list.take(60))
    }

    fun remove(key: String) {
        write(resumes().filter { it.meta.key != key })
    }

    private fun write(list: List<Resume>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("key", r.meta.key); put("kind", r.meta.kind); put("title", r.meta.title)
                put("image", r.meta.image ?: JSONObject.NULL); put("contentId", r.meta.contentId)
                put("episodeId", r.meta.episodeId ?: JSONObject.NULL); put("season", r.meta.season ?: JSONObject.NULL)
                put("episode", r.meta.episode ?: JSONObject.NULL); put("ext", r.meta.ext ?: JSONObject.NULL)
                put("url", r.url); put("pos", r.position); put("dur", r.duration); put("updated", r.updated)
            })
        }
        sp.edit().putString("resumes", arr.toString()).apply()
    }

    // ---------- Épisodes vus ----------
    fun isWatched(episodeId: String) = sp.getStringSet("watched", emptySet())?.contains(episodeId) == true

    fun markWatched(episodeId: String) {
        val set = HashSet(sp.getStringSet("watched", emptySet()) ?: emptySet())
        set.add(episodeId)
        sp.edit().putStringSet("watched", set).apply()
    }

    // ---------- Ma liste ----------
    private fun listKey(kind: Kind, id: String) = "${kind.name}:$id"

    fun inMyList(kind: Kind, id: String) = sp.getStringSet("mylist", emptySet())?.contains(listKey(kind, id)) == true

    fun toggleMyList(kind: Kind, id: String): Boolean {
        val set = HashSet(sp.getStringSet("mylist", emptySet()) ?: emptySet())
        val k = listKey(kind, id)
        val added = if (set.contains(k)) { set.remove(k); false } else { set.add(k); true }
        sp.edit().putStringSet("mylist", set).apply()
        return added
    }

    fun myListIds(kind: Kind): Set<String> =
        (sp.getStringSet("mylist", emptySet()) ?: emptySet())
            .filter { it.startsWith(kind.name + ":") }
            .map { it.substringAfter(':') }
            .toSet()
}
