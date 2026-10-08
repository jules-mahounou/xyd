package tech.xydhub.xyd.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Télécharge l'APK de mise à jour et lance l'installeur système. */
object Updater {
    sealed interface State {
        data object Idle : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data class Error(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    private fun apkFile(ctx: Context) = File(File(ctx.cacheDir, "update").apply { mkdirs() }, "xyd.apk")

    suspend fun download(ctx: Context, url: String) {
        if (state.value is State.Downloading) return
        state.value = State.Downloading(0f)
        try {
            withContext(Dispatchers.IO) {
                val out = apkFile(ctx)
                val tmp = File(out.path + ".tmp")
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 20_000
                    c.readTimeout = 30_000
                    // Évite un APK périmé servi par un cache intermédiaire.
                    c.setRequestProperty("Cache-Control", "no-cache")
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    val total = c.contentLengthLong
                    var read = 0L
                    c.inputStream.use { input ->
                        tmp.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                read += n
                                if (total > 0) state.value = State.Downloading(read.toFloat() / total)
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                }
                out.delete()
                if (!tmp.renameTo(out)) throw IOException("Écriture impossible")
            }
            state.value = State.Ready
            install(ctx)
        } catch (e: Exception) {
            state.value = State.Error(e.message ?: "Échec du téléchargement")
        }
    }

    /** true si Android autorise déjà xyd à installer des applications. */
    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(ctx: Context) {
        val f = apkFile(ctx)
        if (!f.exists()) return
        if (!canInstall(ctx)) {
            openInstallPermission(ctx)
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Nettoie l'APK téléchargé une fois la mise à jour installée. */
    fun cleanup(ctx: Context) {
        if (state.value !is State.Downloading) File(ctx.cacheDir, "update").deleteRecursively()
    }
}
