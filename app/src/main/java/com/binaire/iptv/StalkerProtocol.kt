package com.binaire.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Logique « pure » du protocole Stalker/MAG, sans dépendance Android ni réseau,
 * pour être relue et testée facilement (voir app/src/test). Le client réseau
 * (StalkerClient) s'appuie dessus pour tout ce qui est fragile : URL, enveloppes
 * de réponse, normalisation des liens de lecture, décision de pagination.
 *
 * Rien ici ne contourne l'authentification d'un fournisseur : on se contente de
 * parler le protocole Stalker standard, avec les informations que l'utilisateur
 * fournit (adresse du portail + sa MAC).
 */
object StalkerProtocol {

    const val PREFIX = "stalker:"
    const val PAGE_MAX = 60

    // Préfixes de commande connus renvoyés par les portails (« ffmpeg http://… »).
    private val KNOWN_PREFIXES = setOf("ffmpeg", "auto", "mpegts", "varied", "vlc", "play", "streamer")

    // ---------- MAC ----------
    fun cleanMac(input: String): String? {
        val hex = input.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
        return hex.chunked(2).joinToString(":")
    }

    fun isValidMac(input: String) = cleanMac(input) != null

    /** MAC aléatoire au préfixe des box MAG (00:1A:79:…). */
    fun randomMac(rnd: java.util.Random = java.security.SecureRandom()): String {
        val tail = (0 until 3).joinToString(":") { "%02X".format(rnd.nextInt(256)) }
        return "00:1A:79:$tail"
    }

    // ---------- Adresses ----------
    fun normalizePortal(input: String): String {
        var u = input.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) u = "http://$u"
        return PREFIX + u.trimEnd('/')
    }

    fun isStalker(server: String?) = server?.startsWith(PREFIX) == true

    /** Origine (scheme://host:port) d'une adresse de portail, sans dépendance Android. */
    fun origin(portal: String): String {
        val u = URI(portal.trim())
        val scheme = (u.scheme ?: "http").lowercase()
        val host = u.host ?: throw XtreamException("Adresse de portail invalide.")
        val port = if (u.port > 0) ":${u.port}" else ""
        return "$scheme://$host$port"
    }

    /** Emplacements habituels du point d'entrée du portail, essayés dans l'ordre. */
    fun endpointCandidates(portal: String): List<String> {
        val base = portal.trim().trimEnd('/')
        val o = origin(portal)
        return listOf(
            "$o/portal.php",
            "$o/server/load.php",
            "$o/stalker_portal/server/load.php",
            "$base/server/load.php"
        ).distinct()
    }

    fun buildUrl(api: String, params: String) = "$api?$params&JsHttpRequest=1-xml"

    // ---------- Enveloppes de réponse ----------
    enum class ResultKind { OK, EMPTY, BLOCKED, TOKEN_EXPIRED, ERROR }

    data class Parsed(val kind: ResultKind, val js: Any?, val message: String?)

    /**
     * Classe une réponse de portail. Les portails renvoient soit un objet { js: … },
     * soit une enveloppe d'erreur ({ js: "error" }, { js: { error: … } }, block=1…).
     */
    fun parse(body: String): Parsed {
        val root = try {
            JSONObject(body)
        } catch (e: Exception) {
            return Parsed(ResultKind.ERROR, null, "Réponse illisible")
        }
        if (!root.has("js")) {
            // Certaines erreurs arrivent à la racine.
            val err = root.optString("error").ifBlank { root.optString("msg") }
            return if (err.isNotBlank()) classifyError(err) else Parsed(ResultKind.EMPTY, null, null)
        }
        return when (val js = root.opt("js")) {
            is JSONObject -> {
                val block = js.optInt("block", 0)
                val err = js.optString("error").ifBlank { js.optString("msg") }
                when {
                    block == 1 -> Parsed(ResultKind.BLOCKED, js, "Compte bloqué par le portail")
                    err.isNotBlank() -> classifyError(err)
                    else -> Parsed(ResultKind.OK, js, null)
                }
            }
            is JSONArray -> Parsed(ResultKind.OK, js, null)
            is String -> classifyError(js) // ex. { "js": "error" }
            else -> Parsed(ResultKind.EMPTY, null, null)
        }
    }

    private fun classifyError(raw: String): Parsed {
        val m = raw.lowercase()
        return when {
            m.isBlank() || m == "null" -> Parsed(ResultKind.EMPTY, null, null)
            "token" in m && ("expire" in m || "invalid" in m || "not valid" in m) ->
                Parsed(ResultKind.TOKEN_EXPIRED, null, raw)
            "authoriz" in m || "auth" in m && "token" in m -> Parsed(ResultKind.TOKEN_EXPIRED, null, raw)
            "block" in m || "ban" in m || "disabled" in m || "expired" in m -> Parsed(ResultKind.BLOCKED, null, raw)
            else -> Parsed(ResultKind.ERROR, null, raw)
        }
    }

    fun token(js: Any?): String? = (js as? JSONObject)?.optString("token")?.takeIf { it.isNotBlank() }

    fun dataArray(js: Any?): JSONArray = when (js) {
        is JSONArray -> js
        is JSONObject -> js.optJSONArray("data") ?: JSONArray()
        else -> JSONArray()
    }

    // ---------- Pagination ----------
    /** Faut-il arrêter la pagination après cette page ? (page vide, dernière page, ou plafond) */
    fun paginationDone(pageItemCount: Int, perPage: Int, loadedTotal: Int, declaredTotal: Int, pageIndex: Int): Boolean {
        if (pageItemCount == 0) return true                 // page vide
        if (pageIndex >= PAGE_MAX) return true              // garde-fou
        if (perPage in 1..(pageItemCount - 1)) {
            // Le portail a renvoyé plus que sa taille de page annoncée : total peu fiable, on continue prudemment.
        } else if (perPage > 0 && pageItemCount < perPage) return true  // dernière page (moins pleine)
        if (declaredTotal in 1..loadedTotal) return true    // on a tout chargé selon le total annoncé
        return false
    }

    // ---------- Lien de lecture (sécurité) ----------
    /**
     * Transforme la commande renvoyée par create_link en URL jouable.
     * On n'accepte QUE des URL http(s). Toute autre forme (commande locale, schéma
     * inattendu) est refusée plutôt que passée au lecteur. Renvoie null si invalide.
     */
    fun normalizePlayUrl(cmd: String?): String? {
        if (cmd.isNullOrBlank()) return null
        var s = cmd.trim()
        // Retire un éventuel préfixe de commande connu (ex. "ffmpeg ", "auto ").
        val firstSpace = s.indexOf(' ')
        if (firstSpace > 0) {
            val head = s.substring(0, firstSpace).lowercase()
            if (head in KNOWN_PREFIXES) s = s.substring(firstSpace + 1).trim()
        }
        // Certains portails ajoutent des segments après l'URL (séparés par espace) : on garde le 1er jeton URL.
        val firstToken = s.split(' ').firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
            ?: s
        val url = firstToken.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        return try {
            val u = URI(url)
            if (u.host.isNullOrBlank()) null else url
        } catch (e: Exception) {
            null
        }
    }

    /** Résout une URL éventuellement relative par rapport à l'origine du portail. */
    fun absUrl(portal: String, u: String?): String? {
        if (u.isNullOrBlank()) return null
        return if (u.startsWith("http", true)) u else origin(portal) + "/" + u.trimStart('/')
    }
}
