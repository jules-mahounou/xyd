package tech.xydhub.xyd.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import tech.xydhub.xyd.BuildConfig
import java.io.IOException

/**
 * Accès Supabase en REST pur (GoTrue + PostgREST + Edge Functions).
 * Remplace supabase-kt + Ktor + kotlinx.serialization (~1,5 Mo) par ~150 lignes.
 */
object Supabase {
    val url: String = BuildConfig.SUPABASE_URL
    private val key: String = BuildConfig.SUPABASE_KEY
    val configured: Boolean get() = url.startsWith("http") && key.isNotBlank()

    private val anon get() = mapOf("apikey" to key)
    private val refreshLock = Mutex()

    // ---------- Auth ----------

    suspend fun signIn(email: String, password: String): Session {
        val body = JSONObject().put("email", email).put("password", password).toString()
        val r = Http.request("$url/auth/v1/token?grant_type=password", "POST", anon, body)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        return parseSession(r.body)
    }

    suspend fun signUp(email: String, password: String, name: String): Session {
        val body = JSONObject().put("email", email).put("password", password)
            .put("data", JSONObject().put("name", name)).toString()
        val r = Http.request("$url/auth/v1/signup", "POST", anon, body)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        if (!JSONObject(r.body).has("access_token")) {
            throw Http.ApiException(0, "Compte créé. Désactive « Confirm email » dans Supabase pour une connexion directe.")
        }
        return parseSession(r.body)
    }

    suspend fun signOut(s: Session) {
        runCatching {
            Http.request("$url/auth/v1/logout", "POST", anon + ("Authorization" to "Bearer ${s.accessToken}"), "{}")
        }
    }

    private suspend fun refresh(s: Session): Session {
        val body = JSONObject().put("refresh_token", s.refreshToken).toString()
        val r = Http.request("$url/auth/v1/token?grant_type=refresh_token", "POST", anon, body)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        return parseSession(r.body)
    }

    private fun parseSession(json: String): Session {
        val o = JSONObject(json)
        val user = o.getJSONObject("user")
        val expiresAt = if (o.has("expires_at")) o.getLong("expires_at") * 1000
        else System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
        val meta = user.optJSONObject("user_metadata")
        val email = user.optString("email")
        return Session(
            accessToken = o.getString("access_token"),
            refreshToken = o.getString("refresh_token"),
            expiresAt = expiresAt,
            userId = user.getString("id"),
            email = email,
            name = meta?.optString("name")?.ifBlank { null } ?: email.substringBefore('@'),
        )
    }

    /** Jeton valide (rafraîchi si nécessaire). Déconnecte si le refresh token est révoqué. */
    private suspend fun accessToken(forceRefresh: Boolean = false): String {
        return refreshLock.withLock {
            val s = Repo.session.value ?: throw Http.ApiException(401, "Non connecté")
            if (!forceRefresh && s.expiresAt - 60_000 > System.currentTimeMillis()) return@withLock s.accessToken
            try {
                val n = refresh(s)
                Repo.saveSession(n)
                n.accessToken
            } catch (e: Http.ApiException) {
                if (e.code in 400..499) Repo.saveSession(null)
                throw e
            }
        }
    }

    // ---------- Requêtes authentifiées ----------

