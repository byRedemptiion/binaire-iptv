package com.binaire.iptv

import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class Kind { LIVE, MOVIE, SERIES }

data class Category(val id: String, val name: String)

data class Item(
    val kind: Kind,
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val ext: String?,
    val num: String?,
    val added: Long,
    val rating: Double?,
    /** Catégories (fusionnées) où apparaît l'élément ; vide = seulement categoryId. */
    val cats: Set<String> = emptySet(),
    /** Autres versions de la même chaîne (ids de flux), de la meilleure à la moins bonne. */
    val variants: List<String> = emptyList(),
    /** Badge affiché : FHD, 4K, VOSTFR… */
    val quality: String? = null,
    val year: String? = null
) {
    fun inCat(id: String) = if (cats.isEmpty()) categoryId == id else id in cats
    val allIds: List<String> get() = listOf(id) + variants
}

data class VodInfo(
    val plot: String?,
    val genre: String?,
    val year: String?,
    val duration: String?,
    val rating: Double?,
    val backdrop: String?,
    val poster: String?,
    val ext: String?
)

data class Episode(
    val id: String,
    val season: String,
    val number: String,
    val title: String,
    val ext: String,
    val image: String?,
    val durationSecs: Long
)

data class SeriesInfo(
    val name: String,
    val plot: String?,
    val cover: String?,
    val backdrop: String?,
    val genre: String?,
    val year: String?,
    val rating: Double?,
    val seasons: List<String>,
    val episodes: Map<String, List<Episode>>
)

data class EpgEntry(val title: String, val description: String, val start: Long, val end: Long)

data class AccountInfo(val status: String, val expDate: Long?, val maxConnections: String?, val formats: List<String>)

/** Ce que le lecteur doit jouer, et de quoi enregistrer la reprise. */
data class PlayItem(
    val title: String,
    val url: String,
    val isLive: Boolean,
    val liveId: String? = null,
    val fallbackUrl: String? = null,
    val watch: WatchMeta? = null,
    /** Autres adresses à essayer si la lecture échoue (autres versions de la chaîne). */
    val alternates: List<String> = emptyList()
) {
    val attempts: List<String> get() = listOfNotNull(url, fallbackUrl) + alternates
}

data class WatchMeta(
    val key: String,
    val kind: String,           // "movie" ou "episode"
    val title: String,
    val image: String?,
    val contentId: String,      // id du film ou de la série
    val episodeId: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val ext: String? = null
)

class XtreamException(message: String) : Exception(message)

internal fun JSONObject.str(key: String): String? {
    if (isNull(key)) return null
    val s = optString(key, "").trim()
    return if (s.isEmpty() || s == "null") null else s
}

/** Premier texte d'un champ qui peut être une chaîne ou un tableau (ex. backdrop_path). */
internal fun JSONObject.firstStr(key: String): String? {
    if (isNull(key)) return null
    return when (val v = opt(key)) {
        is JSONArray -> {
            for (i in 0 until v.length()) {
                val s = v.optString(i, "").trim()
                if (s.isNotEmpty() && s != "null") return s
            }
            null
        }
        else -> str(key)
    }
}

internal fun parseRating(s: String?): Double? {
    val d = s?.replace(',', '.')?.toDoubleOrNull() ?: return null
    return if (d > 0) d else null
}

internal fun parseDurationSecs(o: JSONObject?): Long {
    if (o == null) return 0
    o.str("duration_secs")?.toLongOrNull()?.let { return it }
    val d = o.str("duration") ?: return 0
    val parts = d.split(":").mapNotNull { it.trim().toLongOrNull() }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> 0
    }
}

/** Client pour l'API "player_api.php" des serveurs Xtream Codes. */
class XtreamClient(rawServer: String, val user: String, val pass: String) {

    val server: String = normalize(rawServer)

