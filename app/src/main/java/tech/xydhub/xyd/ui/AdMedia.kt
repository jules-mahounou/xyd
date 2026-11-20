@file:OptIn(UnstableApi::class)

package tech.xydhub.xyd.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.view.TextureView
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.xydhub.xyd.data.Announcement

/**
 * Média d'une annonce, rempli et rogné à la taille donnée :
 * - image : affichée telle quelle ;
 * - GIF : animé (Android 9+, image fixe avant) ;
 * - vidéo MP4 : jouée en boucle, sans son, par-dessus sa vignette (affichée pendant le chargement).
 * Le fichier est téléchargé une fois puis lu depuis le cache.
 */
@Composable
fun AdMedia(ad: Announcement, modifier: Modifier = Modifier) {
    Box(modifier) {
        // Image, ou vignette / première image en attendant l'animation.
        RemoteImage(ad.image, Modifier.fillMaxSize())
        when {
            ad.mediaType == "video" && ad.video != null -> AdVideo(ad.video, Modifier.fillMaxSize())
            ad.mediaType == "gif" && ad.image != null && Build.VERSION.SDK_INT >= 28 -> AdGif(ad.image, Modifier.fillMaxSize())
        }
    }
}

@androidx.annotation.RequiresApi(28)
@Composable
private fun AdGif(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val drawable by produceState<android.graphics.drawable.Drawable?>(null, url) {
        val f = Images.file(ctx, url) ?: return@produceState
        value = withContext(Dispatchers.IO) { runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) }.getOrNull() }
    }
    val d = drawable ?: return
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { v ->
            if (v.drawable !== d) {
                v.setImageDrawable(d)
                (d as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun AdVideo(url: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val file by produceState<java.io.File?>(null, url) { value = Images.file(ctx, url) }
    val f = file ?: return
    // Lecteur minimal : rendu vidéo seul (muet), extracteur MP4, lecture du fichier en cache, en boucle.
    val player = remember(f) {
        val renderers = RenderersFactory { handler, video, _, _, _ ->
            arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT, 5_000, handler, video, 50))
        }
        val extractors = ExtractorsFactory { arrayOf(Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED)) }
        ExoPlayer.Builder(ctx, renderers, ProgressiveMediaSource.Factory(FileDataSource.Factory(), extractors)).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = {
            TextureView(it).also { tv ->
                player.setVideoTextureView(tv)
                // Remplit la zone sans déformer (recadrage centré), même si le format de la vidéo diffère.
                val crop = { vw: Int, vh: Int ->
                    if (vw > 0 && vh > 0 && tv.width > 0 && tv.height > 0) {
                        val s = maxOf(tv.width / vw.toFloat(), tv.height / vh.toFloat())
                        tv.setTransform(android.graphics.Matrix().apply {
                            setScale(vw * s / tv.width, vh * s / tv.height, tv.width / 2f, tv.height / 2f)
                        })
                    }
                }
                player.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        tv.post { crop(videoSize.width, videoSize.height) }
                    }
                })
                tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> crop(player.videoSize.width, player.videoSize.height) }
            }
        },
        modifier = modifier,
    )
}
