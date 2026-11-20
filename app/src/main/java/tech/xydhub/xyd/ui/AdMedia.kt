@file:OptIn(UnstableApi::class)

package tech.xydhub.xyd.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.view.TextureView
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
        RemoteImage(ad.image, Modifier.fillMaxSize(), background = Color.Transparent)
        when {
            ad.mediaType == "video" && ad.video != null -> AdVideo(ad.video, Modifier.fillMaxSize())
            ad.mediaType == "gif" && ad.image != null && Build.VERSION.SDK_INT >= 28 -> AdGif(ad.image, Modifier.fillMaxSize())
        }
        if (ad.hasOverlay) AdOverlay(ad)
    }
}

/**
 * Titre, description et bouton par-dessus le média (seulement ce qui est rempli).
 * Tailles proportionnelles à la carte, pour rester lisibles du bandeau fin à l'affiche.
 */
@Composable
private fun AdOverlay(ad: Announcement) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val light = ad.textTheme != "dark"
        val ink = if (light) Color.White else Color(0xFF1B1408)
        val tall = ad.ratio == "portrait" || ad.ratio == "square"
        val unit = minOf(maxHeight / (if (tall) 9f else 4.2f), maxWidth / 12f) // base typographique
        val hAlign = when (ad.textPosition) { "center" -> Alignment.CenterHorizontally; "right" -> Alignment.End; else -> Alignment.Start }
        val textAlign = when (ad.textPosition) { "center" -> TextAlign.Center; "right" -> TextAlign.End; else -> TextAlign.Start }
        if (light) {
            // Voile sombre du côté du texte (en bas pour les formats hauts) : lisible sur n'importe quel visuel.
            Box(
                Modifier.fillMaxSize().background(
                    if (tall) Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xC8000000))
                    else when (ad.textPosition) {
                        "right" -> Brush.horizontalGradient(0.35f to Color.Transparent, 1f to Color(0xB0000000))
                        "center" -> Brush.verticalGradient(0f to Color(0x30000000), 1f to Color(0x90000000))
                        else -> Brush.horizontalGradient(0f to Color(0xB0000000), 0.65f to Color.Transparent)
                    },
                ),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = unit * 1.1f, vertical = unit * 0.8f)
                .fillMaxWidth(),
            horizontalAlignment = hAlign,
            verticalArrangement = if (tall) Arrangement.Bottom else Arrangement.Center,
        ) {
            val sp = with(LocalDensity.current) { unit.toSp() }
            Column(Modifier.fillMaxWidth(if (tall || ad.textPosition == "center") 1f else 0.66f), horizontalAlignment = hAlign) {
                if (ad.title.isNotBlank()) Text(
                    ad.title, color = ink, fontWeight = FontWeight.Bold, textAlign = textAlign,
                    fontSize = sp * 1.15f, lineHeight = sp * 1.3f, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (ad.description.isNotBlank()) Text(
                    ad.description, color = ink.copy(alpha = 0.82f), textAlign = textAlign,
                    fontSize = sp * 0.82f, lineHeight = sp * 1.05f, maxLines = if (tall) 3 else 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = unit * 0.2f),
                )
                if (ad.cta.isNotBlank()) Text(
                    ad.cta,
                    Modifier.padding(top = unit * 0.55f).clip(RoundedCornerShape(50))
                        .background(if (light) Color.White else Color(0xFF1B1408))
                        .padding(horizontal = unit * 0.9f, vertical = unit * 0.35f),
                    color = if (light) Color(0xFF111111) else Color(0xFFFFF4DC),
                    fontWeight = FontWeight.SemiBold, fontSize = sp * 0.85f, maxLines = 1,
                )
            }
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
