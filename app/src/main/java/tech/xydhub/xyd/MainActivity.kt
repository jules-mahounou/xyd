package tech.xydhub.xyd

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tech.xydhub.xyd.data.Repo
import tech.xydhub.xyd.download.Downloads
import tech.xydhub.xyd.ui.HomeScreen
import tech.xydhub.xyd.ui.Images
import tech.xydhub.xyd.ui.PlayerScreen
import tech.xydhub.xyd.ui.UpdateGate
import tech.xydhub.xyd.ui.Xyd
import tech.xydhub.xyd.ui.XydTheme
import tech.xydhub.xyd.update.Updater

/** Une seule activité, deux écrans (Accueil / Lecteur), tout le reste en bottom sheets. */
class MainActivity : ComponentActivity() {
    private var watcher: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Images.trim(this)
        setContent {
            XydTheme {
                var playing by rememberSaveable { mutableStateOf<String?>(null) }
                // Surface : couleur de texte par défaut = blanc partout (sinon noir sur noir hors des sheets).
                Surface(Modifier.fillMaxSize(), color = Xyd.Black, contentColor = Xyd.Text) {
                Box(Modifier.fillMaxSize()) {
                    val current = playing
                    if (current == null) {
                        HomeScreen(onPlay = { playing = it })
                    } else {
                        BackHandler { playing = null }
                        PlayerScreen(
                            mediaId = current,
                            onPlay = { playing = it },
                            onClose = { playing = null },
                        )
                    }
                    UpdateGate()
                }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Repo.refresh()
        Downloads.resumePending(this)
        // Écoute des mises à jour : au retour au premier plan puis toutes les 10 min tant que l'app est visible.
        watcher?.cancel()
        watcher = lifecycleScope.launch {
            while (isActive) {
                Repo.checkUpdate()
                if (Repo.update.value == null) Updater.cleanup(this@MainActivity)
                delay(10 * 60 * 1000L)
            }
        }
    }

    override fun onStop() {
        watcher?.cancel()
        Repo.syncProgressLater()
        super.onStop()
    }
}
