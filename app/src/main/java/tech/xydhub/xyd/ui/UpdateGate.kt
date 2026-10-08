package tech.xydhub.xyd.ui

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

    XSheet(onDismiss = { dismissed = info.versionCode }, locked = forced) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Logo(52.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                if (forced) "Mise à jour obligatoire" else "Mise à jour disponible",
                fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Version ${info.versionName.ifBlank { info.versionCode.toString() }}",
                color = Xyd.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
            )
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

            when (val s = state) {
                is Updater.State.Downloading -> {
                    LinearProgressIndicator(
                        progress = { s.fraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = Xyd.Grey, trackColor = Xyd.Line, drawStopIndicator = {},
                    )
                    Text("${(s.fraction * 100).toInt()} %", color = Xyd.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
                else -> {
                    if (s is Updater.State.Error) {
                        Text(s.message, color = Xyd.Danger, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                    }
                    if (!canInstall) {
                        Text(
                            "Autorise d'abord xyd à installer des applications (une seule fois).",
                            color = Xyd.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                    Button(
                        onClick = {
                            when {
                                !canInstall -> Updater.openInstallPermission(ctx)
                                s is Updater.State.Ready -> Updater.install(ctx)
                                else -> scope.launch { Updater.download(ctx, info.apkUrl) }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Xyd.Grey, contentColor = Xyd.Black),
                    ) {
                        Text(
                            when {
                                !canInstall -> "Autoriser l'installation"
                                s is Updater.State.Ready -> "Installer"
                                else -> "Mettre à jour"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (!forced) {
                        TextButton(onClick = { dismissed = info.versionCode }) { Text("Plus tard", color = Xyd.Muted) }
                    }
                }
            }
        }
    }
}
