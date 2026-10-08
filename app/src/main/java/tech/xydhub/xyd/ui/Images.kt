package tech.xydhub.xyd.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Chargeur d'images maison (remplace Coil/Glide) : cache mémoire LRU + cache disque,
 * décodage sous-échantillonné à la taille d'affichage. ~100 lignes, 0 dépendance.
 */
object Images {
    private val memory = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)

    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun cached(url: String, w: Int) = memory.get("$url@$w")

    suspend fun load(ctx: Context, url: String, targetW: Int): ImageBitmap? {
        val key = "$url@$targetW"
        memory.get(key)?.let { return it }
        return withContext(io) {
            runCatching {
                val dir = File(ctx.cacheDir, "img").apply { mkdirs() }
                val f = File(dir, hash(url))
                if (!f.exists() || f.length() == 0L) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 15_000
                        c.readTimeout = 20_000
                        if (c.responseCode !in 200..299) return@runCatching null
                        val tmp = File(dir, f.name + ".tmp")
                        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                        tmp.renameTo(f)
                    } finally {
                        c.disconnect()
                    }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                var sample = 1
                while (targetW > 0 && bounds.outWidth / (sample * 2) >= targetW) sample *= 2
                val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@runCatching null
                bmp.asImageBitmap().also { memory.put(key, it) }
            }.getOrNull()
        }
    }

    /** Purge le cache disque s'il dépasse ~60 Mo (appelé au démarrage). */
    fun trim(ctx: Context, maxBytes: Long = 60L * 1024 * 1024) {
        val files = File(ctx.cacheDir, "img").listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= maxBytes * 3 / 4) break
        }
    }
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    BoxWithConstraints(modifier.background(Xyd.Card)) {
        if (url.isNullOrBlank()) return@BoxWithConstraints
        val ctx = LocalContext.current
        val w = constraints.maxWidth.takeIf { it in 1..4096 } ?: 600
        val img by produceState(Images.cached(url, w), url, w) {
            if (value == null) value = Images.load(ctx, url, w)
        }
        img?.let {
            Image(it, null, Modifier.matchParentSize(), contentScale = contentScale)
        } ?: Box(Modifier.matchParentSize())
    }
}
