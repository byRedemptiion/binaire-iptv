package com.binaire.iptv

import okhttp3.Request
import java.io.IOException
import java.util.EnumMap

/**
 * Abonnement « lien M3U simple » : une liste de liens téléchargée en une fois.
 * Chaînes, films et séries sont reconnus d'après les liens, les groupes et les titres (S01E02…).
 */
class M3uClient(raw: String) : XtreamClient(raw, "m3u", "m3u") {

    val playlistUrl: String = raw.removePrefix(XtreamClient.M3U_PREFIX).trim()

    private var loaded = false
    private val cats = EnumMap<Kind, MutableList<Category>>(Kind::class.java)
    private val items = EnumMap<Kind, MutableList<Item>>(Kind::class.java)
    private val urls = HashMap<String, String>()
    private val icons = HashMap<String, String>()
    private val seriesEpisodes = HashMap<String, MutableList<Episode>>()

    private companion object {
        val ATTR = Regex("""([\w-]+)="([^"]*)"""")
        val SXXEXX = Regex("""(?i)(?:^|[\s._\-|(\[])S(\d{1,2})\s*[ ._-]?\s*E(\d{1,3})(?![0-9])""")
        val ALT_EP = Regex("""(?i)(?:^|[\s._\-|])(\d{1,2})x(\d{1,3})(?![0-9])""")
        val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "webm", "flv", "mpg", "mpeg")
        val VOD_GROUP = Regex("""(?i)\b(vod|films?|movies?|cin[eé]ma)\b""")
        val SERIES_GROUP = Regex("""(?i)\b(s[eé]ries?|shows?|saisons?)\b""")
    }

    private data class Entry(val name: String, val logo: String?, val group: String, val url: String)

