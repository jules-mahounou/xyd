@file:OptIn(UnstableApi::class)

package tech.xydhub.xyd.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.File

/**
 * ExoPlayer réduit au strict nécessaire :
 * - 2 renderers (vidéo + audio MediaCodec matériel), pas de texte/métadonnées/caméra/image ;
 * - 2 extracteurs (MP4 + MKV) au lieu des ~20 de DefaultExtractorsFactory ;
 * - source progressive sur fichier local chiffré.
 * R8 peut ainsi éliminer tout le reste de Media3.
 */
object Players {
    fun create(ctx: Context): ExoPlayer {
        val renderers = RenderersFactory { handler, video, audio, _, _ ->
            arrayOf(
                MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT, 5_000, handler, video, 50),
                MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, handler, audio),
            )
        }
        val extractors = ExtractorsFactory {
            arrayOf(
                Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED),
                MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED),
            )
        }
        val source = ProgressiveMediaSource.Factory(EncryptedFileDataSource.Factory(ctx), extractors)
        return ExoPlayer.Builder(ctx, renderers, source)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    fun item(file: File, mediaId: String): MediaItem =
        MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(mediaId).build()
}
