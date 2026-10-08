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
        "/rest/v1/titles?select=id,kind,name,synopsis,poster_url,backdrop_url,year,duration_s,size_bytes," +
            "seasons(id,number,name,episodes(id,number,name,synopsis,duration_s,size_bytes))" +
            "&published=eq.true&order=sort.asc,created_at.desc"
    )

    suspend fun fetchCategories(): String = authedOk(
        "/rest/v1/categories?select=id,name,ranked,title_categories(title_id,position)&order=sort.asc,created_at.asc"
    )

    suspend fun fetchProgress(): String =
        authedOk("/rest/v1/watch_progress?select=media_id,position_ms,duration_ms,updated_at")

    suspend fun pushProgress(jsonArray: String) {
        authedOk(
            "/rest/v1/watch_progress?on_conflict=user_id,media_id", "POST", jsonArray,
            mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
        )
    }

    /** URL signée R2 (valable quelques heures) générée par l'Edge Function `video-url`. */
    suspend fun videoUrl(mediaId: String): String {
        val body = JSONObject().put("media_id", mediaId).toString()
        val json = JSONObject(authedOk("/functions/v1/video-url", "POST", body))
        return json.optString("url").ifBlank { throw IOException("URL vidéo absente") }
    }

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
