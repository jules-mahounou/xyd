package tech.xydhub.xyd.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import tech.xydhub.xyd.data.Episode
import tech.xydhub.xyd.data.Playable
import tech.xydhub.xyd.data.Season
import tech.xydhub.xyd.data.Title
import java.io.File

enum class DlStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED }

data class DownloadItem(
    val mediaId: String,
    val titleId: String,
    val titleName: String,
    val isFilm: Boolean,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeName: String,
    val poster: String?,
    val backdrop: String?,
    val durationS: Int,
    val sizeBytes: Long,
    val downloaded: Long = 0,
    val status: DlStatus = DlStatus.QUEUED,
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    val fraction: Float get() = if (sizeBytes <= 0) 0f else (downloaded.toFloat() / sizeBytes).coerceIn(0f, 1f)
    val label: String get() = if (isFilm) titleName else "S$seasonNumber · É$episodeNumber — $episodeName"
}

/** Registre des téléchargements : état observable + persistance JSON (pas de Room). */
object Downloads {
    private lateinit var ctx: Context
    private val _items = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())
    val items: StateFlow<Map<String, DownloadItem>> = _items
    private var lastPersist = 0L

    private val dir get() = File(ctx.filesDir, "v").apply { mkdirs() }
    private val index get() = File(ctx.filesDir, "downloads.json")

    fun file(mediaId: String) = File(dir, "$mediaId.xyd")
    fun partFile(mediaId: String) = File(dir, "$mediaId.part")

    fun init(context: Context) {
        ctx = context.applicationContext
        val loaded = runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        _items.value = loaded.associateBy { it.mediaId }.mapValues { (id, it) ->
            when {
                it.status == DlStatus.DONE && !file(id).exists() -> it.copy(status = DlStatus.FAILED, downloaded = 0, error = "Fichier introuvable")
                it.status == DlStatus.RUNNING -> it.copy(status = DlStatus.QUEUED)
                else -> it
            }
        }
    }

    /** Relance le service si des téléchargements attendent (appelé quand l'app passe au premier plan). */
    fun resumePending(context: Context) {
        if (_items.value.values.any { it.status == DlStatus.QUEUED }) startService(context)
    }

    fun isReady(mediaId: String) = _items.value[mediaId]?.status == DlStatus.DONE && file(mediaId).exists()

    fun enqueue(context: Context, p: Playable) {
        val existing = _items.value[p.mediaId]
        val item = existing?.copy(status = DlStatus.QUEUED, error = null) ?: DownloadItem(
            mediaId = p.mediaId,
            titleId = p.title.id,
            titleName = p.title.name,
            isFilm = p.title.isFilm,
            seasonNumber = p.season?.number ?: 0,
            episodeNumber = p.episode?.number ?: 0,
            episodeName = p.episode?.name.orEmpty(),
            poster = p.title.poster,
            backdrop = p.title.backdrop,
            durationS = p.durationS,
            sizeBytes = p.sizeBytes,
        )
        _items.update { it + (item.mediaId to item) }
        persist(force = true)
        startService(context)
    }

    fun pause(mediaId: String) = set(mediaId) { it.copy(status = DlStatus.PAUSED) }

    fun remove(mediaId: String) {
        _items.update { it - mediaId }
        file(mediaId).delete()
        partFile(mediaId).delete()
        persist(force = true)
    }

    fun removeAll() {
        _items.value.keys.toList().forEach { remove(it) }
        dir.listFiles()?.forEach { it.delete() }
    }

    fun usedBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    internal fun nextQueued(): DownloadItem? =
        _items.value.values.filter { it.status == DlStatus.QUEUED }.minByOrNull { it.addedAt }

    internal fun status(mediaId: String) = _items.value[mediaId]?.status

    internal fun set(mediaId: String, f: (DownloadItem) -> DownloadItem) {
        _items.update { m -> m[mediaId]?.let { m + (mediaId to f(it)) } ?: m }
        persist(force = true)
    }

    internal fun progress(mediaId: String, downloaded: Long, total: Long) {
        _items.update { m ->
            m[mediaId]?.let { m + (mediaId to it.copy(downloaded = downloaded, sizeBytes = if (total > 0) total else it.sizeBytes)) } ?: m
        }
        persist(force = false)
    }

    private fun startService(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    @Synchronized
    private fun persist(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPersist < 5_000) return
        lastPersist = now
        val arr = JSONArray()
        _items.value.values.forEach { arr.put(toJson(it)) }
        runCatching {
            val tmp = File(index.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(index)
        }
    }

    /** Reconstruit un Playable à partir des métadonnées sauvegardées (lecture hors ligne). */
    fun offlinePlayable(d: DownloadItem): Playable {
        val ep = if (d.isFilm) null else Episode(d.mediaId, d.episodeNumber, d.episodeName, "", d.durationS, d.sizeBytes)
        val season = ep?.let { Season("offline-${d.titleId}-${d.seasonNumber}", d.seasonNumber, "", listOf(it)) }
        val title = Title(
            id = if (d.isFilm) d.mediaId else d.titleId, isFilm = d.isFilm, name = d.titleName, synopsis = "",
            poster = d.poster, backdrop = d.backdrop, year = 0,
            durationS = if (d.isFilm) d.durationS else 0, sizeBytes = if (d.isFilm) d.sizeBytes else 0,
            seasons = listOfNotNull(season),
        )
        return Playable(title, season, ep)
    }

    private fun toJson(d: DownloadItem) = JSONObject()
        .put("id", d.mediaId).put("tid", d.titleId).put("tn", d.titleName).put("film", d.isFilm)
        .put("sn", d.seasonNumber).put("en", d.episodeNumber).put("ename", d.episodeName)
        .put("poster", d.poster ?: "").put("backdrop", d.backdrop ?: "").put("dur", d.durationS)
        .put("size", d.sizeBytes).put("done", d.downloaded).put("st", d.status.name)
        .put("err", d.error ?: "").put("at", d.addedAt)

    private fun fromJson(o: JSONObject) = DownloadItem(
        mediaId = o.getString("id"), titleId = o.getString("tid"), titleName = o.optString("tn"),
        isFilm = o.optBoolean("film"), seasonNumber = o.optInt("sn"), episodeNumber = o.optInt("en"),
        episodeName = o.optString("ename"), poster = o.optString("poster").ifBlank { null },
        backdrop = o.optString("backdrop").ifBlank { null }, durationS = o.optInt("dur"),
        sizeBytes = o.optLong("size"), downloaded = o.optLong("done"),
        status = runCatching { DlStatus.valueOf(o.getString("st")) }.getOrDefault(DlStatus.PAUSED),
        error = o.optString("err").ifBlank { null }, addedAt = o.optLong("at"),
    )
}
