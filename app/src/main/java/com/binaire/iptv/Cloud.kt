package com.binaire.iptv

import android.content.Context
import android.os.Build
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom

/**
 * Compte Binaire : Firebase (Authentication + Firestore) via leurs API web.
 *
 * Chaque appareil a sa propre identité (anonyme, durable). Il accède aux données d'un compte
 * une fois autorisé par ce compte : users/{compte}/devices/{appareil}.
 */
object Cloud {

    // Configuration publique du projet Firebase (ce ne sont pas des secrets).
    const val API_KEY = "AIzaSyD4AGPutgrGhmttoeeVuHhJQs8rzqJuQac"
    const val PROJECT = "binaire-iptv"
    const val WEB_URL = "https://byredemptiion.github.io/binaire-iptv/"

    private const val FS = "https://firestore.googleapis.com/v1/projects/$PROJECT/databases/(default)/documents"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    class CloudException(message: String, val code: Int = 0) : Exception(message)

    // ------------------------------------------------------------------
    // État local (commun à l'appareil)
    // ------------------------------------------------------------------
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("binaire_cloud", Context.MODE_PRIVATE)

    fun ownerUid(ctx: Context): String? = sp(ctx).getString("owner", null)
    fun ownerEmail(ctx: Context): String? = sp(ctx).getString("owner_email", null)
    fun isLinked(ctx: Context) = ownerUid(ctx) != null
    fun subId(ctx: Context): String? = sp(ctx).getString("sub_id", null)

    fun setOwner(ctx: Context, uid: String?, email: String?) {
        sp(ctx).edit().putString("owner", uid).putString("owner_email", email).remove("sub_id")
            .remove("synced_ids").remove("last_sync").apply()
    }

    fun setSubId(ctx: Context, id: String?) = sp(ctx).edit().putString("sub_id", id).apply()

    fun syncedIds(ctx: Context): Set<String> = HashSet(sp(ctx).getStringSet("synced_ids", emptySet()) ?: emptySet())
    fun setSyncedIds(ctx: Context, ids: Set<String>) = sp(ctx).edit().putStringSet("synced_ids", ids).apply()
    fun pushedHash(ctx: Context, pid: String): String? = sp(ctx).getString("hash_$pid", null)
    fun setPushedHash(ctx: Context, pid: String, h: String) = sp(ctx).edit().putString("hash_$pid", h).apply()
    fun pulledAt(ctx: Context, pid: String): Long = sp(ctx).getLong("pulled_$pid", 0L)
    fun setPulledAt(ctx: Context, pid: String, t: Long) = sp(ctx).edit().putLong("pulled_$pid", t).apply()

    fun deviceName(): String {
        val m = Build.MODEL ?: "Appareil"
        val brand = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
        return if (m.startsWith(brand, true)) m else "$brand $m".trim()
    }

    // ------------------------------------------------------------------
    // Identité de l'appareil (connexion anonyme Firebase)
    // ------------------------------------------------------------------
    private fun postJson(url: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
        return exec(req)
    }