    suspend fun authed(
        path: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Http.Response {
        var token = accessToken()
        var r = Http.request("$url$path", method, anon + headers + ("Authorization" to "Bearer $token"), body)
        if (r.code == 401) {
            token = accessToken(forceRefresh = true)
            r = Http.request("$url$path", method, anon + headers + ("Authorization" to "Bearer $token"), body)
        }
        return r
    }

    suspend fun authedOk(path: String, method: String = "GET", body: String? = null, headers: Map<String, String> = emptyMap()): String {
        val r = authed(path, method, body, headers)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        return r.body
    }

    /** Requête publique (clé anon/publishable seule) : utilisée pour la vérification de version. */
    suspend fun public(path: String): String {
        val r = Http.request("$url$path", "GET", anon)
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        return r.body
    }

    // ---------- Données ----------

    suspend fun fetchCatalog(): String = authedOk(
        "/rest/v1/titles?select=id,kind,name,synopsis,poster_url,backdrop_url,year,duration_s,size_bytes,featured,created_at," +
            "seasons(id,number,name,episodes(id,number,name,synopsis,duration_s,size_bytes))" +
            "&published=eq.true&order=sort.asc,created_at.desc"
    )

    suspend fun fetchCategories(): String = authedOk(
        "/rest/v1/categories?select=id,name,ranked,title_categories(title_id,position)&order=sort.asc,created_at.asc"
    )

    /** Statut du compte connecté : "active" ou "blocked" (null si inconnu). */
    suspend fun myStatus(userId: String): String? {
        val arr = org.json.JSONArray(authedOk("/rest/v1/profiles?select=status&user_id=eq.$userId"))
        return if (arr.length() == 0) null else arr.getJSONObject(0).optString("status").ifBlank { null }
    }

    suspend fun fetchProgress(): String =
        authedOk("/rest/v1/watch_progress?select=media_id,position_ms,duration_ms,updated_at")

    suspend fun pushProgress(jsonArray: String) {
        authedOk(
            "/rest/v1/watch_progress?on_conflict=user_id,media_id", "POST", jsonArray,
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    /** Refus de la limitation serveur (quota du jour, budget d'un stockage…) : réessayer à `retryAt`. */
    class QuotaException(message: String, val retryAt: Long) : IOException(message)

    /**
     * URL signée (valable quelques heures) générée par l'Edge Function `video-url`.
     * `offset` = octets déjà téléchargés : une reprise n'est pas recomptée dans le quota.
     */
    suspend fun videoUrl(mediaId: String, offset: Long): String {
        val body = JSONObject().put("media_id", mediaId).put("offset", offset).toString()
        val r = authed("/functions/v1/video-url", "POST", body)
        if (r.code == 429) {
            val o = runCatching { JSONObject(r.body) }.getOrNull()
            val retryAt = o?.optString("retry_at")?.takeIf { it.isNotBlank() && it != "null" }?.let(::parseInstant)
            throw QuotaException(Http.errorMessage(r), retryAt ?: (System.currentTimeMillis() + 3_600_000))
        }
        if (!r.ok) throw Http.ApiException(r.code, Http.errorMessage(r))
        return JSONObject(r.body).optString("url").ifBlank { throw IOException("URL vidéo absente") }
    }

    /** Quota de téléchargement du jour : (minutes utilisées, minutes autorisées), null si indisponible. */
    suspend fun myQuota(): Pair<Double, Double>? = runCatching {
        val o = JSONObject(authedOk("/rest/v1/rpc/my_download_quota", "POST", "{}"))
        if (!o.optBoolean("enabled", true)) null else o.optDouble("used_minutes", 0.0) to o.optDouble("daily_minutes", 0.0)
    }.getOrNull()

    // ---------- Requêtes des utilisateurs (avis, demandes…) ----------

    suspend fun submitRequest(kind: RequestKind, message: String) {
        val body = JSONObject().put("kind", kind.key).put("message", message.trim())
            .put("app_version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").toString()
        authedOk("/rest/v1/user_requests", "POST", body, mapOf("Prefer" to "return=minimal"))
    }

    /** Requêtes du compte connecté (filtre explicite : un admin voit toutes les lignes via RLS). */
    suspend fun myRequests(userId: String): List<UserRequest> = Parse.requests(
        authedOk("/rest/v1/user_requests?select=id,kind,message,status,reply,created_at&user_id=eq.$userId&order=created_at.desc&limit=30")
    )

    /** « 2026-10-14T00:00:00+00:00 » → epoch ms. */
    private fun parseInstant(s: String): Long? =
        runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()

    suspend fun latestVersion(): AppVersion? {
        val arr = org.json.JSONArray(public("/rest/v1/app_versions?select=*&order=version_code.desc&limit=1"))
        if (arr.length() == 0) return null
        val o = arr.getJSONObject(0)
        return AppVersion(
            versionCode = o.getInt("version_code"),
            versionName = o.optString("version_name"),
            minVersionCode = o.optInt("min_version_code"),
            apkUrl = o.optString("apk_url"),
            changelog = if (o.isNull("changelog")) "" else o.optString("changelog"),
        )
    }
}
