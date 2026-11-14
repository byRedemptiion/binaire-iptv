package com.binaire.iptv

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Abonnement « portail Stalker / MAG ».
 * L'appareil s'identifie par une adresse MAC (ex. 00:1A:79:xx:xx:xx) auprès d'un portail,
 * récupère un jeton, puis la liste des chaînes, films et séries. Chaque lecture demande au
 * portail un lien temporaire (create_link).
 *
 * Variantes de portails prises en charge (toutes « standard », aucun contournement
 * d'authentification propre à un fournisseur) :
 *   - point d'entrée portal.php ou server/load.php (détecté automatiquement) ;
 *   - jeton via handshake (envoyé ensuite en Authorization: Bearer et dans le cookie) ;
 *   - catalogues « plats » (épisodes paginés) ou « hiérarchiques » (séries → saisons → épisodes).
 *
 * Stocké comme : serveur = "stalker:<adresse du portail>", user = MAC, pass = inutilisé.
 * Format inchangé depuis la v1.7.0 : les comptes déjà enregistrés restent compatibles.
 */
class StalkerClient(rawServer: String, macInput: String) : XtreamClient(rawServer, macInput, "") {

    val portal: String = rawServer.removePrefix(StalkerProtocol.PREFIX).trim().trimEnd('/')
    val mac: String = StalkerProtocol.cleanMac(macInput) ?: macInput.trim()

    private var api: String? = null
    private var token: String? = null
    private var sessionReady = false

    // cmd Stalker par id d'élément, pour create_link au moment de lire.
    private val cmds = HashMap<String, String>()

    /** Délégués conservés pour le reste de l'appli (LoginActivity, SettingsActivity, Xtream.create). */
    companion object {
        const val STALKER_PREFIX = StalkerProtocol.PREFIX
        private const val STB_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
            "MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        fun isStalker(server: String?) = StalkerProtocol.isStalker(server)
        fun normalizePortal(input: String) = StalkerProtocol.normalizePortal(input)
        fun cleanMac(input: String) = StalkerProtocol.cleanMac(input)
        fun randomMac() = StalkerProtocol.randomMac()
    }

    // ---------- Réseau ----------
    private fun cookie() = "mac=${URLEncoder.encode(mac, "UTF-8")}; stb_lang=fr; timezone=Europe%2FParis"

    /** Une requête HTTP brute vers le point d'entrée courant. */
    private fun raw(params: String): StalkerProtocol.Parsed {
        val base = api ?: throw XtreamException("Portail non connecté")
        val b = Request.Builder().url(StalkerProtocol.buildUrl(base, params))
            .header("User-Agent", STB_UA)
            .header("Cookie", cookie())
            .header("Referer", StalkerProtocol.origin(portal) + "/c/")
            .header("Accept", "*/*")
        token?.let { b.header("Authorization", "Bearer $it") }
        val resp = try {
            http.newCall(b.build()).execute()
        } catch (e: IOException) {
            throw XtreamException("Portail injoignable. Vérifie l'adresse et ta connexion.")
        }
        resp.use {
            // 401/403 : on laisse parse() voir le corps au cas où, mais on retient l'indice d'auth.
            if (it.code == 403 || it.code == 401) return StalkerProtocol.Parsed(
                StalkerProtocol.ResultKind.TOKEN_EXPIRED, null, "HTTP ${it.code}"
            )
            if (!it.isSuccessful) throw XtreamException("Portail : réponse inattendue (${it.code}).")
            return StalkerProtocol.parse(it.body?.string().orEmpty())
        }
    }

    /**
     * Requête « métier » : s'assure d'avoir une session, interprète l'enveloppe,
     * et re-tente UNE fois après un nouveau handshake si le jeton a expiré.
     */
    private fun call(params: String, allowReauth: Boolean = true): Any? {
        ensureSession()
        val r = raw(params)
        return when (r.kind) {
            StalkerProtocol.ResultKind.OK, StalkerProtocol.ResultKind.EMPTY -> r.js
            StalkerProtocol.ResultKind.TOKEN_EXPIRED -> {
                if (!allowReauth) throw XtreamException("Session du portail expirée.")
                sessionReady = false
                token = null
                ensureSession()
                val r2 = raw(params)
                if (r2.kind == StalkerProtocol.ResultKind.OK || r2.kind == StalkerProtocol.ResultKind.EMPTY) r2.js
                else throw XtreamException(humanError(r2))
            }
            StalkerProtocol.ResultKind.BLOCKED ->
                throw XtreamException("Portail : cet appareil (MAC) est bloqué ou l'abonnement a expiré.")
            StalkerProtocol.ResultKind.ERROR -> throw XtreamException(humanError(r))
        }
    }

    private fun humanError(r: StalkerProtocol.Parsed) =
        "Portail : " + (r.message?.takeIf { it.isNotBlank() } ?: "réponse inattendue") + "."

    /** Point d'entrée + jeton. On essaie les emplacements habituels ; on ne fabrique aucune signature. */
    private fun ensureSession() {
        if (sessionReady && token != null) return
        var lastError: String? = null
        for (c in StalkerProtocol.endpointCandidates(portal)) {
            api = c
            try {
                val hs = raw("type=stb&action=handshake&token=")
                if (hs.kind == StalkerProtocol.ResultKind.BLOCKED)
                    throw XtreamException("Portail : appareil bloqué ou abonnement expiré.")
                val t = StalkerProtocol.token(hs.js)
                if (t == null) { lastError = "pas de jeton"; token = null; continue }
                token = t
                // get_profile : exigé par certains portails. On n'envoie PAS de signature ni d'ID matériel inventés.
                val prof = raw("type=stb&action=get_profile&hd=1&num_banks=2&stb_type=MAG250&client_type=STB&not_valid_token=0")
                when (prof.kind) {
                    StalkerProtocol.ResultKind.BLOCKED ->
                        throw XtreamException("Portail : appareil bloqué ou abonnement expiré.")
                    StalkerProtocol.ResultKind.TOKEN_EXPIRED -> { token = null; lastError = "profil refusé"; continue }
                    else -> { sessionReady = true; return }
                }
            } catch (e: XtreamException) {
                lastError = e.message
                token = null
                // Un « bloqué » est définitif : inutile d'essayer les autres points d'entrée.
                if (e.message?.contains("bloqué") == true) throw e
            }
        }
        throw XtreamException(lastError ?: "Impossible de se connecter au portail.")
    }

    // ---------- API Binaire ----------
    override fun login(): AccountInfo {
        ensureSession()
        return AccountInfo(status = "active", expDate = null, maxConnections = "1", formats = emptyList())
    }

    override fun categories(kind: Kind): List<Category> {
        val action = when (kind) {
            Kind.LIVE -> "itv" to "get_genres"
            Kind.MOVIE -> "vod" to "get_categories"
            Kind.SERIES -> "series" to "get_categories"
        }
        val arr = StalkerProtocol.dataArray(call("type=${action.first}&action=${action.second}"))
        val out = ArrayList<Category>()
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank() || id == "*" || id == "0" || !seen.add(id)) continue
            out.add(Category(id, o.optString("title").ifBlank { o.optString("name", "Catégorie") }))
        }
        return out
    }

    override fun streams(kind: Kind): List<Item> {
        if (kind == Kind.SERIES) return seriesList()
        val type = if (kind == Kind.LIVE) "itv" else "vod"
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (cat in categories(kind)) {
            paginate("type=$type&action=get_ordered_list&genre=${cat.id}&category=${cat.id}") { o ->
                val id = o.optString("id")
                if (id.isBlank() || !seen.add(idKey(kind, id))) return@paginate
                o.optString("cmd").ifBlank { null }?.let { cmds[idKey(kind, id)] = it }
                out.add(
                    Item(
                        kind = kind, id = id,
                        name = o.optString("name").ifBlank { o.optString("o_name", "Sans nom") },
                        icon = StalkerProtocol.absUrl(portal, o.str("logo") ?: o.str("screenshot_uri")),
                        categoryId = cat.id,
                        ext = if (kind == Kind.MOVIE) "mp4" else null,
                        num = o.str("number"),
                        added = o.optLong("added", 0L),
                        rating = parseRating(o.str("rating_imdb") ?: o.str("rating")),
                        year = o.str("year")
                    )
                )
            }
        }
        return out
    }

    private fun seriesList(): List<Item> {
        val out = ArrayList<Item>()
        val seen = HashSet<String>()
        for (cat in categories(Kind.SERIES)) {
            paginate("type=series&action=get_ordered_list&category=${cat.id}") { o ->
                val id = o.optString("id")
                if (id.isBlank() || !seen.add(id)) return@paginate
                o.optString("cmd").ifBlank { null }?.let { cmds[idKey(Kind.SERIES, id)] = it }
                out.add(
                    Item(
                        kind = Kind.SERIES, id = id,
                        name = o.optString("name", "Série"),
                        icon = StalkerProtocol.absUrl(portal, o.str("screenshot_uri") ?: o.str("logo")),
                        categoryId = cat.id, ext = null, num = null,
                        added = o.optLong("added", 0L),
                        rating = parseRating(o.str("rating_imdb")),
                        year = o.str("year")
                    )
                )
            }
        }
        return out
    }

    /** Boucle de pagination commune : dédoublonnage et arrêts gérés par StalkerProtocol. */
    private inline fun paginate(baseParams: String, onItem: (JSONObject) -> Unit) {
        var page = 1
        var loaded = 0
        while (true) {
            val js = call("$baseParams&p=$page") as? JSONObject ?: break
            val arr = js.optJSONArray("data") ?: break
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(onItem)
            loaded += arr.length()
            val total = js.optInt("total_items", 0)
            val perPage = js.optInt("max_page_items", arr.length())
            if (StalkerProtocol.paginationDone(arr.length(), perPage, loaded, total, page)) break
            page++
        }
    }

    override fun vodInfo(vodId: String): VodInfo {
        ensureSession()
        return VodInfo(null, null, null, null, null, null, null, "mp4")
    }

    override fun seriesInfo(seriesId: String): SeriesInfo {
        val episodes = LinkedHashMap<String, MutableList<Episode>>()

        // Structure « plate » : chaque entrée série porte un tableau "series" = numéros d'épisodes,
        // et un seul cmd ; create_link reçoit alors le numéro d'épisode via series=<n>.
        val flat = flatEpisodes(seriesId)
        if (flat != null) return flat

        // Structure « hiérarchique » : on liste les épisodes (paginés).
        paginate("type=series&action=get_ordered_list&movie_id=$seriesId&season_id=0&episode_id=0") { o ->
            val id = o.optString("id")
            if (id.isBlank()) return@paginate
            o.optString("cmd").ifBlank { null }?.let { cmds[idKey(Kind.SERIES, id)] = it }
            val season = o.str("season_number") ?: o.optJSONObject("series")?.optString("season") ?: "1"
            val epNum = o.str("episode_number") ?: o.str("number") ?: (episodes[season]?.size ?: 0).plus(1).toString()
            episodes.getOrPut(season) { mutableListOf() }.add(
                Episode(id, season, epNum, o.optString("name", "Épisode $epNum"), "mp4",
                    StalkerProtocol.absUrl(portal, o.str("screenshot_uri")), 0)
            )
        }
        return buildSeries(episodes)
    }

    /** Variante « plate » : l'entrée série contient directement la liste des épisodes. */
    private fun flatEpisodes(seriesId: String): SeriesInfo? {
        val js = call("type=series&action=get_ordered_list&movie_id=$seriesId&p=1") as? JSONObject ?: return null
        val arr = js.optJSONArray("data") ?: return null
        val episodes = LinkedHashMap<String, MutableList<Episode>>()
        var foundSeriesArray = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val epList = o.optJSONArray("series") ?: continue
            foundSeriesArray = true
            val season = o.str("season_number") ?: "1"
            val cmd = o.optString("cmd").ifBlank { null }
            for (j in 0 until epList.length()) {
                val epNum = epList.optInt(j, j + 1).toString()
                val epId = "$seriesId:s$season:e$epNum"
                if (cmd != null) cmds[idKey(Kind.SERIES, epId)] = "$cmd|series=$epNum"
                episodes.getOrPut(season) { mutableListOf() }.add(
                    Episode(epId, season, epNum, "Épisode $epNum", "mp4", null, 0)
                )
            }
        }
        return if (foundSeriesArray) buildSeries(episodes) else null
    }

    private fun buildSeries(episodes: Map<String, List<Episode>>) = SeriesInfo(
        name = "", plot = null, cover = null, backdrop = null, genre = null, year = null, rating = null,
        seasons = episodes.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE },
        episodes = episodes.mapValues { (_, l) -> l.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE } }
    )

    override fun shortEpg(streamId: String): List<EpgEntry> = emptyList()

    // ---------- Lecture : create_link ----------
    private fun idKey(kind: Kind, id: String) = "${kind.name}:$id"

    private fun resolve(kind: Kind, id: String): String {
        val stored = cmds[idKey(kind, id)] ?: throw XtreamException("Élément introuvable dans le portail")
        // Variante plate : "cmd|series=<n>" → on passe le numéro d'épisode à create_link.
        val cmd = stored.substringBefore("|series=")
        val seriesParam = if ("|series=" in stored) "&series=${stored.substringAfter("|series=")}" else ""
        val type = if (kind == Kind.LIVE) "itv" else "vod"
        val enc = URLEncoder.encode(cmd, "UTF-8")
        val js = call("type=$type&action=create_link&cmd=$enc$seriesParam&forced_storage=0&disable_ad=0") as? JSONObject
        val resolved = js?.optString("cmd").orEmpty()
        return StalkerProtocol.normalizePlayUrl(resolved)
            ?: StalkerProtocol.normalizePlayUrl(cmd) // certains portails renvoient déjà une URL dans le cmd d'origine
            ?: throw XtreamException("Le portail n'a pas renvoyé de lien de lecture valide.")
    }

    override fun liveUrl(id: String, ext: String) = resolve(Kind.LIVE, id)
    override fun movieUrl(id: String, ext: String) = resolve(Kind.MOVIE, id)
    override fun episodeUrl(id: String, ext: String) = resolve(Kind.SERIES, id)
}
