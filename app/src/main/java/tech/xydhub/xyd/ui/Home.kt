package tech.xydhub.xyd.ui

import android.app.Activity
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.xydhub.xyd.data.Playable
import tech.xydhub.xyd.data.Progress
import tech.xydhub.xyd.data.Repo
import tech.xydhub.xyd.data.Title
import tech.xydhub.xyd.download.DlStatus
import tech.xydhub.xyd.download.Downloads

/** Feuilles ouvrables depuis l'accueil. */
private const val SHEET_DOWNLOADS = "#downloads"
private const val SHEET_SETTINGS = "#settings"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onPlay: (String) -> Unit) {
    val act = LocalContext.current as Activity
    LaunchedEffect(Unit) { act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }

    val session by Repo.session.collectAsStateWithLifecycle()
    val catalog by Repo.catalog.collectAsStateWithLifecycle()
    val categories by Repo.categories.collectAsStateWithLifecycle()
    val progress by Repo.progress.collectAsStateWithLifecycle()
    val loading by Repo.loading.collectAsStateWithLifecycle()
    val error by Repo.error.collectAsStateWithLifecycle()
    val downloads by Downloads.items.collectAsStateWithLifecycle()

    // Valeur = id d'un titre, ou SHEET_DOWNLOADS / SHEET_SETTINGS.
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val download = rememberDownloader()

    val series = remember(catalog) { catalog.filter { !it.isFilm } }
    val films = remember(catalog) { catalog.filter { it.isFilm } }
    val resume = remember(catalog, progress) { Repo.continueWatching(catalog, progress) }
    // Rangées de l'admin : titres résolus dans l'ordre choisi, catégories vides masquées.
    val rows = remember(catalog, categories) {
        val byId = catalog.associateBy { it.id }
        categories.map { c -> c to c.titleIds.mapNotNull { byId[it] } }.filter { it.second.isNotEmpty() }
    }
    val active = downloads.values.count { it.status == DlStatus.RUNNING || it.status == DlStatus.QUEUED }

    PullToRefreshBox(isRefreshing = loading, onRefresh = { Repo.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = statusBarTop() + 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Logo(30.dp)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { sheet = SHEET_DOWNLOADS }) {
                        BadgedBox(badge = { if (active > 0) Badge(containerColor = Xyd.Grey) { Text("$active", color = Xyd.Black) } }) {
                            Icon(XIcons.Download, "Téléchargements", tint = Xyd.Grey)
                        }
                    }
                    IconButton(onClick = { sheet = SHEET_SETTINGS }) {
                        Icon(Icons.Filled.Settings, "Réglages", tint = Xyd.Grey)
                    }
                }
            }

            if (resume.isNotEmpty()) {
                item { SectionTitle("Continuer à regarder") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(resume, key = { it.first.mediaId }) { (p, prog) ->
                            ResumeCard(p, prog) {
                                if (Downloads.isReady(p.mediaId)) onPlay(p.mediaId) else sheet = p.title.id
                            }
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

            if (series.isNotEmpty()) {
                item { SectionTitle("Séries") }
                item { PosterRow(series) { sheet = it.id } }
            }
            if (films.isNotEmpty()) {
                item { SectionTitle("Films") }
                item { PosterRow(films) { sheet = it.id } }
            }

            if (catalog.isEmpty() && session != null) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 120.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Logo(64.dp)
                        Spacer(Modifier.height(20.dp))
                        Text(
                            when {
                                loading -> "Chargement…"
                                error != null -> error!!
                                else -> "Aucun contenu pour le moment"
                            },
                            color = Xyd.Muted,
                        )
                        if (error != null && !loading) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = { Repo.refresh() }) { Text("Réessayer", color = Xyd.Grey) }
                        }
                    }
                }
            }
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

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
        color = Xyd.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun PosterRow(titles: List<Title>, onClick: (Title) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(titles, key = { it.id }) { t ->
            Column(Modifier.width(118.dp).clickable { onClick(t) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
                    RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize())
                    if (t.poster == null && t.backdrop == null) {
                        Text(
                            t.name, Modifier.align(Alignment.Center).padding(8.dp),
                            color = Xyd.Grey, fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Text(
                    t.name, Modifier.padding(top = 6.dp), color = Xyd.Text, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ResumeCard(p: Playable, prog: Progress, onClick: () -> Unit) {
    Column(Modifier.width(260.dp).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            RemoteImage(p.title.backdrop ?: p.title.poster, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))
                )
            )
            Box(
                Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, null, tint = Xyd.Text) }
            LinearProgressIndicator(
                progress = { prog.fraction },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                color = Xyd.Grey, trackColor = Color(0x55FFFFFF), drawStopIndicator = {},
            )
        }
        Text(
            p.title.name, Modifier.padding(top = 6.dp), color = Xyd.Text, fontSize = 14.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (p.episode != null) {
            Text(p.label, color = Xyd.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                Box(
                    Modifier.align(Alignment.CenterEnd).width(110.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))
                ) {
                    RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize())
                    if (t.poster == null && t.backdrop == null) {
                        Text(t.name, Modifier.align(Alignment.Center).padding(8.dp), color = Xyd.Grey, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
