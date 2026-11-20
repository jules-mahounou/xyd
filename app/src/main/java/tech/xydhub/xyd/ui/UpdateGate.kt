package tech.xydhub.xyd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.launch
import tech.xydhub.xyd.BuildConfig
import tech.xydhub.xyd.data.Repo
import tech.xydhub.xyd.update.Updater

/**
 * Mise à jour : si la version installée est < min_version_code, la feuille est verrouillée
 * (impossible de la fermer ni d'utiliser l'app). Sinon elle est proposée une fois par version.
 */
@Composable
fun UpdateGate() {
    val v by Repo.update.collectAsStateWithLifecycle()
    val state by Updater.state.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableIntStateOf(0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Relu à chaque retour dans l'app (l'utilisateur revient des réglages « sources inconnues »).
    val life by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val canInstall = remember(life) { Updater.canInstall(ctx) }
    val info = v ?: return
    val forced = BuildConfig.VERSION_CODE < info.minVersionCode
    if (!forced && dismissed == info.versionCode) return

    // Taille de l'APK : fournie par la publication, sinon demandée au serveur (en-tête Content-Length).
    val size by androidx.compose.runtime.produceState(info.sizeBytes, info.apkUrl) {
        if (value <= 0L) value = Updater.remoteSize(info.apkUrl)
    }

    XSheet(onDismiss = { dismissed = info.versionCode }, locked = forced) {
        Box(Modifier.fillMaxWidth()) {
        // Fond : même traînée bleue animée que le modal de connexion, collée en bas.
        AuroraBand(Modifier.align(Alignment.BottomCenter), height = 150.dp)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Haut animé comme la connexion : carrousel diagonal du catalogue en fond,
            // et le visuel de la version (image, GIF ou vidéo) qui flotte au centre.
            if (info.imageUrl != null) {
                val float = androidx.compose.animation.core.rememberInfiniteTransition(label = "float")
                val t by float.animateFloat(
                    0f, (2 * Math.PI).toFloat(),
                    androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(5200, easing = androidx.compose.animation.core.LinearEasing)),
                    label = "float-t",
                )
                Box(Modifier.fillMaxWidth().padding(top = 0.dp), contentAlignment = Alignment.Center) {
                    CoverMarquee(height = 300.dp)
                    Box(
                        Modifier.fillMaxWidth(0.52f).aspectRatio(2f / 3f)
                            .graphicsLayer {
                                translationY = kotlin.math.sin(t) * 6.dp.toPx()
                                rotationZ = kotlin.math.sin(t + 1f) * 1.6f
                                shadowElevation = 24.dp.toPx()
                                shape = RoundedCornerShape(16.dp); clip = true
                            }
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp)),
                    ) {
                        AdMedia(
                            tech.xydhub.xyd.data.Announcement(
                                id = "v${info.versionCode}", title = "", description = "", image = info.imageUrl,
                                ratio = "portrait", link = null, mediaType = info.mediaType, video = info.videoUrl, showText = false,
                            ),
                            Modifier.fillMaxSize(),
                        )
                    }
                }
            } else {
                Logo(52.dp, Modifier.padding(top = 24.dp))
            }
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (forced) "Mise à jour obligatoire" else "Mise à jour disponible",
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                listOfNotNull(
                    "Version ${info.versionName.ifBlank { info.versionCode.toString() }}",
                    fmtSize(size).takeIf { it.isNotBlank() }?.let { "$it à télécharger" },
                ).joinToString(" · "),
                color = Xyd.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
            if (size in 1..5_000_000) {
                Text(
                    "Moins qu'une photo : la mise à jour est rapide, même en 3G.",
                    color = Xyd.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (info.changelog.isNotBlank()) {
                Text(
                    info.changelog, color = Xyd.Grey, fontSize = 14.sp, lineHeight = 20.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (forced) {
                Text(
                    "Installe cette version pour continuer à utiliser xyd.",
                    color = Xyd.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(24.dp))

            val s = state
            if (s is Updater.State.Error) {
                Text(s.message, color = Xyd.Danger, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            if (!canInstall && s !is Updater.State.Downloading) {
                Text(
                    "Autorise d'abord xyd à installer des applications (une seule fois).",
                    color = Xyd.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            // Bouton « Surge » : le liquide suit le téléchargement de la mise à jour.
            SurgeButton(
                label = when {
                    s is Updater.State.Downloading -> "Téléchargement · ${(s.fraction * 100).toInt()} %"
                    !canInstall -> "Autoriser l'installation"
                    s is Updater.State.Ready -> "Installer"
                    else -> "Mettre à jour" + (fmtSize(size).takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                },
                onClick = {
                    when {
                        s is Updater.State.Downloading -> {}
                        !canInstall -> Updater.openInstallPermission(ctx)
                        s is Updater.State.Ready -> Updater.install(ctx)
                        else -> scope.launch { Updater.download(ctx, info.apkUrl) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                height = 52.dp,
                fill = when (s) { is Updater.State.Downloading -> s.fraction; is Updater.State.Ready -> 1f; else -> 0f },
                active = s is Updater.State.Downloading,
                moving = s is Updater.State.Downloading,
            )
            if (!forced && s !is Updater.State.Downloading) {
                TextButton(onClick = { dismissed = info.versionCode }) { Text("Plus tard", color = Xyd.Muted) }
            }
        }
            }
        }
    }
}
