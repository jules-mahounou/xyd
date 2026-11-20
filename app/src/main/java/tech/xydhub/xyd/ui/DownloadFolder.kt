package tech.xydhub.xyd.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Dossier en verre des téléchargements (« Séries » / « Films »).
 *
 * Trois couches, du fond vers l'avant : le dos du dossier (image du dernier
 * titre téléchargé), les « fiches » (images des derniers titres) et la face
 * avant en verre fumé, articulée sur son bord bas.
 *
 * - Repos : fermé, le haut des fiches dépasse à peine.
 * - Sélectionné (toucher) : la face bascule vers l'avant, les fiches sortent et
 *   s'écartent en éventail. Sert aussi de filtre de la liste en dessous.
 * - Entrée : le dossier s'assemble une fois à l'ouverture du modal.
 *
 * Géométrie dans les unités du dossier (340 × 272, + 54 de marge au-dessus pour
 * que les fiches puissent sortir), mise à l'échelle de la largeur disponible.
 */

private const val U_W = 340f
private const val U_HEAD = 54f
private const val U_H = 272f
private const val U_FRONT_TOP = 74f
private const val U_FRONT_H = 198f
private const val U_SHEET_W = 272f
private const val U_SHEET_H = 184f

private const val BACK_D =
    "M0 56A22 22 0 0 1 22 34L146 34C170 34 172 0 198 0L318 0A22 22 0 0 1 340 22L340 250A22 22 0 0 1 318 272L22 272A22 22 0 0 1 0 250Z"
private const val FRONT_D =
    "M0 22A22 22 0 0 1 22 0L118 0C144 0 146 32 172 32L318 32A22 22 0 0 1 340 54L340 176A22 22 0 0 1 318 198L22 198A22 22 0 0 1 0 176Z"

private val BACK_PATH: Path by lazy { PathParser().parsePathString(BACK_D).toPath() }
private val FRONT_PATH: Path by lazy { PathParser().parsePathString(FRONT_D).toPath() }

private fun scaled(base: Path, sx: Float, sy: Float): Path =
    Path().apply { addPath(base); transform(Matrix().apply { scale(sx, sy) }) }

/** Forme issue d'un tracé SVG, étirée à la taille du composant. */
private class SvgShape(private val base: () -> Path, private val uw: Float, private val uh: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(scaled(base(), size.width / uw, size.height / uh))
}

private val BackShape = SvgShape({ BACK_PATH }, U_W, U_H)
private val FrontShape = SvgShape({ FRONT_PATH }, U_W, U_FRONT_H)

/** Forme « face avant de dossier » (onglet en haut), réutilisée par les cartes accordéon. */
internal val FolderGlassShape: Shape get() = FrontShape
internal val FolderGlassPath: Path get() = FRONT_PATH
internal const val FOLDER_GLASS_W = U_W
internal const val FOLDER_GLASS_H = U_FRONT_H
internal fun scaledFolderPath(sx: Float, sy: Float): Path = scaled(FRONT_PATH, sx, sy)

/** Même bleu que la traînée de l'accueil. */
private val Accent = Color(0xFF4C6BFF)

@Composable
fun DownloadFolder(
    label: String,
    meta: String,
    running: Int,
    /** Images des derniers titres, du plus récent au plus ancien (backdrop de préférence). */
    images: List<String?>,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    appearDelay: Long = 0,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(appearDelay); shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(750, easing = FastOutSlowInEasing), label = "appear")
    val open by animateFloatAsState(if (selected) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 240f), label = "open")

    val cover = images.firstOrNull()
    // Le plus récent est devant (contre le verre) : on dessine du plus ancien au plus récent.
    val stack = images.take(3).reversed()

    BoxWithConstraints(
        modifier
            .aspectRatio(U_W / (U_H + U_HEAD))
            .clipToBounds()
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        val k = constraints.maxWidth / U_W // pixels par unité
        val dens = LocalDensity.current
        val u = { v: Float -> with(dens) { (v * k).toDp() } }

        // ---- dos du dossier + son image
        Box(
            Modifier.size(u(U_W), u(U_H))
                .graphicsLayer { translationY = (U_HEAD + (1f - appear) * 22f) * k; alpha = appear }
                .clip(BackShape)
                .background(Brush.verticalGradient(listOf(Color(0xFF2A2A2E), Color(0xFF121214)))),
        ) {
            if (cover != null) RemoteImage(cover, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x22000000), Color(0x88000000)))))
        }
        Canvas(Modifier.size(u(U_W), u(U_H)).graphicsLayer { translationY = (U_HEAD + (1f - appear) * 22f) * k; alpha = appear }) {
            drawPath(scaled(BACK_PATH, k, k), Color.White.copy(alpha = 0.14f), style = Stroke(1.dp.toPx()))
        }

        // ---- fiches : au repos elles dépassent à peine, ouvertes elles sortent en éventail
        val n = stack.size
        stack.forEachIndexed { v, img ->
            val depth = n - 1 - v
            val slot = if (n <= 1) 0f else v / (n - 1f) * 2f - 1f
            val jitterX = ((v * 37 % 7) - 3) * 1.2f
            val jitterR = ((v * 53 % 5) - 2) * 0.6f
            val lift = lerp(8f, -74f, open) - depth * lerp(14f, 26f, open)
            val spread = lerp(0f, 34f, open)
            val fan = lerp(0f, 6f, open)
            Box(
                Modifier.size(u(U_SHEET_W), u(U_SHEET_H))
                    .graphicsLayer {
                        translationX = (34f + jitterX + slot * spread) * k
                        translationY = (U_HEAD + 96f + lift - (1f - appear) * 120f) * k
                        rotationZ = jitterR + slot * fan
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        alpha = appear
                    }
                    .clip(RoundedCornerShape(u(16f)))
                    .background(Color(0xFF1C1C1F)),
            ) {
                RemoteImage(img, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x66000000), 0.35f to Color.Transparent)))
            }
        }

        // ---- face avant en verre fumé, articulée en bas
        Box(
            Modifier.size(u(U_W), u(U_FRONT_H))
                .graphicsLayer {
                    translationY = (U_HEAD + U_FRONT_TOP + (1f - appear) * 46f) * k
                    rotationX = -22f * open
                    cameraDistance = 14f * density
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    alpha = appear
                }
                .clip(FrontShape),
        ) {
            // Faux « backdrop-filter » : l'image du dos, floutée, vue à travers le verre (flou réel dès Android 12).
            if (cover != null) {
                RemoteImage(
                    cover,
                    Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                        .offset(y = -u(U_FRONT_TOP)).requiredSize(u(U_W), u(U_H)).blur(22.dp),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color(0x30121214), 0.46f to Color(0xA00E0E10), 1f to Color(0xF208080A),
                    ),
                ),
            )
            Canvas(Modifier.fillMaxSize()) {
                drawPath(
                    scaled(FRONT_PATH, k, k),
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (selected) 0.75f else 0.5f),
                        0.35f to Color.White.copy(alpha = 0.18f),
                        1f to Color.White.copy(alpha = 0.06f),
                    ),
                    style = Stroke((if (selected) 2.dp else 1.5.dp).toPx()),
                )
            }
            if (running > 0) {
                Row(
                    Modifier.align(Alignment.TopEnd).padding(top = u(46f), end = 10.dp)
                        .clip(RoundedCornerShape(20.dp)).background(Color(0x99000000))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(Accent))
                    Text("$running en cours", fontSize = 10.sp, color = Xyd.Text)
                }
            }
            Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 10.dp, bottom = 11.dp)) {
                Text(
                    label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Xyd.Text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    meta, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Xyd.Text.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
