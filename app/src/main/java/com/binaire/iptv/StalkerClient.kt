package com.binaire.iptv

import android.net.Uri
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Abonnement « portail Stalker / MAG ».
 * L'appareil s'identifie par une adresse MAC (ex. 00:1A:79:xx:xx:xx) auprès d'un portail,
 * récupère un jeton, puis la liste des chaînes et des films. Chaque lecture demande au
 * portail un lien temporaire (create_link).
 *
 * Stocké comme : serveur = "stalker:<adresse du portail>", user = MAC, pass = inutilisé.
 */
class StalkerClient(rawServer: String, mac: String) : XtreamClient(rawServer, mac, "") {

    val portal: String = rawServer.removePrefix(STALKER_PREFIX).trim().trimEnd('/')
    val mac: String = mac.trim()

    private var api: String? = null
    private var token: String? = null
    private var profileDone = false

    // cmd Stalker (ex. "ffmpeg http://…") par id d'élément, pour create_link au moment de lire.
    private val cmds = HashMap<String, String>()

    companion object {
        const val STALKER_PREFIX = "stalker:"
        // Les portails MAG vérifient souvent l'« agent » : on se présente comme une box MAG.
        private const val STB_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
            "MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        private const val PAGE_MAX = 60 // garde-fou : 60 pages par catégorie au maximum

        fun isStalker(server: String?) = server?.startsWith(STALKER_PREFIX) == true

        /** Transforme une saisie utilisateur (portail + MAC) en « stalker:<portail> ». */
        fun normalizePortal(input: String): String {
            var u = input.trim()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
            // On garde l'origine ; le bon point d'entrée (portal.php / load.php) est trouvé au login.
            return STALKER_PREFIX + u.trimEnd('/')
        }

        /** MAC saisie librement → format 00:1A:79:AA:BB:CC, ou null si invalide. */
        fun cleanMac(input: String): String? {
            val hex = input.filter { it.isLetterOrDigit() }.uppercase()
            if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
            return hex.chunked(2).joinToString(":")
        }

        /** MAC aléatoire au préfixe des box MAG (00:1A:79:…), à donner au fournisseur. */
        fun randomMac(): String {
            val r = java.security.SecureRandom()
            val tail = (0 until 3).joinToString(":") { "%02X".format(r.nextInt(256)) }
            return "00:1A:79:$tail"
        }
    }

    // ---------- Réseau ----------
    private fun origin(): String {
        val u = Uri.parse(portal)
        val port = if (u.port > 0) ":${u.port}" else ""
        return "${u.scheme}://${u.host}$port"
    }

    private fun cookie() = "mac=${Uri.encode(mac)}; stb_lang=fr; timezone=Europe%2FParis"

    private fun call(params: String): JSONObject {
        val base = api ?: throw XtreamException("Portail non connecté")
        val url = "$base?$params&JsHttpRequest=1-xml"
        val b = Request.Builder().url(url)
            .header("User-Agent", STB_UA)
            .header("Cookie", cookie())
            .header("Referer", origin() + "/c/")
            .header("Accept", "*/*")
        token?.let { b.header("Authorization", "Bearer $it") }
        val resp = try {
            http.newCall(b.build()).execute()
        } catch (e: IOException) {
            throw XtreamException("Portail injoignable. Vérifie l'adresse et ta connexion.")
        }
        resp.use {
            if (it.code == 401 || it.code == 403) throw XtreamException("Portail : MAC refusée ou non autorisée (${it.code}).")
            if (!it.isSuccessful) throw XtreamException("Portail : réponse inattendue (${it.code}).")
            val body = it.body?.string().orEmpty()
            return try {
                JSONObject(body)
            } catch (e: Exception) {
                throw XtreamException("Réponse du portail illisible (ce n'est peut-être pas un portail Stalker).")
            }
        }
    }

