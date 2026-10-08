package tech.xydhub.xyd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.xydhub.xyd.BuildConfig
import tech.xydhub.xyd.data.OrientationMode
import tech.xydhub.xyd.data.Playable
import tech.xydhub.xyd.data.Repo
import tech.xydhub.xyd.data.Supabase
import tech.xydhub.xyd.data.Title
import tech.xydhub.xyd.download.DlStatus
import tech.xydhub.xyd.download.DownloadItem
import tech.xydhub.xyd.download.Downloads

/** Bottom sheet standard de l'app (fond noir, poignée discrète). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XSheet(
    onDismiss: () -> Unit,
    locked: Boolean = false,
    content: @Composable () -> Unit,
) {
    val state = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { !locked || it != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { if (!locked) onDismiss() },
        sheetState = state,
        containerColor = Xyd.Surface,
        contentColor = Xyd.Text,
        scrimColor = Color(0xB3000000),
        dragHandle = if (locked) null else {
            { Box(Modifier.padding(vertical = 10.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Xyd.Line)) }
        },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = !locked),
    ) { content() }
}

// ---------------------------------------------------------------- Auth

@Composable
fun AuthSheet() {
    var signup by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        err = when {
            !Supabase.configured -> "Application non configurée (Supabase)."
            signup && name.isBlank() -> "Indique ton prénom"
            !email.contains('@') -> "Adresse email invalide"
            pass.length < 6 -> "Mot de passe : 6 caractères minimum"
            else -> null
        }
        if (err != null) return
        busy = true
        scope.launch {
            try {
                if (signup) Repo.signUp(email, pass, name) else Repo.signIn(email, pass)
            } catch (e: Exception) {
                err = if (e is java.net.UnknownHostException) "Pas de connexion internet" else e.message ?: "Erreur"
            } finally {
                busy = false
            }
        }
    }

    XSheet(onDismiss = {}, locked = true) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(top = 28.dp, bottom = 24.dp)
                .navigationBarsPadding().imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Logo(56.dp)
            Spacer(Modifier.height(16.dp))
            Text(if (signup) "Créer un compte" else "Connexion", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(24.dp))
            if (signup) {
                Field(name, { name = it }, "Prénom", KeyboardType.Text)
                Spacer(Modifier.height(12.dp))
            }
            Field(email, { email = it.trim() }, "Email", KeyboardType.Email)
            Spacer(Modifier.height(12.dp))
            Field(pass, { pass = it }, "Mot de passe", KeyboardType.Password, password = true, done = ::submit)
            err?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Xyd.Danger, fontSize = 13.sp)
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = ::submit, enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Xyd.Grey, contentColor = Xyd.Black),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Xyd.Black, strokeWidth = 2.dp)
                else Text(if (signup) "S'inscrire" else "Se connecter", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { signup = !signup; err = null }) {
                Text(if (signup) "J'ai déjà un compte" else "Pas de compte ? S'inscrire", color = Xyd.Muted)
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    type: KeyboardType,
    password: Boolean = false,
    done: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (done != null) ImeAction.Done else ImeAction.Next),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { done?.invoke() }),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Xyd.Grey, unfocusedBorderColor = Xyd.Line,
            focusedLabelColor = Xyd.Grey, unfocusedLabelColor = Xyd.Muted, cursorColor = Xyd.Grey,
        ),
    )
}

// ---------------------------------------------------------------- Titre (série / film)

@Composable
fun TitleSheet(t: Title, onPlay: (String) -> Unit, download: (Playable) -> Unit, onDismiss: () -> Unit) {
    val progress by Repo.progress.collectAsStateWithLifecycle()
    val downloads by Downloads.items.collectAsStateWithLifecycle()
    val target = remember(t, progress) { Repo.resumeTarget(t) }
    var seasonIdx by rememberSaveable(t.id) {
        mutableIntStateOf(t.seasons.indexOfFirst { it.id == target?.season?.id }.coerceAtLeast(0))
    }

    XSheet(onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    RemoteImage(t.backdrop ?: t.poster, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Xyd.Surface))))
                }
            }
            item {
                Column(Modifier.padding(horizontal = 20.dp)) {
                    Text(t.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    val meta = listOfNotNull(
                        t.year.takeIf { it > 0 }?.toString(),
                        if (t.isFilm) fmtDuration(t.durationS).ifBlank { null }
                        else "${t.seasons.size} saison${if (t.seasons.size > 1) "s" else ""}",
                        if (t.isFilm) fmtSize(t.sizeBytes).ifBlank { null } else null,
                    )
                    Text(meta.joinToString(" · "), color = Xyd.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (t.synopsis.isNotBlank()) {
                        var expanded by remember { mutableStateOf(false) }
                        Text(
                            t.synopsis, color = Xyd.Grey, fontSize = 14.sp, lineHeight = 20.sp,
                            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp).clickable { expanded = !expanded },
                        )
                    }
                    target?.let { p ->
                        Spacer(Modifier.height(16.dp))
                        MainAction(p, downloads[p.mediaId], progress[p.mediaId]?.positionMs ?: 0, onPlay, download)
                    }
                }
            }
            if (!t.isFilm && t.seasons.isNotEmpty()) {
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(t.seasons.size) { i ->
                            val s = t.seasons[i]
                            FilterChip(
                                selected = i == seasonIdx, onClick = { seasonIdx = i },
                                label = { Text(s.name.ifBlank { "Saison ${s.number}" }) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Xyd.Grey, selectedLabelColor = Xyd.Black,
                                    labelColor = Xyd.Grey,
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = i == seasonIdx, borderColor = Xyd.Line,
                                ),
                            )
                        }
                    }
                }
                val season = t.seasons[seasonIdx.coerceIn(0, t.seasons.lastIndex)]
                items(season.episodes, key = { it.id }) { e ->
                    val p = Playable(t, season, e)
                    EpisodeRow(p, downloads[p.mediaId], progress[p.mediaId]?.fraction ?: 0f, onPlay, download)
                }
            }
        }
    }
}

@Composable
private fun MainAction(p: Playable, d: DownloadItem?, posMs: Long, onPlay: (String) -> Unit, download: (Playable) -> Unit) {
    val label = when (d?.status) {
        DlStatus.DONE -> (if (posMs > 5_000) "Reprendre" else "Lire") + if (p.episode != null) " · ${p.shortLabel}" else ""
        DlStatus.RUNNING -> "Téléchargement… ${(d!!.fraction * 100).toInt()} %"
        DlStatus.QUEUED -> "En attente…"
        DlStatus.PAUSED -> "Reprendre le téléchargement"
        DlStatus.FAILED -> "Réessayer le téléchargement"
        null -> "Télécharger" + (if (p.episode != null) " · ${p.shortLabel}" else "") +
            fmtSize(p.sizeBytes).let { if (it.isBlank()) "" else " ($it)" }
    }
    Column {
        Button(
            onClick = { primaryAction(p, d, onPlay, download) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Xyd.Grey, contentColor = Xyd.Black),
        ) {
            Icon(if (d?.status == DlStatus.DONE) Icons.Filled.PlayArrow else XIcons.Download, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (d != null && d.status == DlStatus.RUNNING) {
            LinearProgressIndicator(
                progress = { d.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp),
                color = Xyd.Grey, trackColor = Xyd.Line, drawStopIndicator = {},
            )
        }
        d?.takeIf { it.status == DlStatus.FAILED }?.error?.let {
            Text(it, color = Xyd.Danger, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun EpisodeRow(p: Playable, d: DownloadItem?, watched: Float, onPlay: (String) -> Unit, download: (Playable) -> Unit) {
    val e = p.episode!!
    Row(
        Modifier.fillMaxWidth().clickable { primaryAction(p, d, onPlay, download) }
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${e.number}", color = Xyd.Muted, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name.ifBlank { "Épisode ${e.number}" }, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = when (d?.status) {
                DlStatus.RUNNING -> "${(d!!.fraction * 100).toInt()} % · ${fmtSize(d.downloaded)} / ${fmtSize(d.sizeBytes)}"
                DlStatus.QUEUED -> "En attente"
                DlStatus.PAUSED -> "En pause · ${(d!!.fraction * 100).toInt()} %"
                DlStatus.FAILED -> d!!.error ?: "Échec"
                else -> listOf(fmtDuration(e.durationS), fmtSize(e.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · ")
            }
            Text(sub, color = if (d?.status == DlStatus.FAILED) Xyd.Danger else Xyd.Muted, fontSize = 12.sp, maxLines = 1)
            if (watched > 0f) {
                LinearProgressIndicator(
                    progress = { watched },
                    modifier = Modifier.padding(top = 6.dp).fillMaxWidth(0.6f).height(2.dp),
                    color = Xyd.Grey, trackColor = Xyd.Line, drawStopIndicator = {},
                )
            }
        }
        DownloadButton(p, onPlay, download)
    }
}

// ---------------------------------------------------------------- Téléchargements

/** Téléchargements groupés : un bloc par série/film, puis par saison. Filtre Tout / Séries / Films. */
@Composable
fun DownloadsSheet(onPlay: (String) -> Unit, onDismiss: () -> Unit) {
    val items by Downloads.items.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val used = remember(items) { Downloads.usedBytes() }
    var filter by rememberSaveable { mutableIntStateOf(0) } // 0 = tout, 1 = séries, 2 = films
    val expanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }

    // Groupes triés par activité la plus récente.
    val groups = remember(items) {
        items.values.groupBy { if (it.isFilm) it.mediaId else it.titleId }
            .values.sortedByDescending { g -> g.maxOf { it.addedAt } }
    }
    val hasSeries = groups.any { !it.first().isFilm }
    val hasFilms = groups.any { it.first().isFilm }
    val shown = groups.filter {
        when (filter) { 1 -> !it.first().isFilm; 2 -> it.first().isFilm; else -> true }
    }

    fun act(d: DownloadItem) = when (d.status) {
        DlStatus.DONE -> onPlay(d.mediaId)
        DlStatus.RUNNING, DlStatus.QUEUED -> Downloads.pause(d.mediaId)
        else -> Downloads.enqueue(ctx, Repo.playable(d.mediaId) ?: Downloads.offlinePlayable(d))
    }

    XSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Téléchargements", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(fmtSize(used).ifBlank { "0 Mo" }, color = Xyd.Muted, fontSize = 13.sp)
            }
            if (hasSeries && hasFilms) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Tout", "Séries", "Films").forEachIndexed { i, label ->
                        FilterChip(
                            selected = filter == i, onClick = { filter = i }, label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Xyd.Grey, selectedLabelColor = Xyd.Black, labelColor = Xyd.Grey,
                            ),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = filter == i, borderColor = Xyd.Line),
                        )
                    }
                }
            }
            if (items.isEmpty()) {
                Text(
                    "Aucun téléchargement.\nOuvre une série ou un film et appuie sur Télécharger.",
                    color = Xyd.Muted, modifier = Modifier.padding(20.dp), lineHeight = 20.sp,
                )
            }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                shown.forEach { group ->
                    val first = group.first()
                    val key = if (first.isFilm) first.mediaId else first.titleId
                    if (first.isFilm) {
                        item(key = key) { FilmDownloadRow(first, onClick = { act(first) }) }
                    } else {
                        val active = group.any { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }
                        val open = expanded[key] ?: (active || shown.size == 1)
                        item(key = key) {
                            SeriesDownloadHeader(group, open, onToggle = { expanded[key] = !open })
                        }
                        if (open) {
                            group.groupBy { it.seasonNumber }.toSortedMap().forEach { (season, eps) ->
                                item(key = "$key-s$season") {
                                    Text(
                                        "SAISON $season", color = Xyd.Muted, fontSize = 11.sp, letterSpacing = 1.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 36.dp, top = 10.dp, bottom = 2.dp),
                                    )
                                }
                                eps.sortedBy { it.episodeNumber }.forEach { d ->
                                    item(key = d.mediaId) { EpisodeDownloadRow(d, onClick = { act(d) }) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusText(d: DownloadItem): String = when (d.status) {
    DlStatus.DONE -> fmtSize(d.sizeBytes)
    DlStatus.RUNNING -> "${(d.fraction * 100).toInt()} % · ${fmtSize(d.downloaded)} / ${fmtSize(d.sizeBytes)}"
    DlStatus.QUEUED -> "En attente"
    DlStatus.PAUSED -> "En pause · ${(d.fraction * 100).toInt()} %"
    DlStatus.FAILED -> "Échec : ${d.error ?: "erreur"}"
}

@Composable
private fun DownloadProgress(d: DownloadItem) {
    if (d.status == DlStatus.RUNNING || d.status == DlStatus.PAUSED) {
        LinearProgressIndicator(
            progress = { d.fraction },
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(2.dp),
            color = if (d.status == DlStatus.RUNNING) Xyd.Grey else Xyd.Muted, trackColor = Xyd.Line, drawStopIndicator = {},
        )
    }
}

@Composable
private fun FilmDownloadRow(d: DownloadItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(d.backdrop ?: d.poster, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(d.titleName, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Film · " + statusText(d), color = if (d.status == DlStatus.FAILED) Xyd.Danger else Xyd.Muted, fontSize = 12.sp, maxLines = 2)
            DownloadProgress(d)
        }
        IconButton(onClick = { Downloads.remove(d.mediaId) }) { Icon(Icons.Filled.Delete, "Supprimer", tint = Xyd.Muted) }
    }
}

@Composable
private fun SeriesDownloadHeader(group: List<DownloadItem>, open: Boolean, onToggle: () -> Unit) {
    val first = group.first()
    val done = group.count { it.status == DlStatus.DONE }
    val running = group.filter { it.status == DlStatus.RUNNING }
    val seasons = group.map { it.seasonNumber }.distinct().size
    val size = group.filter { it.status == DlStatus.DONE }.sumOf { it.sizeBytes }
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(first.backdrop ?: first.poster, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(first.titleName, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = listOfNotNull(
                "${group.size} épisode${if (group.size > 1) "s" else ""}",
                if (seasons > 1) "$seasons saisons" else null,
                fmtSize(size).ifBlank { null },
                if (running.isNotEmpty()) "${running.size} en cours" else if (done < group.size) "${group.size - done} en attente" else null,
            )
            Text(parts.joinToString(" · "), color = Xyd.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (confirm) {
            TextButton(onClick = { confirm = false }) { Text("Non", color = Xyd.Muted) }
            TextButton(onClick = { group.forEach { Downloads.remove(it.mediaId) } }) { Text("Tout suppr.", color = Xyd.Danger) }
        } else {
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Supprimer la série", tint = Xyd.Muted) }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                tint = Xyd.Grey, modifier = Modifier.padding(end = 12.dp),
            )
        }
    }
}

@Composable
private fun EpisodeDownloadRow(d: DownloadItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 36.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${d.episodeNumber}", color = Xyd.Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(d.episodeName.ifBlank { "Épisode ${d.episodeNumber}" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(statusText(d), color = if (d.status == DlStatus.FAILED) Xyd.Danger else Xyd.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DownloadProgress(d)
        }
        if (d.status == DlStatus.DONE) {
            Icon(Icons.Filled.PlayArrow, "Lire", tint = Xyd.Grey, modifier = Modifier.padding(horizontal = 4.dp))
        }
        IconButton(onClick = { Downloads.remove(d.mediaId) }) { Icon(Icons.Filled.Close, "Supprimer", tint = Xyd.Muted, modifier = Modifier.size(18.dp)) }
    }
}

// ---------------------------------------------------------------- Réglages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val session by Repo.session.collectAsStateWithLifecycle()
    val orientation by Repo.orientation.collectAsStateWithLifecycle()
    val items by Downloads.items.collectAsStateWithLifecycle()
    val used = remember(items) { Downloads.usedBytes() }
    var confirmClear by remember { mutableStateOf(false) }

    XSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Réglages", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp))

            session?.let {
                Label("Compte")
                Text(it.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(it.email, color = Xyd.Muted, fontSize = 13.sp)
            }

            Label("Orientation du lecteur")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                OrientationMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = orientation == m,
                        onClick = { Repo.setOrientation(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, OrientationMode.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = Xyd.Grey, activeContentColor = Xyd.Black,
                            inactiveContainerColor = Color.Transparent, inactiveContentColor = Xyd.Grey,
                            activeBorderColor = Xyd.Grey, inactiveBorderColor = Xyd.Line,
                        ),
                    ) { Text(m.label) }
                }
            }

            Label("Stockage")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vidéos téléchargées : ${fmtSize(used).ifBlank { "0 Mo" }}", color = Xyd.Grey, modifier = Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = used > 0) { Text("Tout supprimer", color = Xyd.Danger) }
            }
            if (confirmClear) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Supprimer toutes les vidéos ?", color = Xyd.Text, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { confirmClear = false }) { Text("Annuler", color = Xyd.Muted) }
                    TextButton(onClick = { Downloads.removeAll(); confirmClear = false }) { Text("Oui", color = Xyd.Danger) }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = Xyd.Line)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { onDismiss(); Repo.signOut() },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
            ) { Text("Se déconnecter", color = Xyd.Grey) }
            Text(
                "xyd ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                color = Xyd.Muted, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text.uppercase(), color = Xyd.Muted, fontSize = 11.sp, letterSpacing = 1.sp,
        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}