    private fun exec(req: Request): JSONObject {
        try {
            XtreamClient.http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                    throw CloudException(msg.ifBlank { "Erreur ${r.code}" }, r.code)
                }
                return if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        } catch (e: IOException) {
            throw CloudException("Pas de connexion au serveur Binaire")
        }
    }

    private fun saveToken(ctx: Context, o: JSONObject, uidKey: String = "localId") {
        val e = sp(ctx).edit()
            .putString("id_token", o.optString("idToken", o.optString("id_token")))
            .putLong("token_exp", System.currentTimeMillis() + (o.optString("expiresIn", o.optString("expires_in", "3600")).toLongOrNull() ?: 3600) * 1000 - 60_000)
        o.optString("refreshToken", o.optString("refresh_token")).takeIf { it.isNotBlank() }?.let { e.putString("refresh", it) }
        o.optString(uidKey, o.optString("user_id")).takeIf { it.isNotBlank() }?.let { e.putString("device_uid", it) }
        e.apply()
    }

    fun deviceUid(ctx: Context): String? = sp(ctx).getString("device_uid", null)

    /** Jeton valide de l'identité de l'appareil (créée au premier besoin). */
    @Synchronized
    fun token(ctx: Context): String {
        val s = sp(ctx)
        val tok = s.getString("id_token", null)
        if (tok != null && System.currentTimeMillis() < s.getLong("token_exp", 0)) return tok
        val refresh = s.getString("refresh", null)
        if (refresh != null) {
            try {
                val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).build()
                val o = exec(Request.Builder().url("https://securetoken.googleapis.com/v1/token?key=$API_KEY").post(body).build())
                saveToken(ctx, o, "user_id")
                return s.getString("id_token", null)!!
            } catch (e: CloudException) {
                if (e.code !in listOf(400, 401, 403)) throw e
                // identité perdue : on en recrée une (l'appareil devra être réautorisé)
            }
        }
        val o = postJson("https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$API_KEY", JSONObject().put("returnSecureToken", true))
        saveToken(ctx, o)
        return s.getString("id_token", null)!!
    }

    /** Envoie l'e-mail « mot de passe oublié » (en français). */
    fun sendPasswordReset(email: String) {
        val req = Request.Builder()
            .url("https://identitytoolkit.googleapis.com/v1/accounts:sendOobCode?key=$API_KEY")
            .header("X-Firebase-Locale", "fr")
            .post(JSONObject().put("requestType", "PASSWORD_RESET").put("email", email).toString().toRequestBody(JSON))
            .build()
        exec(req)
    }

    /** Lit l'indication « e-mail confirmé » dans le jeton de connexion. */
    private fun emailVerified(idToken: String): Boolean = try {
        val part = idToken.split('.')[1]
        val json = String(android.util.Base64.decode(part, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
        JSONObject(json).optBoolean("email_verified", false)
    } catch (e: Exception) {
        false
    }

    /** Connexion avec l'e-mail du compte (sans stocker le mot de passe) : renvoie (uid, jeton). */
    fun signInEmail(email: String, password: String): Pair<String, String> {
        try {
            val o = postJson(
                "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$API_KEY",
                JSONObject().put("email", email.trim()).put("password", password).put("returnSecureToken", true)
            )
            val idToken = o.getString("idToken")
            if (!emailVerified(idToken)) throw CloudException(
                "Valide d'abord ton adresse e-mail : clique sur le lien reçu par mail (regarde aussi dans les spams), puis réessaie.", 403
            )
            return o.getString("localId") to idToken
        } catch (e: CloudException) {
            if (e.code == 403) throw e
            throw CloudException(
                when {
                    e.message?.contains("INVALID") == true || e.message?.contains("NOT_FOUND") == true -> "E-mail ou mot de passe incorrect"
                    e.message?.contains("TOO_MANY") == true -> "Trop d'essais, réessaie dans quelques minutes"
                    else -> e.message ?: "Connexion impossible"
                }, e.code
            )
        }
    }

    // ------------------------------------------------------------------
    // Firestore (format REST)
    // ------------------------------------------------------------------
    private fun enc(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("nullValue", JSONObject.NULL)
        is String -> JSONObject().put("stringValue", v)
        is Boolean -> JSONObject().put("booleanValue", v)
        is Int, is Long -> JSONObject().put("integerValue", v.toString())
        is Number -> JSONObject().put("doubleValue", v.toDouble())
        else -> JSONObject().put("stringValue", v.toString())
    }

    private fun dec(o: JSONObject): Any? = when {
        o.has("stringValue") -> o.getString("stringValue")
        o.has("booleanValue") -> o.getBoolean("booleanValue")
        o.has("integerValue") -> o.getString("integerValue").toLongOrNull()
        o.has("doubleValue") -> o.getDouble("doubleValue")
        o.has("timestampValue") -> o.getString("timestampValue")
        else -> null
    }

    private fun fields(m: Map<String, Any?>): JSONObject {
        val f = JSONObject()
        for ((k, v) in m) f.put(k, enc(v))
        return JSONObject().put("fields", f)
    }

    private fun parseDoc(o: JSONObject): Pair<String, Map<String, Any?>> {
        val name = o.optString("name").substringAfterLast('/')
        val f = o.optJSONObject("fields") ?: JSONObject()
        val m = HashMap<String, Any?>()
        for (k in f.keys()) m[k] = dec(f.getJSONObject(k))
        return name to m
    }

    private fun req(token: String, path: String) = Request.Builder().url("$FS/$path").header("Authorization", "Bearer $token")

    fun getDoc(token: String, path: String): Map<String, Any?>? = try {
        parseDoc(exec(req(token, path).get().build())).second
    } catch (e: CloudException) {
        if (e.code == 404) null else throw e
    }

    fun listDocs(token: String, path: String): List<Pair<String, Map<String, Any?>>> {
        val o = exec(req(token, "$path?pageSize=100").get().build())
        val a = o.optJSONArray("documents") ?: JSONArray()
        return (0 until a.length()).map { parseDoc(a.getJSONObject(it)) }
    }

    /** Crée ou met à jour les champs donnés. */
    fun setDoc(token: String, path: String, data: Map<String, Any?>) {
        val mask = data.keys.joinToString("&") { "updateMask.fieldPaths=" + java.net.URLEncoder.encode(it, "UTF-8") }
        exec(
            Request.Builder().url("$FS/$path?$mask").header("Authorization", "Bearer $token")
                .patch(fields(data).toString().toRequestBody(JSON)).build()
        )
    }

    /** Crée un document s'il n'existe pas (erreur 409 sinon). */
    fun createDoc(token: String, collection: String, id: String, data: Map<String, Any?>) {
        exec(
            Request.Builder().url("$FS/$collection?documentId=$id").header("Authorization", "Bearer $token")
                .post(fields(data).toString().toRequestBody(JSON)).build()
        )
    }

    fun deleteDoc(token: String, path: String) {
        try {
            exec(req(token, path).delete().build())
        } catch (e: CloudException) {
            if (e.code != 404) throw e
        }
    }

    // ------------------------------------------------------------------
    // Activation par QR code
    // ------------------------------------------------------------------
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // sans 0/O ni 1/I

    fun newCode(): String {
        val r = SecureRandom()
        return (1..6).map { ALPHABET[r.nextInt(ALPHABET.length)] }.joinToString("")
    }

    /** Publie une demande d'activation ; renvoie le code affiché sur la TV. */
    fun startActivation(ctx: Context): String {
        val token = token(ctx)
        val uid = deviceUid(ctx)!!
        repeat(5) {
            val code = newCode()
            try {
                createDoc(token, "activations", code, mapOf("tvUid" to uid, "model" to deviceName(), "createdAt" to System.currentTimeMillis()))
                return code
            } catch (e: CloudException) {
                if (e.code != 409) throw e // code déjà pris : on en tire un autre
            }
        }
        throw CloudException("Impossible de créer un code d'activation")
    }

    /** Renvoie (uid du compte, e-mail) quand le téléphone a validé, sinon null. */
    fun checkActivation(ctx: Context, code: String): Pair<String, String?>? {
        val d = getDoc(token(ctx), "activations/$code") ?: throw CloudException("Demande expirée", 404)
        val owner = d["owner"] as? String ?: return null
        return owner to (d["email"] as? String)
    }

    fun cancelActivation(ctx: Context, code: String) {
        runCatching { deleteDoc(token(ctx), "activations/$code") }
    }

    /** Après validation : la TV vérifie qu'elle a bien accès au compte puis s'y rattache. */
    fun finishActivation(ctx: Context, code: String, owner: String, email: String?) {
        val token = token(ctx)
        if (getDoc(token, "users/$owner/devices/${deviceUid(ctx)}") == null) {
            throw CloudException("L'autorisation n'est pas encore enregistrée, réessaie")
        }
        runCatching { deleteDoc(token, "activations/$code") }
        setOwner(ctx, owner, email)
        runCatching { setDoc(token, "users/$owner/devices/${deviceUid(ctx)}", mapOf("lastSeen" to System.currentTimeMillis())) }
    }

    /** Connexion directe sur la TV avec l'e-mail du compte (sans téléphone). */
    fun linkWithEmail(ctx: Context, email: String, password: String, currentLogin: Triple<String, String, String>? = null) {
        val (owner, ownerToken) = signInEmail(email, password)
        token(ctx) // crée l'identité de l'appareil si besoin
        val dev = deviceUid(ctx)!!
        setDoc(ownerToken, "users/$owner", mapOf("email" to email.trim()))
        setDoc(
            ownerToken, "users/$owner/devices/$dev",
            mapOf("name" to deviceName(), "model" to deviceName(), "addedAt" to System.currentTimeMillis(), "lastSeen" to System.currentTimeMillis())
        )
        // Compte sans abonnement : on y range celui déjà saisi sur cet appareil.
        if (currentLogin != null && listDocs(ownerToken, "users/$owner/subs").isEmpty()) {
            setDoc(
                ownerToken, "users/$owner/subs/main",
                mapOf("name" to "Mon abonnement", "server" to currentLogin.first, "user" to currentLogin.second,
                    "pass" to currentLogin.third, "createdAt" to System.currentTimeMillis())
            )
        }
        setOwner(ctx, owner, email.trim())
    }

    /** Délie l'appareil du compte (il garde son abonnement actuel). */
    fun unlink(ctx: Context) {
        val owner = ownerUid(ctx)
        if (owner != null) runCatching { deleteDoc(token(ctx), "users/$owner/devices/${deviceUid(ctx)}") }
        setOwner(ctx, null, null)
    }

    // ------------------------------------------------------------------
    // Abonnements du compte
    // ------------------------------------------------------------------
    data class Sub(val id: String, val name: String, val server: String, val user: String, val pass: String)

    fun subs(ctx: Context): List<Sub> {
        val owner = ownerUid(ctx) ?: return emptyList()
        return listDocs(token(ctx), "users/$owner/subs").mapNotNull { (id, m) ->
            val server = m["server"] as? String ?: return@mapNotNull null
            Sub(id, (m["name"] as? String) ?: "Abonnement", server, (m["user"] as? String).orEmpty(), (m["pass"] as? String).orEmpty())
        }.sortedBy { it.name.lowercase() }
    }

    /** Vérifie que l'appareil est toujours autorisé (false = retiré depuis le compte). */
    fun stillAuthorized(ctx: Context): Boolean {
        val owner = ownerUid(ctx) ?: return false
        return try {
            val t = token(ctx)
            if (getDoc(t, "users/$owner/devices/${deviceUid(ctx)}") == null) return false
            // Compte suspendu par l'administrateur : l'appareil est délié.
            getDoc(t, "users/$owner")?.get("blocked") != true
        } catch (e: CloudException) {
            if (e.code == 403 || e.code == 404) false else true // hors ligne : on ne délie pas
        }
    }
}