    /** Point d'entrée + jeton. On essaie les emplacements habituels du portail. */
    private fun ensureSession() {
        if (token != null && profileDone) return
        val candidates = listOf(
            "${origin()}/portal.php",
            "${origin()}/server/load.php",
            "${origin()}/stalker_portal/server/load.php",
            "$portal/server/load.php"
        )
        var lastError: String? = null
        for (c in candidates) {
            api = c
            try {
                val hs = call("type=stb&action=handshake&token=")
                val t = hs.optJSONObject("js")?.optString("token").orEmpty()
                if (t.isBlank()) { lastError = "pas de jeton"; token = null; continue }
                token = t
                // Profil : certains portails exigent cet appel avant le reste.
                runCatching {
                    call("type=stb&action=get_profile&hd=1&ver=&num_banks=2&stb_type=MAG250&client_type=STB&device_id=&device_id2=&sn=&not_valid_token=0")
                }
                profileDone = true
                return
            } catch (e: XtreamException) {
                lastError = e.message
                token = null
            }
        }
        throw XtreamException(lastError ?: "Impossible de se connecter au portail.")
    }

    private fun jsData(o: JSONObject): JSONArray {
        val js = o.opt("js")
        return when (js) {
            is JSONArray -> js
            is JSONObject -> js.optJSONArray("data") ?: JSONArray()
            else -> JSONArray()
        }
    }

    // ---------- API Binaire ----------
    override fun login(): AccountInfo {
        ensureSession()
        return AccountInfo(status = "active", expDate = null, maxConnections = "1", formats = emptyList())
    }

