package tech.xydhub.xyd.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tech.xydhub.xyd.BuildConfig
import tech.xydhub.xyd.download.Downloads
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime

enum class OrientationMode(val label: String) { AUTO("Auto"), PORTRAIT("Portrait"), LANDSCAPE("Paysage") }

/** État global de l'app (process-scoped). Pas de DI, pas de ViewModel : tout tient ici. */
object Repo {
    lateinit var ctx: Context
        private set
    private lateinit var prefs: SharedPreferences
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    private val _catalog = MutableStateFlow<List<Title>>(emptyList())
    val catalog: StateFlow<List<Title>> = _catalog
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress
    private val dirty = mutableSetOf<String>()

    private val _orientation = MutableStateFlow(OrientationMode.AUTO)
    val orientation: StateFlow<OrientationMode> = _orientation

    /** Version distante plus récente que l'APK installé (null = à jour). */
    val update = MutableStateFlow<AppVersion?>(null)

    private val catalogFile get() = File(ctx.filesDir, "catalog.json")
    private val categoriesFile get() = File(ctx.filesDir, "categories.json")

    fun init(context: Context) {
        ctx = context.applicationContext
        prefs = ctx.getSharedPreferences("xyd", Context.MODE_PRIVATE)
        _session.value = prefs.getString("session", null)?.let { runCatching { sessionFromJson(it) }.getOrNull() }
        _orientation.value = runCatching { OrientationMode.valueOf(prefs.getString("orientation", "AUTO")!!) }
            .getOrDefault(OrientationMode.AUTO)
        loadLocalProgress()
        runCatching { if (catalogFile.exists()) _catalog.value = Parse.catalog(catalogFile.readText()) }
        runCatching { if (categoriesFile.exists()) _categories.value = Parse.categories(categoriesFile.readText()) }
        Downloads.init(ctx)
    }

    // ---------- Session ----------

    fun saveSession(s: Session?) {
        _session.value = s
        prefs.edit().apply {
            if (s == null) remove("session") else putString("session", sessionToJson(s))
        }.apply()
    }

    suspend fun signIn(email: String, password: String) {
        saveSession(Supabase.signIn(email.trim(), password))
        onSignedIn()
    }

    suspend fun signUp(email: String, password: String, name: String) {
        saveSession(Supabase.signUp(email.trim(), password, name.trim()))
        onSignedIn()
    }

    private fun onSignedIn() {
        refresh()
    }

    fun signOut() {
        val s = _session.value ?: return
        scope.launch {
            runCatching { syncProgress() }
            Supabase.signOut(s)
        }
        saveSession(null)
        _progress.value = emptyMap()
        dirty.clear()
        prefs.edit().remove("progress").apply()
    }

    private fun sessionToJson(s: Session) = JSONObject()
        .put("a", s.accessToken).put("r", s.refreshToken).put("e", s.expiresAt)
        .put("u", s.userId).put("m", s.email).put("n", s.name).toString()

    private fun sessionFromJson(j: String) = JSONObject(j).let {
        Session(it.getString("a"), it.getString("r"), it.getLong("e"), it.getString("u"), it.getString("m"), it.optString("n"))
    }

    // ---------- Catalogue ----------

    fun refresh() {
        if (_session.value == null || loading.value) return
        scope.launch {
            loading.value = true
            try {
                val json = Supabase.fetchCatalog()
                _catalog.value = Parse.catalog(json)
                error.value = null
                withContext(Dispatchers.IO) { catalogFile.writeText(json) }
                // Catégories : facultatives (table absente = simplement pas de rangées personnalisées).
                runCatching {
                    val cj = Supabase.fetchCategories()
                    _categories.value = Parse.categories(cj)
                    withContext(Dispatchers.IO) { categoriesFile.writeText(cj) }
                }
                syncProgress()
            } catch (e: Exception) {
                error.value = if (_catalog.value.isEmpty()) (e.message ?: "Erreur réseau") else null
            } finally {
                loading.value = false
            }
        }
    }

    fun playable(mediaId: String): Playable? {
        for (t in _catalog.value) {
            if (t.isFilm && t.id == mediaId) return Playable(t, null, null)
            for (s in t.seasons) for (e in s.episodes) if (e.id == mediaId) return Playable(t, s, e)
        }
        return Downloads.items.value[mediaId]?.let { Downloads.offlinePlayable(it) }
    }

    fun next(p: Playable): Playable? {
        val season = p.season ?: return null
        val ep = p.episode ?: return null
        val i = season.episodes.indexOfFirst { it.id == ep.id }
        if (i >= 0 && i + 1 < season.episodes.size) return Playable(p.title, season, season.episodes[i + 1])
        val si = p.title.seasons.indexOfFirst { it.id == season.id }
        val ns = p.title.seasons.getOrNull(si + 1) ?: return null
        return ns.episodes.firstOrNull()?.let { Playable(p.title, ns, it) }
    }

