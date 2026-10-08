package tech.xydhub.xyd.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tech.xydhub.xyd.MainActivity
import tech.xydhub.xyd.R
import tech.xydhub.xyd.crypto.Vault
import tech.xydhub.xyd.data.Http
import tech.xydhub.xyd.data.Supabase
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import kotlin.coroutines.coroutineContext

/**
 * Service de téléchargement au premier plan : télécharge en HTTP Range (reprise possible),
 * chiffre à la volée en AES-CTR et écrit directement le fichier chiffré (jamais de clair sur disque).
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private lateinit var nm: NotificationManager
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification("Préparation…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_NOT_STICKY
    }

    private suspend fun loop() {
        acquireLocks()
        try {
            while (true) {
                val item = Downloads.nextQueued() ?: break
                Downloads.set(item.mediaId) { it.copy(status = DlStatus.RUNNING, error = null) }
                var attempt = 0
                while (true) {
                    try {
                        download(item)
                        break
                    } catch (e: StoppedException) {
                        break
                    } catch (e: Exception) {
                        coroutineContext.ensureActive()
                        if (Downloads.status(item.mediaId) != DlStatus.RUNNING) break
                        attempt++
                        if (attempt > RETRIES.size || e is FatalException) {
                            Downloads.set(item.mediaId) { it.copy(status = DlStatus.FAILED, error = message(e)) }
                            break
                        }
                        notify(item.label, "Connexion perdue, nouvelle tentative…", -1)
                        delay(RETRIES[attempt - 1])
                    }
                }
            }
        } finally {
            releaseLocks()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun download(item: DownloadItem) {
        val id = item.mediaId
        val part = Downloads.partFile(id)
        val raf = RandomAccessFile(part, "rw")
        raf.use { f ->
            // En-tête : réutilisé si reprise, sinon nouveau nonce.
            var have: Long
            val iv: ByteArray
            if (f.length() >= Vault.HEADER) {
                val h = ByteArray(Vault.HEADER).also { f.seek(0); f.readFully(it) }
                val existing = Vault.ivFrom(h)
                if (existing != null) {
                    iv = existing
                    have = f.length() - Vault.HEADER
                } else {
                    f.setLength(0)
                    val nh = Vault.newHeader(); f.write(nh); iv = Vault.ivFrom(nh)!!; have = 0
                }
            } else {
                f.setLength(0)
                val nh = Vault.newHeader(); f.write(nh); iv = Vault.ivFrom(nh)!!; have = 0
            }

            val url = try {
                Supabase.videoUrl(id)
            } catch (e: Http.ApiException) {
                if (e.code in 400..499) throw FatalException(e.message ?: "Accès refusé") else throw e
            }

            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                val code = c.responseCode
                when {
                    code == 416 && have > 0 -> { /* déjà complet */ }
                    code == 200 && have > 0 -> { // serveur sans Range : on recommence
                        have = 0
                        f.setLength(Vault.HEADER.toLong())
                    }
                    code == 200 || code == 206 -> {}
                    code in 400..499 -> throw FatalException("Vidéo indisponible ($code)")
                    else -> throw IOException("HTTP $code")
                }
                val remaining = if (code == 416) 0L else c.contentLengthLong
                val total = if (remaining >= 0) have + remaining else item.sizeBytes
                if (remaining > 0 && part.parentFile!!.usableSpace < remaining + 50L * 1024 * 1024) {
                    throw FatalException("Espace de stockage insuffisant")
                }
                Downloads.progress(id, have, total)

                if (code != 416) {
                    f.seek(Vault.HEADER + have)
                    val cipher = Vault.cipherAt(this, iv, have, Cipher.ENCRYPT_MODE)
                    val buf = ByteArray(256 * 1024)
                    val out = ByteArray(buf.size + 16)
                    var lastUi = 0L
                    c.inputStream.use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            val m = cipher.update(buf, 0, n, out, 0)
                            f.write(out, 0, m)
                            have += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi > 700) {
                                lastUi = now
                                if (Downloads.status(id) != DlStatus.RUNNING) throw StoppedException()
                                coroutineContext.ensureActive()
                                Downloads.progress(id, have, total)
                                val pct = if (total > 0) (have * 100 / total).toInt() else -1
                                notify(item.label, if (pct >= 0) "$pct %" else "", pct)
                            }
                        }
                    }
                }
                if (total > 0 && have < total) throw IOException("Téléchargement interrompu")
            } finally {
                c.disconnect()
            }
            Downloads.progress(id, have, have)
        }
        val target = Downloads.file(id)
        target.delete()
        if (!part.renameTo(target)) throw IOException("Écriture impossible")
        Downloads.set(id) { it.copy(status = DlStatus.DONE, downloaded = it.sizeBytes, error = null) }
    }

    private fun message(e: Exception) = when (e) {
        is FatalException -> e.message
        is java.net.UnknownHostException -> "Pas de connexion internet"
        else -> e.message
    } ?: "Erreur de téléchargement"

    private fun notification(text: String, pct: Int, indeterminate: Boolean, title: String = "xyd") =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_xyd)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, pct.coerceAtLeast(0), indeterminate)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    private fun notify(title: String, text: String, pct: Int) {
        nm.notify(NOTIF_ID, notification(text, pct, pct < 0, title))
    }

    private fun acquireLocks() {
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xyd:download").apply { acquire(3 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "xyd:download").apply { acquire() }
    }

    private fun releaseLocks() {
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        runCatching { wifi?.takeIf { it.isHeld }?.release() }
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private class StoppedException : Exception()
    private class FatalException(msg: String) : Exception(msg)

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIF_ID = 42
        private val RETRIES = longArrayOf(3_000, 10_000, 30_000, 60_000)
    }
}