    override fun categories(kind: Kind): List<Category> {
        ensureSession()
        val (type, action) = when (kind) {
            Kind.LIVE -> "itv" to "get_genres"
            Kind.MOVIE -> "vod" to "get_categories"
            Kind.SERIES -> "series" to "get_categories"
        }
        val arr = jsData(call("type=$type&action=$action"))
        val out = ArrayList<Category>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            if (id == "*" || id == "0") continue // « toutes » : on garde les vraies catégories
            out.add(Category(id, o.optString("title").ifBlank { o.optString("name", "Catégorie") }))
        }
        return out
    }

    override fun streams(kind: Kind): List<Item> {
        ensureSession()
        if (kind == Kind.SERIES) return seriesList()
        val type = if (kind == Kind.LIVE) "itv" else "vod"
        val out = ArrayList<Item>()
        for (cat in categories(kind)) {
            var page = 1
            while (page <= PAGE_MAX) {
                val js = call("type=$type&action=get_ordered_list&genre=${cat.id}&category=${cat.id}&p=$page").optJSONObject("js")
                val arr = js?.optJSONArray("data") ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").ifBlank { continue }
                    val cmd = o.optString("cmd").ifBlank { null }
                    if (cmd != null) cmds[idKey(kind, id)] = cmd
                    out.add(
                        Item(
                            kind = kind,
                            id = id,
                            name = o.optString("name").ifBlank { o.optString("o_name", "Sans nom") },
                            icon = absUrl(o.str("logo") ?: o.str("screenshot_uri")),
                            categoryId = cat.id,
                            ext = if (kind == Kind.MOVIE) "mp4" else null,
                            num = o.str("number"),
                            added = o.optLong("added", 0L),
                            rating = parseRating(o.str("rating_imdb") ?: o.str("rating")),
                            year = o.str("year")
                        )
                    )
                }
                val total = js.optInt("total_items", out.size)
                val perPage = js.optInt("max_page_items", arr.length()).coerceAtLeast(1)
                if (page * perPage >= total) break
                page++
            }
        }
        return out
    }

    private fun seriesList(): List<Item> {
        val out = ArrayList<Item>()
        for (cat in categories(Kind.SERIES)) {
            var page = 1
            while (page <= PAGE_MAX) {
                val js = call("type=series&action=get_ordered_list&category=${cat.id}&p=$page").optJSONObject("js")
                val arr = js?.optJSONArray("data") ?: break
                if (arr.length() == 0) break
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id").ifBlank { continue }
                    o.optString("cmd").ifBlank { null }?.let { cmds[idKey(Kind.SERIES, id)] = it }
                    out.add(
                        Item(
                            kind = Kind.SERIES, id = id,
                            name = o.optString("name", "Série"),
                            icon = absUrl(o.str("screenshot_uri") ?: o.str("logo")),
                            categoryId = cat.id, ext = null, num = null,
                            added = o.optLong("added", 0L),
                            rating = parseRating(o.str("rating_imdb")),
                            year = o.str("year")
                        )
                    )
                }
                val total = js.optInt("total_items", out.size)
                val perPage = js.optInt("max_page_items", arr.length()).coerceAtLeast(1)
                if (page * perPage >= total) break
                page++
            }
        }
        return out
    }

    override fun vodInfo(vodId: String): VodInfo {
        ensureSession()
        // Le portail ne détaille pas toujours un film ; on renvoie l'essentiel.
        return VodInfo(null, null, null, null, null, null, null, "mp4")
    }

    override fun seriesInfo(seriesId: String): SeriesInfo {
        ensureSession()
        // Épisodes d'une série : liste paginée, chaque épisode porte son propre cmd.
        val episodes = LinkedHashMap<String, MutableList<Episode>>()
        var page = 1
        while (page <= PAGE_MAX) {
            val js = call("type=series&action=get_ordered_list&movie_id=$seriesId&season_id=0&episode_id=0&p=$page").optJSONObject("js")
            val arr = js?.optJSONArray("data") ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { continue }
                o.optString("cmd").ifBlank { null }?.let { cmds[idKey(Kind.SERIES, id)] = it }
                val season = o.str("season_number") ?: o.optJSONObject("series")?.optString("season") ?: "1"
                val epNum = o.str("episode_number") ?: o.str("number") ?: (i + 1).toString()
                episodes.getOrPut(season) { mutableListOf() }.add(
                    Episode(id, season, epNum, o.optString("name", "Épisode $epNum"), "mp4", absUrl(o.str("screenshot_uri")), 0)
                )
            }
            val total = js.optInt("total_items", 0)
            val perPage = js.optInt("max_page_items", arr.length()).coerceAtLeast(1)
            if (total == 0 || page * perPage >= total) break
            page++
        }
        return SeriesInfo(
            name = "", plot = null, cover = null, backdrop = null, genre = null, year = null, rating = null,
            seasons = episodes.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE },
            episodes = episodes
        )
    }

    override fun shortEpg(streamId: String): List<EpgEntry> = emptyList()

    // ---------- Lecture : create_link ----------
    private fun idKey(kind: Kind, id: String) = "${kind.name}:$id"

    private fun resolve(kind: Kind, id: String): String {
        ensureSession()
        val cmd = cmds[idKey(kind, id)] ?: throw XtreamException("Élément introuvable dans le portail")
        val type = when (kind) { Kind.LIVE -> "itv"; else -> "vod" }
        val enc = URLEncoder.encode(cmd, "UTF-8")
        val js = call("type=$type&action=create_link&cmd=$enc&forced_storage=0&disable_ad=0").optJSONObject("js")
        val raw = js?.optString("cmd").orEmpty().ifBlank { cmd }
        // Le portail renvoie souvent « ffmpeg http://… » ou « http://… » : on garde l'URL.
        return raw.substringAfter(' ', raw).trim().ifBlank { raw.trim() }
    }

    override fun liveUrl(id: String, ext: String) = resolve(Kind.LIVE, id)
    override fun movieUrl(id: String, ext: String) = resolve(Kind.MOVIE, id)
    override fun episodeUrl(id: String, ext: String) = resolve(Kind.SERIES, id)

    private fun absUrl(u: String?): String? {
        if (u.isNullOrBlank()) return null
        return if (u.startsWith("http", true)) u else origin() + "/" + u.trimStart('/')
    }
}
