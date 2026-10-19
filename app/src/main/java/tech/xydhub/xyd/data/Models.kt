package tech.xydhub.xyd.data

import org.json.JSONArray
import org.json.JSONObject

data class Episode(
    val id: String,
    val number: Int,
    val name: String,
    val synopsis: String,
    val durationS: Int,
    val sizeBytes: Long,
)

data class Season(val id: String, val number: Int, val name: String, val episodes: List<Episode>)

data class Title(
    val id: String,
    val isFilm: Boolean,
    val name: String,
    val synopsis: String,
    val poster: String?,
    val backdrop: String?,
    val year: Int,
    val durationS: Int,
    val sizeBytes: Long,
    val seasons: List<Season>,
    val featured: Boolean = false,
    val createdAt: String = "",
)

/** Rangée de l'accueil définie dans l'admin (Top 10, Anime…). `titleIds` déjà triés. */
data class Category(val id: String, val name: String, val ranked: Boolean, val titleIds: List<String>)

/** Un élément lisible : un film, ou un épisode d'une série. `mediaId` = id du film ou de l'épisode. */
data class Playable(val title: Title, val season: Season?, val episode: Episode?) {
    val mediaId: String get() = episode?.id ?: title.id
    val sizeBytes: Long get() = episode?.sizeBytes ?: title.sizeBytes
    val durationS: Int get() = episode?.durationS ?: title.durationS
    val shortLabel: String get() = if (episode != null) "S${season!!.number} · É${episode.number}" else "Film"
    val label: String
        get() = if (episode != null) "$shortLabel — ${episode.name}" else title.name
}

data class Progress(val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val fraction: Float get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val finished: Boolean get() = fraction >= 0.95f
}

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String,
    val name: String,
)

data class AppVersion(
    val versionCode: Int,
    val versionName: String,
    val minVersionCode: Int,
    val apkUrl: String,
    val changelog: String,
)

/** Type de requête envoyée depuis l'accueil (clé stockée en base → libellé affiché). */
enum class RequestKind(val key: String, val label: String, val chip: String, val hint: String) {
    REQUEST("request", "Demande de série / film", "Série / film", "Quelle série ou quel film veux-tu voir sur xyd ?"),
    FEEDBACK("feedback", "Avis", "Avis", "Ton avis sur le design, le fonctionnement, ce qui te plaît ou pas…"),
    BUG("bug", "Problème", "Problème", "Décris le problème : ce que tu faisais, ce qui s'est passé…"),
    OTHER("other", "Autre", "Autre", "Ta demande, ta question, ton idée…"),
    ;
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: OTHER }
}

/** Requête d'un utilisateur. `status` : new / seen / done. `reply` : réponse de l'admin. */
data class UserRequest(
    val id: String,
    val kind: RequestKind,
    val message: String,
    val status: String,
    val reply: String?,
    val createdAt: String,
)

object Parse {
    fun categories(json: String): List<Category> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val links = o.optJSONArray("title_categories") ?: JSONArray()
            Category(
                id = o.getString("id"),
                name = o.optString("name"),
                ranked = o.optBoolean("ranked"),
                titleIds = (0 until links.length()).map { links.getJSONObject(it) }
                    .sortedBy { it.optInt("position") }
                    .map { it.getString("title_id") },
            )
        }
    }

    private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)
    private fun JSONObject.strOrNull(k: String) = if (isNull(k)) null else optString(k).ifBlank { null }

    fun requests(json: String): List<UserRequest> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            UserRequest(
                id = o.getString("id"),
                kind = RequestKind.of(o.optString("kind")),
                message = o.str("message"),
                status = o.optString("status", "new"),
                reply = o.strOrNull("reply"),
                createdAt = o.optString("created_at"),
            )
        }
    }

    fun catalog(json: String): List<Title> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val seasons = o.optJSONArray("seasons") ?: JSONArray()
            Title(
                id = o.getString("id"),
                isFilm = o.optString("kind") == "film",
                name = o.str("name"),
                synopsis = o.str("synopsis"),
                poster = o.strOrNull("poster_url"),
                backdrop = o.strOrNull("backdrop_url"),
                year = o.optInt("year"),
                durationS = o.optInt("duration_s"),
                sizeBytes = o.optLong("size_bytes"),
                featured = o.optBoolean("featured"),
                createdAt = o.optString("created_at"),
                seasons = (0 until seasons.length()).map { j ->
                    val s = seasons.getJSONObject(j)
                    val eps = s.optJSONArray("episodes") ?: JSONArray()
                    Season(
                        id = s.getString("id"),
                        number = s.optInt("number"),
                        name = s.str("name"),
                        episodes = (0 until eps.length()).map { k ->
                            val e = eps.getJSONObject(k)
                            Episode(
                                id = e.getString("id"),
                                number = e.optInt("number"),
                                name = e.str("name"),
                                synopsis = e.str("synopsis"),
                                durationS = e.optInt("duration_s"),
                                sizeBytes = e.optLong("size_bytes"),
                            )
                        }.sortedBy { it.number },
                    )
                }.sortedBy { it.number },
            )
        }
    }
}
