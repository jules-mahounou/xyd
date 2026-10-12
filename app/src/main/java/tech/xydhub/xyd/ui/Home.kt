package tech.xydhub.xyd.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.xydhub.xyd.data.Playable
import tech.xydhub.xyd.data.Progress
import tech.xydhub.xyd.data.Repo
import tech.xydhub.xyd.data.Title
import tech.xydhub.xyd.download.DlStatus
import tech.xydhub.xyd.download.DownloadItem
import tech.xydhub.xyd.download.Downloads

/** Feuilles ouvrables depuis l'accueil. */
private const val SHEET_DOWNLOADS = "#downloads"
private const val SHEET_SETTINGS = "#settings"

/** Filtres en haut de l'accueil : "all", "serie", "film" ou "cat:<id>". */
private const val F_ALL = "all"
private const val F_SERIES = "serie"
private const val F_FILMS = "film"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onPlay: (String) -> Unit) {
    val act = LocalContext.current as Activity
    LaunchedEffect(Unit) { act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }

    val session by Repo.session.collectAsStateWithLifecycle()
    val catalog by Repo.catalog.collectAsStateWithLifecycle()
    val categories by Repo.categories.collectAsStateWithLifecycle()
    val progress by Repo.progress.collectAsStateWithLifecycle()
    val hidden by Repo.hiddenResume.collectAsStateWithLifecycle()
    val loading by Repo.loading.collectAsStateWithLifecycle()
    val error by Repo.error.collectAsStateWithLifecycle()
    val downloads by Downloads.items.collectAsStateWithLifecycle()

    // Valeur = id d'un titre, ou SHEET_DOWNLOADS / SHEET_SETTINGS.
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(F_ALL) }
    var resumeMenu by rememberSaveable { mutableStateOf<String?>(null) } // mediaId
    val download = rememberDownloader()

    // Rangées de l'admin : titres résolus dans l'ordre choisi, catégories vides masquées.
    val allRows = remember(catalog, categories) {
        val byId = catalog.associateBy { it.id }
        categories.map { c -> c to c.titleIds.mapNotNull { byId[it] } }.filter { it.second.isNotEmpty() }
    }
    val catFilter = filter.removePrefix("cat:").takeIf { filter.startsWith("cat:") }
    val keep: (Title) -> Boolean = when (filter) {
        F_SERIES -> { t -> !t.isFilm }
        F_FILMS -> { t -> t.isFilm }
        else -> { _ -> true }
    }
    val catTitles = allRows.firstOrNull { it.first.id == catFilter }?.second
    val visible = remember(catalog, filter, catTitles) { catTitles ?: catalog.filter(keep) }
    val rows = remember(allRows, filter) {
        if (catFilter != null) emptyList()
        else allRows.map { (c, l) -> c to l.filter(keep) }.filter { it.second.isNotEmpty() }
    }
    val resume = remember(catalog, progress, hidden, filter, catTitles) {
        Repo.continueWatching(catalog, progress, hidden)
            .filter { (p, _) -> if (catTitles != null) catTitles.any { it.id == p.title.id } else keep(p.title) }
    }
    // À la une : titres marqués dans l'admin, sinon les 3 plus récents.
    val hero = remember(visible) {
        visible.filter { it.featured }.take(6).ifEmpty { visible.sortedByDescending { it.createdAt }.take(3) }
    }
    val active = downloads.values.count { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }

    val header: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = statusBarTop() + 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(30.dp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { sheet = SHEET_DOWNLOADS }) {
                BadgedBox(badge = { if (active > 0) Badge(containerColor = Xyd.Grey) { Text("$active", color = Xyd.Black) } }) {
                    Icon(XIcons.Download, "Téléchargements", tint = Color.White)
                }
            }
            IconButton(onClick = { sheet = SHEET_SETTINGS }) {
                Icon(Icons.Filled.Settings, "Réglages", tint = Color.White)
            }
        }
    }
    val chips: @Composable () -> Unit = {
        val options = buildList {
            add(F_ALL to "Tout")
            if (catalog.any { !it.isFilm }) add(F_SERIES to "Séries")
            if (catalog.any { it.isFilm }) add(F_FILMS to "Films")
            allRows.forEach { (c, _) -> add("cat:${c.id}" to c.name) }
        }
        if (options.size > 2) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(options, key = { it.first }) { (key, label) ->
                    val on = filter == key
                    Text(
                        label,
                        Modifier.clip(RoundedCornerShape(18.dp))
                            .background(if (on) Xyd.Grey else Color(0x33000000))
                            .border(1.dp, if (on) Xyd.Grey else Color(0x66FFFFFF), RoundedCornerShape(18.dp))
                            .clickable { filter = if (on && key != F_ALL) F_ALL else key }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        color = if (on) Xyd.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }

    PullToRefreshBox(isRefreshing = loading, onRefresh = { Repo.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            when {
                catalog.isEmpty() && session != null && (loading || error == null) -> {
                    item { header() }
                    item { SkeletonHome() }
                }
                hero.isNotEmpty() -> item(key = "hero-$filter") {
                    Hero(
                        titles = hero, downloads = downloads, header = header, chips = chips,
                        onInfo = { sheet = it.id },
                        onAction = { t ->
                            val target = Repo.resumeTarget(t) ?: return@Hero
                            primaryAction(target, downloads[target.mediaId], onPlay, download)
                        },
                    )
                }
                else -> {
                    item { header() }
                    item { chips() }
                }
            }

            if (resume.isNotEmpty()) {
                item { SectionTitle("Continuer à regarder") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(resume, key = { it.first.mediaId }) { (p, prog) ->
                            ResumeCard(
                                p, prog, downloads[p.mediaId],
                                onClick = { if (Downloads.isReady(p.mediaId)) onPlay(p.mediaId) else sheet = p.title.id },
                                onMenu = { resumeMenu = p.mediaId },
                            )
                        }
                    }
                }
            }

            rows.forEach { (c, list) ->
                item(key = "cat-" + c.id) { SectionTitle(c.name) }
                item(key = "row-" + c.id) {
                    if (c.ranked) RankedRow(list) { sheet = it.id } else PosterRow(list) { sheet = it.id }
                }
            }

            when {
                // Filtre catégorie, Séries ou Films : grille complète.
                catTitles != null || filter == F_SERIES || filter == F_FILMS -> {
                    val label = when {
                        catTitles != null -> allRows.first { it.first.id == catFilter }.first.name
                        filter == F_SERIES -> "Toutes les séries"
                        else -> "Tous les films"
                    }
                    if (visible.isNotEmpty()) {
                        item { SectionTitle(label) }
                        posterGrid(visible) { sheet = it.id }
                    }
                }
                else -> {
                    val series = visible.filter { !it.isFilm }
                    val films = visible.filter { it.isFilm }
                    if (series.isNotEmpty()) {
                        item { SectionTitle("Séries") }
                        item { PosterRow(series) { sheet = it.id } }
                    }
                    if (films.isNotEmpty()) {
                        item { SectionTitle("Films") }
                        item { PosterRow(films) { sheet = it.id } }
                    }
                }
            }

            if (catalog.isEmpty() && session != null && !loading && error != null) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 120.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Logo(64.dp)
                        Spacer(Modifier.height(20.dp))
                        Text(error ?: "Aucun contenu pour le moment", color = Xyd.Muted, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(onClick = { Repo.refresh() }) { Text("Réessayer", color = Xyd.Grey) }
                    }
                }
            } else if (catalog.isEmpty() && session != null && !loading) {
                item {
                    Text(
                        "Aucun contenu pour le moment", color = Xyd.Muted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 80.dp),
                    )
                }
            }
        }
    }

    resumeMenu?.let { id ->
        val entry = resume.firstOrNull { it.first.mediaId == id }
        if (entry == null) {
            resumeMenu = null
        } else {
            ResumeMenuSheet(
                p = entry.first, d = downloads[id],
                onDismiss = { resumeMenu = null },
                onPlay = { resumeMenu = null; if (Downloads.isReady(id)) onPlay(id) else sheet = entry.first.title.id },
                onInfo = { resumeMenu = null; sheet = entry.first.title.id },
            )
        }
    }

    when (val s = sheet) {
        null -> {}
        SHEET_DOWNLOADS -> DownloadsSheet(onPlay = { sheet = null; onPlay(it) }, onDismiss = { sheet = null })
        SHEET_SETTINGS -> SettingsSheet(onDismiss = { sheet = null })
        else -> catalog.firstOrNull { it.id == s }?.let { t ->
            TitleSheet(t, onPlay = { sheet = null; onPlay(it) }, download = download, onDismiss = { sheet = null })
        } ?: run { sheet = null }
    }

    if (session == null) AuthSheet()
}

