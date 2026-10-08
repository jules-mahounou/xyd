package tech.xydhub.xyd.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.xydhub.xyd.R
import tech.xydhub.xyd.data.Playable
import tech.xydhub.xyd.download.DlStatus
import tech.xydhub.xyd.download.DownloadItem
import tech.xydhub.xyd.download.Downloads
import java.util.Locale

fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1024.0 * 1024 * 1024))
    else -> "${bytes / (1024 * 1024)} Mo"
}

fun fmtDuration(s: Int): String = when {
    s <= 0 -> ""
    s >= 3600 -> "${s / 3600} h ${"%02d".format((s % 3600) / 60)}"
    else -> "${(s + 59) / 60} min"
}

fun fmtClock(ms: Long): String {
    val t = (ms.coerceAtLeast(0) / 1000).toInt()
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
fun Logo(size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_logo), "xyd", modifier.size(size))
}

/** Lance un téléchargement, en demandant au passage la permission de notification (Android 13+). */
@Composable
fun rememberDownloader(): (Playable) -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(ctx) {
        { p: Playable ->
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            Downloads.enqueue(ctx, p)
        }
    }
}

/** Action principale d'un élément selon son état : télécharger → progression (pause) → lire. */
fun primaryAction(
    p: Playable,
    d: DownloadItem?,
    onPlay: (String) -> Unit,
    download: (Playable) -> Unit,
) {
    when (d?.status) {
        DlStatus.DONE -> if (Downloads.isReady(p.mediaId)) onPlay(p.mediaId) else download(p)
        DlStatus.RUNNING, DlStatus.QUEUED -> Downloads.pause(p.mediaId)
        else -> download(p)
    }
}

@Composable
fun DownloadButton(p: Playable, onPlay: (String) -> Unit, download: (Playable) -> Unit) {
    val items by Downloads.items.collectAsStateWithLifecycle()
    val d = items[p.mediaId]
    IconButton(onClick = { primaryAction(p, d, onPlay, download) }) {
        when (d?.status) {
            DlStatus.DONE -> Box(
                Modifier.size(34.dp).clip(CircleShape).background(Xyd.Grey),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, "Lire", tint = Xyd.Black) }

            DlStatus.RUNNING, DlStatus.QUEUED -> Box(contentAlignment = Alignment.Center) {
                if (d!!.status == DlStatus.QUEUED || d.fraction == 0f) {
                    CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.5.dp, color = Xyd.Grey, trackColor = Xyd.Line)
                } else {
                    val f = d.fraction
                    CircularProgressIndicator(
                        progress = { f }, modifier = Modifier.size(30.dp), strokeWidth = 2.5.dp,
                        color = Xyd.Grey, trackColor = Xyd.Line,
                    )
                }
                Icon(XIcons.Pause, "Pause", Modifier.size(14.dp), tint = Xyd.Grey)
            }

            DlStatus.FAILED -> Icon(Icons.Filled.Refresh, "Réessayer", tint = Xyd.Danger)
            DlStatus.PAUSED -> Box(contentAlignment = Alignment.Center) {
                val f = d!!.fraction
                CircularProgressIndicator(
                    progress = { f }, modifier = Modifier.size(30.dp), strokeWidth = 2.5.dp,
                    color = Xyd.Muted, trackColor = Xyd.Line,
                )
                Icon(XIcons.Download, "Reprendre", Modifier.size(16.dp), tint = Xyd.Grey)
            }

            null -> Icon(XIcons.Download, "Télécharger", tint = Xyd.Grey)
        }
    }
}