    companion object {
        const val USER_AGENT = "BinaireIPTV/1.0 (Linux; Android)"

        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
                }
                .build()
        }

        fun normalize(input: String): String {
            var u = input.trim()
            if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
            u = u.trimEnd('/')
            for (suffix in listOf("/player_api.php", "/get.php", "/c")) {
                if (u.endsWith(suffix)) u = u.removeSuffix(suffix)
            }
            return u.trimEnd('/')
        }
    }

    private fun request(action: String?, extra: Map<String, String> = emptyMap()): String {
        val base = "$server/player_api.php".toHttpUrlOrNull()
            ?: throw XtreamException("Adresse du serveur invalide")
        val url = base.newBuilder().apply {
            addQueryParameter("username", user)
            addQueryParameter("password", pass)
            if (action != null) addQueryParameter("action", action)
            extra.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url).build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw XtreamException(
                        if (resp.code == 401 || resp.code == 403) "Accès refusé par le serveur (${resp.code})"
                        else "Erreur du serveur (${resp.code})"
                    )
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw XtreamException("Impossible de joindre le serveur. Vérifie l'adresse et ta connexion.")
        }
    }

    private fun array(text: String): JSONArray = try {
        JSONArray(text)
    } catch (e: JSONException) {
        JSONArray()
    }

    private fun obj(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (e: JSONException) {
        null
    }

    fun login(): AccountInfo {
        val root = obj(request(null))
            ?: throw XtreamException("Réponse inattendue : ce serveur n'est peut-être pas un serveur Xtream Codes")
        val ui = root.optJSONObject("user_info") ?: throw XtreamException("Identifiants refusés")
        if (ui.str("auth") != "1") throw XtreamException("Identifiants refusés")
        val formats = mutableListOf<String>()
        ui.optJSONArray("allowed_output_formats")?.let { arr ->
            for (i in 0 until arr.length()) formats.add(arr.optString(i))
        }
        return AccountInfo(
            status = ui.str("status") ?: "",
            expDate = ui.str("exp_date")?.toLongOrNull(),
            maxConnections = ui.str("max_connections"),
            formats = formats
        )
    }

    fun categories(kind: Kind): List<Category> {
        val action = when (kind) {
            Kind.LIVE -> "get_live_categories"
            Kind.MOVIE -> "get_vod_categories"
            Kind.SERIES -> "get_series_categories"
        }
        val arr = array(request(action))
        val out = ArrayList<Category>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.str("category_id") ?: continue
            out.add(Category(id, o.str("category_name") ?: "Sans nom"))
        }
        return out
    }

    /** Lit la réponse au fil de l'eau (sans charger tout le texte en mémoire). */
    private fun <T> requestStream(action: String, block: (JsonReader) -> T): T {
        val base = "$server/player_api.php".toHttpUrlOrNull()
            ?: throw XtreamException("Adresse du serveur invalide")
        val url = base.newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .addQueryParameter("action", action)
            .build()
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw XtreamException("Erreur du serveur (${resp.code})")
                val body = resp.body ?: throw XtreamException("Réponse vide du serveur")
                JsonReader(body.charStream()).use { r ->
                    r.isLenient = true
                    block(r)
                }
            }
        } catch (e: XtreamException) {
            throw e
        } catch (e: IOException) {
            throw XtreamException("Impossible de joindre le serveur. Vérifie l'adresse et ta connexion.")
        } catch (e: IllegalStateException) {
            throw XtreamException("Réponse inattendue du serveur")
        }
    }

    /** Lit un objet JSON « à plat » en ne gardant que les champs utiles. */
    private fun readFlat(r: JsonReader, keep: Set<String>): Map<String, String> {
        val m = HashMap<String, String>(12)
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (name !in keep) { r.skipValue(); continue }
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> {
                    val v = r.nextString().trim()
                    if (v.isNotEmpty() && v != "null") m[name] = v
                }
                JsonToken.BOOLEAN -> m[name] = r.nextBoolean().toString()
                JsonToken.NULL -> r.nextNull()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    private val streamKeys = setOf(
        "stream_id", "series_id", "name", "stream_icon", "cover", "category_id", "container_extension",
        "num", "added", "last_modified", "rating"
    )

    fun streams(kind: Kind): List<Item> {
        val action = when (kind) {
            Kind.LIVE -> "get_live_streams"
            Kind.MOVIE -> "get_vod_streams"
            Kind.SERIES -> "get_series"
        }
        return requestStream(action) { r ->
            val out = ArrayList<Item>()
            if (r.peek() != JsonToken.BEGIN_ARRAY) {
                r.skipValue() // {} ou message d'erreur : liste vide
                return@requestStream out
            }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                val o = readFlat(r, streamKeys)
                val item = when (kind) {
                    Kind.LIVE -> Item(
                        kind, o["stream_id"] ?: continue, o["name"] ?: "Chaîne",
                        o["stream_icon"], o["category_id"] ?: "", null, o["num"],
                        o["added"]?.toLongOrNull() ?: 0L, null
                    )
                    Kind.MOVIE -> Item(
                        kind, o["stream_id"] ?: continue, o["name"] ?: "Film",
                        o["stream_icon"], o["category_id"] ?: "", o["container_extension"] ?: "mp4", null,
                        o["added"]?.toLongOrNull() ?: 0L, parseRating(o["rating"])
                    )
                    Kind.SERIES -> Item(
                        kind, o["series_id"] ?: continue, o["name"] ?: "Série",
                        o["cover"], o["category_id"] ?: "", null, null,
                        o["last_modified"]?.toLongOrNull() ?: 0L, parseRating(o["rating"])
                    )
                }
                out.add(item)
            }
            r.endArray()
            out.trimToSize()
            out
        }
    }

    fun vodInfo(vodId: String): VodInfo {
        val root = obj(request("get_vod_info", mapOf("vod_id" to vodId)))
            ?: throw XtreamException("Film introuvable sur le serveur")
        val info = root.optJSONObject("info") ?: JSONObject()
        val data = root.optJSONObject("movie_data")
        val date = info.str("releasedate") ?: info.str("release_date")
        return VodInfo(
            plot = info.str("plot") ?: info.str("description"),
            genre = info.str("genre"),
            year = date?.take(4)?.takeIf { it.all(Char::isDigit) },
            duration = info.str("duration"),
            rating = parseRating(info.str("rating")),
            backdrop = info.firstStr("backdrop_path"),
            poster = info.str("movie_image") ?: info.str("cover_big"),
            ext = data?.str("container_extension")
        )
    }

    fun seriesInfo(seriesId: String): SeriesInfo {
        val root = obj(request("get_series_info", mapOf("series_id" to seriesId)))
            ?: throw XtreamException("Série introuvable sur le serveur")
        val info = root.optJSONObject("info")
        val map = LinkedHashMap<String, MutableList<Episode>>()

        fun addEpisode(o: JSONObject, seasonKey: String) {
            val id = o.str("id") ?: return
            val season = o.str("season") ?: seasonKey
            val einfo = o.optJSONObject("info")
            map.getOrPut(season) { mutableListOf() }.add(
                Episode(
                    id = id,
                    season = season,
                    number = o.str("episode_num") ?: "",
                    title = o.str("title") ?: "Épisode",
                    ext = o.str("container_extension") ?: "mp4",
                    image = einfo?.str("movie_image"),
                    durationSecs = parseDurationSecs(einfo)
                )
            )
        }

        when (val eps = root.opt("episodes")) {
            is JSONObject -> {
                val keys = eps.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val arr = eps.optJSONArray(k) ?: continue
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, k) }
                }
            }
            is JSONArray -> {
                for (i in 0 until eps.length()) {
                    when (val el = eps.opt(i)) {
                        is JSONArray -> for (j in 0 until el.length()) el.optJSONObject(j)?.let { addEpisode(it, (i + 1).toString()) }
                        is JSONObject -> addEpisode(el, "1")
                    }
                }
            }
        }

        val sorted = map.mapValues { (_, list) -> list.sortedBy { it.number.toIntOrNull() ?: Int.MAX_VALUE } }
        val date = info?.str("releaseDate") ?: info?.str("release_date")
        return SeriesInfo(
            name = info?.str("name") ?: "",
            plot = info?.str("plot"),
            cover = info?.str("cover"),
            backdrop = info?.firstStr("backdrop_path"),
            genre = info?.str("genre"),
            year = date?.take(4)?.takeIf { it.all(Char::isDigit) },
            rating = parseRating(info?.str("rating")),
            seasons = sorted.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE },
            episodes = sorted
        )
    }

    fun shortEpg(streamId: String): List<EpgEntry> {
        val root = obj(request("get_short_epg", mapOf("stream_id" to streamId, "limit" to "2"))) ?: return emptyList()
        val arr = root.optJSONArray("epg_listings") ?: return emptyList()
        val out = ArrayList<EpgEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                EpgEntry(
                    title = decode(o.str("title") ?: ""),
                    description = decode(o.str("description") ?: ""),
                    start = o.str("start_timestamp")?.toLongOrNull() ?: 0L,
                    end = o.str("stop_timestamp")?.toLongOrNull() ?: 0L
                )
            )
        }
        return out
    }

    private fun decode(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
    } catch (e: IllegalArgumentException) {
        s
    }

    private fun creds() = "${Uri.encode(user)}/${Uri.encode(pass)}"
    fun liveUrl(id: String, ext: String) = "$server/live/${creds()}/$id.$ext"
    fun movieUrl(id: String, ext: String) = "$server/movie/${creds()}/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "$server/series/${creds()}/$id.$ext"
}