// ---------------------------------------------------------------- Bannière « à la une »

@Composable
private fun Hero(
    titles: List<Title>,
    downloads: Map<String, DownloadItem>,
    header: @Composable () -> Unit,
    chips: @Composable () -> Unit,
    onInfo: (Title) -> Unit,
    onAction: (Title) -> Unit,
) {
    val pager = rememberPagerState { titles.size }
    // Défilement automatique toutes les 7 s (suspendu pendant un glissement).
    LaunchedEffect(pager, titles.size) {
        if (titles.size < 2) return@LaunchedEffect
        while (true) {
            delay(7_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % titles.size)
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(0.7f)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val t = titles[page]
            Box(Modifier.fillMaxSize()) {
                RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(0f to Color(0x99000000), 0.25f to Color.Transparent, 0.55f to Color.Transparent, 1f to Xyd.Black)
                    )
                )
                HeroInfo(t, downloads, onInfo, onAction, Modifier.align(Alignment.BottomCenter))
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth()) {
            header()
            chips()
        }
        if (titles.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(titles.size) { i ->
                    Box(
                        Modifier.size(if (i == pager.currentPage) 16.dp else 6.dp, 6.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) Xyd.Grey else Color(0x55FFFFFF))
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroInfo(
    t: Title,
    downloads: Map<String, DownloadItem>,
    onInfo: (Title) -> Unit,
    onAction: (Title) -> Unit,
    modifier: Modifier,
) {
    val progress by Repo.progress.collectAsStateWithLifecycle()
    val target = remember(t, progress) { Repo.resumeTarget(t) }
    val d = target?.let { downloads[it.mediaId] }
    Column(modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            t.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 34.sp,
        )
        val meta = listOfNotNull(
            if (t.isFilm) "Film" else "Série",
            t.year.takeIf { it > 0 }?.toString(),
            if (t.isFilm) fmtDuration(t.durationS).ifBlank { null }
            else "${t.seasons.size} saison${if (t.seasons.size > 1) "s" else ""}",
        )
        Text(meta.joinToString(" · "), color = Xyd.Grey, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val pos = target?.let { progress[it.mediaId] }
            val (icon, label) = when (d?.status) {
                DlStatus.DONE -> Icons.Filled.PlayArrow to (if ((pos?.positionMs ?: 0) > 5_000 && pos?.finished != true) "Reprendre" else "Lecture")
                DlStatus.RUNNING -> XIcons.Pause to "${(d!!.fraction * 100).toInt()} %"
                DlStatus.QUEUED -> XIcons.Pause to "En attente"
                DlStatus.PAUSED -> XIcons.Download to "Reprendre"
                DlStatus.FAILED -> XIcons.Download to "Réessayer"
                null -> XIcons.Download to "Télécharger"
            }
            Button(
                onClick = { onAction(t) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Xyd.Grey, contentColor = Xyd.Black),
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
            ) {
                Icon(icon, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { onInfo(t) },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x55FFFFFF), contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Filled.Info, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("Infos", fontWeight = FontWeight.SemiBold)
            }
        }
        if (target?.episode != null) {
            Text(target.label, color = Xyd.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- Chargement fluide

@Composable
private fun SkeletonHome() {
    Column {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().aspectRatio(0.85f).clip(RoundedCornerShape(16.dp)).shimmer())
        repeat(2) {
            Box(Modifier.padding(start = 20.dp, top = 20.dp, bottom = 12.dp).size(140.dp, 18.dp).clip(RoundedCornerShape(6.dp)).shimmer())
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { Box(Modifier.width(118.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).shimmer()) }
            }
        }
    }
}

// ---------------------------------------------------------------- Rangées & grilles

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
        color = Xyd.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun Poster(t: Title, modifier: Modifier, onClick: (Title) -> Unit) {
    Column(modifier.clickable { onClick(t) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
            RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize())
            if (t.poster == null && t.backdrop == null) {
                Text(t.name, Modifier.align(Alignment.Center).padding(8.dp), color = Xyd.Grey, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(t.name, Modifier.padding(top = 6.dp), color = Xyd.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PosterRow(titles: List<Title>, onClick: (Title) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(titles, key = { it.id }) { t -> Poster(t, Modifier.width(118.dp), onClick) }
    }
}

/** Grille 3 colonnes insérée dans la LazyColumn (une ligne = un item). */
private fun androidx.compose.foundation.lazy.LazyListScope.posterGrid(titles: List<Title>, onClick: (Title) -> Unit) {
    items(titles.chunked(3), key = { row -> "grid-" + row.first().id }) { row ->
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { t -> Poster(t, Modifier.weight(1f), onClick) }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** Rangée « classement » : grand numéro à gauche de l'affiche (Top 10). */
@Composable
private fun RankedRow(titles: List<Title>, onClick: (Title) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(titles, key = { _, t -> t.id }) { i, t ->
            Box(Modifier.width(if (i + 1 >= 10) 186.dp else 160.dp).clickable { onClick(t) }) {
                Text(
                    "${i + 1}",
                    Modifier.align(Alignment.BottomStart).offset(y = 18.dp),
                    color = Color(0xFF3A3A3A), fontSize = 112.sp, fontWeight = FontWeight.Black,
                    letterSpacing = (-6).sp, maxLines = 1, softWrap = false,
                )
                Box(Modifier.align(Alignment.CenterEnd).width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
                    RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize())
                    if (t.poster == null && t.backdrop == null) {
                        Text(t.name, Modifier.align(Alignment.Center).padding(8.dp), color = Xyd.Grey, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Continuer à regarder

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumeCard(p: Playable, prog: Progress, d: DownloadItem?, onClick: () -> Unit, onMenu: () -> Unit) {
    Column(Modifier.width(260.dp).combinedClickable(onClick = onClick, onLongClick = onMenu)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            RemoteImage(p.title.backdrop ?: p.title.poster, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
            Box(
                Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (d?.status == DlStatus.DONE) Icons.Filled.PlayArrow else XIcons.Download, null, tint = Xyd.Text)
            }
            if (prog.positionMs > 0) {
                LinearProgressIndicator(
                    progress = { prog.fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = Xyd.Grey, trackColor = Color(0x55FFFFFF), drawStopIndicator = {},
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(p.title.name, color = Xyd.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.episode != null) {
                    Text(
                        (if (prog.positionMs == 0L) "À suivre · " else "") + p.label,
                        color = Xyd.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onMenu, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.MoreVert, "Options", tint = Xyd.Muted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Menu d'un élément de « Continuer à regarder » (appui long ou ⋮). */
@Composable
private fun ResumeMenuSheet(p: Playable, d: DownloadItem?, onDismiss: () -> Unit, onPlay: () -> Unit, onInfo: () -> Unit) {
    XSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(p.title.backdrop ?: p.title.poster, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(p.title.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (p.episode != null) Text(p.label, color = Xyd.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MenuRow(if (d?.status == DlStatus.DONE) Icons.Filled.PlayArrow else XIcons.Download,
                if (d?.status == DlStatus.DONE) "Lire" else "Télécharger / voir", onPlay)
            MenuRow(Icons.Filled.Info, "Infos et épisodes", onInfo)
            MenuRow(Icons.Filled.Check, if (p.episode != null) "Marquer cet épisode comme vu" else "Marquer comme vu") {
                Repo.markWatched(p); onDismiss()
            }
            MenuRow(Icons.Filled.Close, "Retirer de « Continuer à regarder »") {
                Repo.hideFromContinue(p.title.id); onDismiss()
            }
            if (d != null) {
                MenuRow(Icons.Filled.Delete, "Supprimer le téléchargement", danger = true) {
                    Downloads.remove(p.mediaId); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) Xyd.Danger else Xyd.Grey, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = if (danger) Xyd.Danger else Xyd.Text, fontSize = 15.sp)
    }
}
