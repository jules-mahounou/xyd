package tech.xydhub.xyd.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Client HTTP minimal (HttpURLConnection du framework) : zéro dépendance, zéro octet dans l'APK. */
object Http {
    class Response(val code: Int, val body: String) {
        val ok get() = code in 200..299
    }

    class ApiException(val code: Int, message: String) : IOException(message)

    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Response = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            Response(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            c.disconnect()
        }
    }

    /** Extrait un message lisible d'une erreur Supabase (GoTrue / PostgREST / Edge). */
    fun errorMessage(r: Response): String {
        val raw = runCatching {
            val o = JSONObject(r.body)
            o.optString("msg").ifBlank { o.optString("error_description") }
                .ifBlank { o.optString("message") }.ifBlank { o.optString("error") }
        }.getOrNull().orEmpty()
        return when {
            raw.contains("Invalid login credentials", true) -> "Email ou mot de passe incorrect"
            raw.contains("already registered", true) -> "Un compte existe déjà avec cet email"
            raw.contains("Password should be", true) -> "Mot de passe trop court (6 caractères minimum)"
            raw.contains("valid email", true) || raw.contains("invalid format", true) -> "Adresse email invalide"
            raw.contains("Signups not allowed", true) -> "Les inscriptions sont fermées"
            raw.isNotBlank() -> raw
            else -> "Erreur serveur (${r.code})"
        }
    }
}
