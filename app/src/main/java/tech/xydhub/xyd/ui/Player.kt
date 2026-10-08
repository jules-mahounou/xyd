package tech.xydhub.xyd.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay
import tech.xydhub.xyd.data.OrientationMode
import tech.xydhub.xyd.data.Playable
import tech.xydhub.xyd.data.Repo
import tech.xydhub.xyd.download.Downloads
import tech.xydhub.xyd.player.Players

@Composable
fun PlayerScreen(mediaId: String, onPlay: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val act = ctx as Activity
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val mode by Repo.orientation.collectAsStateWithLifecycle()
    val catalog by Repo.catalog.collectAsStateWithLifecycle()
    val playable = remember(mediaId, catalog) { Repo.playable(mediaId) }
    val next = remember(playable) { playable?.let { Repo.next(it) } }
    val download = rememberDownloader()

    // ---- Orientation choisie par l'utilisateur
    LaunchedEffect(mode) {
        act.requestedOrientation = when (mode) {
            OrientationMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // ---- Plein écran immersif en paysage
    DisposableEffect(landscape) {
        val c = WindowCompat.getInsetsController(act.window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (landscape) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        onDispose { c.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // ---- Lecteur
    val player = remember { Players.create(ctx) }
    var isPlaying by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(Player.STATE_IDLE) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var ended by remember { mutableStateOf(false) }

    // L'id est lu sur le MediaItem courant (et non capturé) pour rester juste lors d'un changement d'épisode.
    fun save() {
        val id = player.currentMediaItem?.mediaId ?: return
        val d = player.duration
        if (d > 0) Repo.saveProgress(id, if (player.playbackState == Player.STATE_ENDED) d else player.currentPosition, d)
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) {
                isPlaying = p
                view.keepScreenOn = p
                if (!p) save()
            }
            override fun onPlaybackStateChanged(s: Int) {
                state = s
                if (s == Player.STATE_READY) duration = player.duration.coerceAtLeast(0)
                if (s == Player.STATE_ENDED) {
                    save()
                    ended = true
                }
            }
            override fun onVideoSizeChanged(v: VideoSize) {
                if (v.width > 0 && v.height > 0) aspect = v.width * v.pixelWidthHeightRatio / v.height
            }
            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                        "Format vidéo non supporté par ce téléphone."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                        "Fichier vidéo illisible. Supprime-le et télécharge-le à nouveau."
                    else -> "Lecture impossible (${e.errorCodeName})"
                }
            }
        }
        player.addListener(l)
        onDispose {
            save()
            Repo.syncProgressLater()
            player.removeListener(l)
            player.release()
            view.keepScreenOn = false
        }
    }

    // Pause quand l'app passe en arrière-plan.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // Chargement du média (et changement d'épisode) avec reprise de la position.
    LaunchedEffect(mediaId) {
        save()
        error = null
        ended = false
        if (!Downloads.isReady(mediaId)) {
            error = "Cette vidéo n'est pas encore téléchargée."
            return@LaunchedEffect
        }
        val resume = Repo.progress.value[mediaId]?.takeIf { !it.finished }?.positionMs ?: 0L
        player.setMediaItem(Players.item(Downloads.file(mediaId), mediaId), (resume - 3_000).coerceAtLeast(0))
        player.prepare()
        player.play()
    }

    // Sauvegarde périodique + horloge de l'UI.
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            position = player.currentPosition
            if (player.duration > 0) duration = player.duration
            if (++tick % 10 == 0 && player.isPlaying) save()
            delay(500)
        }
    }

    // Enchaînement automatique sur l'épisode suivant s'il est déjà téléchargé.
    LaunchedEffect(ended) {
        if (ended && next != null && Downloads.isReady(next.mediaId)) {
            delay(1_500)
            onPlay(next.mediaId)
        }
    }

    // ---- Interface
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableLongStateOf(0L) }
    LaunchedEffect(controls, isPlaying, interaction) {
        if (controls && isPlaying) {
            delay(3_500)
            controls = false
        }
    }
    val poke = { interaction = System.nanoTime() }

    val videoBox: @Composable (Modifier) -> Unit = { m ->
        Box(
            m.background(Color.Black).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            ) { controls = !controls; poke() },
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { SurfaceView(it).also { sv -> player.setVideoSurfaceView(sv) } },
                modifier = Modifier.aspectRatio(aspect),
            )
            if (state == Player.STATE_BUFFERING) {
                CircularProgressIndicator(color = Xyd.Grey, strokeWidth = 2.dp)
            }
            AnimatedVisibility(controls || !isPlaying || error != null, Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
                Controls(
                    playable = playable, landscape = landscape, isPlaying = isPlaying,
                    position = position, duration = duration, hasNext = next != null, error = error, mode = mode,
                    onBack = onClose,
                    onToggle = { if (player.isPlaying) player.pause() else { if (ended) player.seekTo(0); ended = false; player.play() }; poke() },
                    onSeek = { player.seekTo(it); position = it; poke() },
                    onRewind = { player.seekBack(); poke() },
                    onForward = { player.seekForward(); poke() },
                    onNext = { next?.let { n -> if (Downloads.isReady(n.mediaId)) onPlay(n.mediaId) else download(n) } },
                    onRotate = {
                        Repo.setOrientation(
                            when (mode) {
                                OrientationMode.AUTO -> if (landscape) OrientationMode.PORTRAIT else OrientationMode.LANDSCAPE
                                OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
                                OrientationMode.PORTRAIT -> OrientationMode.LANDSCAPE
                            }
                        )
                        poke()
                    },
                )
            }
        }
    }

    if (landscape) {
        videoBox(Modifier.fillMaxSize())
    } else {
        Column(Modifier.fillMaxSize().background(Xyd.Black)) {
            Spacer(Modifier.fillMaxWidth().statusBarsPadding())
            videoBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            PortraitDetails(playable, next, mediaId, onPlay, download)
        }
    }
}