    @Synchronized
    private fun load() {
        if (loaded) return
        val req = Request.Builder().url(playlistUrl).build()
        val entries = ArrayList<Entry>()
        try {
            XtreamClient.http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw XtreamException(
                    if (resp.code == 401 || resp.code == 403) "Lien M3U refusé par le serveur (${resp.code})"
                    else "Le lien M3U ne répond pas (${resp.code})"
                )
                val reader = resp.body?.charStream()?.buffered() ?: throw XtreamException("Liste M3U vide")
                var name: String? = null
                var logo: String? = null
                var group: String? = null
                while (true) {
                    val line = reader.readLine()?.trim() ?: break
                    if (line.isEmpty()) continue
                    when {
                        line.startsWith("#EXTINF", true) -> {
                            val attrs = HashMap<String, String>()
                            ATTR.findAll(line).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
                            val lastQuote = line.lastIndexOf('"')
                            val comma = line.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
                            name = (if (comma >= 0) line.substring(comma + 1).trim() else "").ifBlank { attrs["tvg-name"] ?: "Sans nom" }
                            logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                            group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                        }
                        line.startsWith("#EXTGRP:", true) -> group = group ?: line.substringAfter(':').trim().ifBlank { null }
                        line.startsWith("#") -> Unit
                        else -> {
                            entries.add(Entry(name ?: line.substringAfterLast('/'), logo, group ?: "Sans catégorie", line))
                            name = null; logo = null; group = null
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw XtreamException("Impossible de télécharger la liste M3U. Vérifie le lien et ta connexion.")
        }
        if (entries.isEmpty()) throw XtreamException("Ce lien ne contient aucune chaîne : ce n'est peut-être pas une liste M3U.")
        classify(entries)
        loaded = true
    }

    private fun classify(entries: List<Entry>) {
        for (k in Kind.values()) { cats[k] = mutableListOf(); items[k] = mutableListOf() }
        val catIds = HashMap<String, String>() // "K|groupe" → id
        fun catId(kind: Kind, group: String): String = catIds.getOrPut("${kind.name}|$group") {
            val id = kind.name.first().lowercase() + catIds.size
            cats[kind]!!.add(Category(id, group))
            id
        }
        val seriesIds = HashMap<String, String>() // "groupe|titre" → id
        var counter = 0
        for (e in entries) {
            counter++
            val path = e.url.substringBefore('?').lowercase()
            val ext = path.substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 2..5 }
            val ep = SXXEXX.find(e.name) ?: ALT_EP.find(e.name)
            val kind = when {
                path.contains("/series/") || (ep != null && (ext in VIDEO_EXT || SERIES_GROUP.containsMatchIn(e.group) || !path.contains("/live/"))) -> Kind.SERIES
                path.contains("/movie/") || ext in VIDEO_EXT || (VOD_GROUP.containsMatchIn(e.group) && !path.contains("/live/")) -> Kind.MOVIE
                else -> Kind.LIVE
            }
            when (kind) {
                Kind.LIVE, Kind.MOVIE -> {
                    val id = (if (kind == Kind.LIVE) "l" else "m") + counter
                    urls[id] = e.url
                    e.logo?.let { icons[id] = it }
                    items[kind]!!.add(
                        Item(
                            kind, id, e.name, e.logo, catId(kind, e.group),
                            if (kind == Kind.MOVIE) (ext ?: "mp4") else null,
                            if (kind == Kind.LIVE) items[kind]!!.size.plus(1).toString() else null,
                            counter.toLong(), null
                        )
                    )
                }
                Kind.SERIES -> {
                    val show = (if (ep != null) e.name.substring(0, ep.range.first) else e.name)
                        .trim().trimEnd('-', '|', ':', '.', '_', '(', '[').trim().ifBlank { e.name }
                    val key = "${e.group}|${show.lowercase()}"
                    val sid = seriesIds.getOrPut(key) {
                        val id = "s" + counter
                        e.logo?.let { icons[id] = it }
                        items[Kind.SERIES]!!.add(Item(Kind.SERIES, id, show, e.logo, catId(Kind.SERIES, e.group), null, null, counter.toLong(), null))
                        id
                    }
                    val list = seriesEpisodes.getOrPut(sid) { mutableListOf() }
                    val epId = "e$counter"
                    urls[epId] = e.url
                    list.add(
                        Episode(
                            id = epId,
                            season = ep?.groupValues?.get(1)?.trimStart('0')?.ifBlank { "0" } ?: "1",
                            number = ep?.groupValues?.get(2)?.trimStart('0')?.ifBlank { "0" } ?: (list.size + 1).toString(),
                            title = e.name,
                            ext = ext ?: "mp4",
                            image = e.logo,
                            durationSecs = 0
                        )
                    )
                }
            }
        }
    }

    override fun login(): AccountInfo {
        load()
        return AccountInfo(status = "active", expDate = null, maxConnections = null, formats = emptyList())
    }

    override fun categories(kind: Kind): List<Category> { load(); return cats[kind] ?: emptyList() }

    override fun streams(kind: Kind): List<Item> { load(); return items[kind] ?: emptyList() }

    override fun vodInfo(vodId: String): VodInfo {
        load()
        val url = urls[vodId] ?: throw XtreamException("Film introuvable dans la liste")
        val ext = url.substringBefore('?').substringAfterLast('.', "").takeIf { it.length in 2..5 }
        return VodInfo(null, null, null, null, null, null, icons[vodId], ext)
    }

    override fun seriesInfo(seriesId: String): SeriesInfo {
        load()
        val eps = seriesEpisodes[seriesId] ?: throw XtreamException("Série introuvable dans la liste")
        val bySeason = eps.groupBy { it.season }
            .mapValues { (_, l) -> l.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE } }
        val item = items[Kind.SERIES]?.firstOrNull { it.id == seriesId }
        return SeriesInfo(
            name = item?.name ?: "",
            plot = null,
            cover = icons[seriesId],
            backdrop = null,
            genre = null,
            year = null,
            rating = null,
            seasons = bySeason.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE },
            episodes = bySeason
        )
    }

    override fun shortEpg(streamId: String): List<EpgEntry> = emptyList()

    override fun liveUrl(id: String, ext: String) = urls[id] ?: ""
    override fun movieUrl(id: String, ext: String) = urls[id] ?: ""
    override fun episodeUrl(id: String, ext: String) = urls[id] ?: ""
}