    /** Point de reprise d'une série : 1er épisode entamé non fini, sinon le suivant du dernier vu. */
    fun resumeTarget(t: Title): Playable? {
        if (t.isFilm) return Playable(t, null, null)
        val all = t.seasons.flatMap { s -> s.episodes.map { Playable(t, s, it) } }
        val prog = _progress.value
        val last = all.filter { prog[it.mediaId] != null }.maxByOrNull { prog[it.mediaId]!!.updatedAt }
            ?: return all.firstOrNull()
        return if (prog[last.mediaId]!!.finished) next(last) ?: last else last
    }

    /** « Continuer à regarder » : dernier élément entamé de chaque titre, du plus récent au plus ancien. */
    fun continueWatching(catalog: List<Title>, prog: Map<String, Progress>): List<Pair<Playable, Progress>> =
        catalog.mapNotNull { t ->
            val items = if (t.isFilm) listOf(Playable(t, null, null))
            else t.seasons.flatMap { s -> s.episodes.map { Playable(t, s, it) } }
            items.mapNotNull { p -> prog[p.mediaId]?.let { p to it } }
                .filter { !it.second.finished && it.second.positionMs > 5_000 }
                .maxByOrNull { it.second.updatedAt }
        }.sortedByDescending { it.second.updatedAt }

    // ---------- Progression ----------

    fun saveProgress(mediaId: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val p = Progress(positionMs, durationMs, System.currentTimeMillis())
        _progress.value = _progress.value + (mediaId to p)
        synchronized(dirty) { dirty += mediaId }
        persistProgress()
    }

    private fun persistProgress() {
        val o = JSONObject()
        _progress.value.forEach { (k, v) -> o.put(k, JSONArray().put(v.positionMs).put(v.durationMs).put(v.updatedAt)) }
        val d = JSONArray(synchronized(dirty) { dirty.toList() })
        prefs.edit().putString("progress", o.toString()).putString("dirty", d.toString()).apply()
    }

    private fun loadLocalProgress() {
        runCatching {
            val o = JSONObject(prefs.getString("progress", "{}")!!)
            _progress.value = o.keys().asSequence().associateWith { k ->
                val a = o.getJSONArray(k)
                Progress(a.getLong(0), a.getLong(1), a.getLong(2))
            }
            val d = JSONArray(prefs.getString("dirty", "[]")!!)
            for (i in 0 until d.length()) dirty += d.getString(i)
        }
    }

    /** Envoie les progressions locales modifiées puis fusionne le distant (le plus récent gagne). */
    suspend fun syncProgress() {
        val s = _session.value ?: return
        val toPush = synchronized(dirty) { dirty.toList() }
        if (toPush.isNotEmpty()) {
            val arr = JSONArray()
            toPush.forEach { id ->
                val p = _progress.value[id] ?: return@forEach
                arr.put(
                    JSONObject().put("user_id", s.userId).put("media_id", id)
                        .put("position_ms", p.positionMs).put("duration_ms", p.durationMs)
                        .put("updated_at", Instant.ofEpochMilli(p.updatedAt).toString())
                )
            }
            Supabase.pushProgress(arr.toString())
            synchronized(dirty) { dirty.removeAll(toPush.toSet()) }
        }
        val remote = JSONArray(Supabase.fetchProgress())
        val merged = _progress.value.toMutableMap()
        for (i in 0 until remote.length()) {
            val o = remote.getJSONObject(i)
            val id = o.getString("media_id")
            val at = runCatching { OffsetDateTime.parse(o.getString("updated_at")).toInstant().toEpochMilli() }.getOrDefault(0)
            val local = merged[id]
            if (local == null || at > local.updatedAt) {
                merged[id] = Progress(o.optLong("position_ms"), o.optLong("duration_ms"), at)
            }
        }
        _progress.value = merged
        persistProgress()
    }

    fun syncProgressLater() {
        scope.launch { runCatching { syncProgress() } }
    }

    // ---------- Réglages ----------

    fun setOrientation(m: OrientationMode) {
        _orientation.value = m
        prefs.edit().putString("orientation", m.name).apply()
    }

    // ---------- Mises à jour ----------

    suspend fun checkUpdate() {
        if (!Supabase.configured) return
        val v = runCatching { Supabase.latestVersion() }.getOrNull() ?: return
        update.value = if (v.versionCode > BuildConfig.VERSION_CODE && v.apkUrl.isNotBlank()) v else null
    }
}