@Composable
private fun Controls(
    playable: Playable?,
    landscape: Boolean,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    hasNext: Boolean,
    error: String?,
    mode: OrientationMode,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onRotate: () -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Box(
        Modifier.fillMaxSize().background(Color(0x80000000))
            .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
    ) {
        // Haut : retour + titre
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) }
            if (landscape && playable != null) {
                Column(Modifier.weight(1f)) {
                    Text(playable.title.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (playable.episode != null) Text(playable.label, color = Xyd.Grey, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = onRotate) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Icon(XIcons.Rotate, "Orientation", tint = Color.White)
                    if (mode != OrientationMode.AUTO) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Xyd.Grey))
                    }
                }
            }
        }

        if (error != null) {
            Text(
                error, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp),
                fontSize = 14.sp,
            )
            return@Box
        }

        // Centre : -10 / lecture / +10
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(if (landscape) 56.dp else 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeekButton(XIcons.Rewind, "Reculer de 10 s", onRewind)
            Box(
                Modifier.size(if (landscape) 72.dp else 60.dp).clip(CircleShape).background(Color(0x33FFFFFF))
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isPlaying) XIcons.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause" else "Lecture", tint = Color.White,
                    modifier = Modifier.size(if (landscape) 40.dp else 34.dp),
                )
            }
            SeekButton(XIcons.Forward, "Avancer de 10 s", onForward)
        }

        // Bas : barre de progression + temps + suivant
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = if (landscape) 8.dp else 0.dp)) {
            val dur = duration.coerceAtLeast(1)
            val shown = dragging?.let { (it * dur).toLong() } ?: position
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${fmtClock(shown)} / ${fmtClock(duration)}", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 4.dp))
                if (hasNext) {
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onNext).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Suivant", color = Color.White, fontSize = 12.sp)
                        Icon(XIcons.SkipNext, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Slider(
                value = dragging ?: (position.toFloat() / dur).coerceIn(0f, 1f),
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { onSeek((it * dur).toLong()) }; dragging = null },
                colors = SliderDefaults.colors(
                    thumbColor = Xyd.Grey, activeTrackColor = Xyd.Grey, inactiveTrackColor = Color(0x55FFFFFF),
                ),
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
        }
    }
}

@Composable
private fun SeekButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(34.dp))
        Text("10", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun PortraitDetails(
    playable: Playable?,
    next: Playable?,
    mediaId: String,
    onPlay: (String) -> Unit,
    download: (Playable) -> Unit,
) {
    val progress by Repo.progress.collectAsStateWithLifecycle()
    val downloads by Downloads.items.collectAsStateWithLifecycle()
    if (playable == null) return
    val season = playable.season
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.padding(20.dp)) {
                Text(playable.title.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (playable.episode != null) {
                    Text(playable.label, color = Xyd.Grey, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))
                }
                val synopsis = playable.episode?.synopsis?.ifBlank { null } ?: playable.title.synopsis
                if (synopsis.isNotBlank()) {
                    Text(synopsis, color = Xyd.Muted, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 12.dp))
                }
                if (next != null) {
                    Spacer(Modifier.height(16.dp))
                    val ready = downloads[next.mediaId]?.let { Downloads.isReady(next.mediaId) } == true
                    Button(
                        onClick = { if (ready) onPlay(next.mediaId) else download(next) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Xyd.Card, contentColor = Xyd.Text),
                    ) {
                        Icon(if (ready) XIcons.SkipNext else XIcons.Download, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (if (ready) "Épisode suivant · " else "Télécharger la suite · ") + next.shortLabel,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (season != null && season.episodes.size > 1) {
            item {
                Text(
                    season.name.ifBlank { "Saison ${season.number}" },
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
                )
            }
            items(season.episodes, key = { it.id }) { e ->
                val p = Playable(playable.title, season, e)
                val current = e.id == mediaId
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (current) Xyd.Card else Color.Transparent)
                        .clickable(enabled = !current) {
                            if (Downloads.isReady(e.id)) onPlay(e.id) else download(p)
                        }
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${e.number}", color = if (current) Xyd.Text else Xyd.Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(32.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            e.name.ifBlank { "Épisode ${e.number}" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(fmtDuration(e.durationS), color = Xyd.Muted, fontSize = 12.sp)
                        val w = progress[e.id]?.fraction ?: 0f
                        if (w > 0f) {
                            LinearProgressIndicator(
                                progress = { w }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth(0.6f).height(2.dp),
                                color = Xyd.Grey, trackColor = Xyd.Line, drawStopIndicator = {},
                            )
                        }
                    }
                    if (current) {
                        Text("En cours", color = Xyd.Grey, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                    } else {
                        DownloadButton(p, onPlay, download)
                    }
                }
            }
        }
    }
}

