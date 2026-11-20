package tech.xydhub.xyd.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.xydhub.xyd.data.Title
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Style de cartes « accordéon » (réglé dans l'admin, par section).
 *
 * La carte de tête est large (paysage : image 16:9, titre et résumé sur un petit
 * panneau en verre en forme de dossier). Les suivantes sont des bandes étroites
 * (portrait : affiche sous un voile de verre sombre, titre à la verticale).
 * En faisant défiler, la carte de tête se resserre pendant que la suivante
 * s'élargit, et ainsi de suite, dans les deux sens. Lâchée, la rangée se cale
 * sur la carte la plus proche.
 *
 * Position continue `p` (0 = première carte en tête, 1 = deuxième…) :
 * la carte k = floor(p) passe de large à étroite et k + 1 d'étroite à large.
 */

private val PAD = 16.dp
private val GAP = 8.dp
private val NARROW = 54.dp
private val RADIUS = 14.dp

@Composable
fun AccordionRow(titles: List<Title>, onClick: (Title) -> Unit) {
    if (titles.isEmpty()) return
    val n = titles.size
    var p by remember(n) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dens = LocalDensity.current
        // Large : la place qui reste en laissant voir ~2 bandes étroites à droite.
        val wide: Dp = (maxWidth - PAD * 2 - (NARROW + GAP) * 2).coerceIn(170.dp, 560.dp)
        val height: Dp = (wide * 0.68f).coerceIn(150.dp, 300.dp)
        val stepPx = with(dens) { (NARROW + GAP).toPx() }
        val max = (n - 1).toFloat()

        fun goTo(target: Float, velocity: Float = 0f) {
            settle?.cancel()
            settle = scope.launch {
                animate(p, target, initialVelocity = velocity, animationSpec = spring(dampingRatio = 0.86f, stiffness = 260f)) { v, _ -> p = v }
            }
        }

        val dragState = rememberDraggableState { dx -> p = (p - dx / stepPx).coerceIn(0f, max) }
        Layout(
            modifier = Modifier.fillMaxWidth().height(height).clipToBounds().draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { settle?.cancel() },
                onDragStopped = { v ->
                    val vItems = -v / stepPx
                    // Projection courte de l'élan, puis on se cale sur une carte entière.
                    val target = (p + vItems * 0.18f).roundToInt().toFloat().coerceIn(0f, max)
                    goTo(target, vItems)
                },
            ),
            content = {
                titles.forEachIndexed { i, t ->
                    val e = expansionOf(i, p)
                    AccordionCard(
                        t, i, n, e,
                        onClick = { if (e > 0.85f) onClick(t) else goTo(i.toFloat().coerceAtMost(max)) },
                    )
                }
            },
        ) { measurables, constraints ->
            val padPx = PAD.toPx(); val gapPx = GAP.toPx(); val nPx = NARROW.toPx(); val wPx = wide.toPx()
            val h = constraints.maxHeight
            val placeables = measurables.mapIndexed { i, m ->
                val w = nPx + (wPx - nPx) * expansionOf(i, p)
                m.measure(Constraints.fixed(w.roundToInt().coerceAtLeast(1), h))
            }
            layout(constraints.maxWidth, h) {
                val k = floor(p).toInt().coerceIn(0, n - 1)
                val f = p - k
                // La carte de tête glisse vers la gauche d'une bande pendant qu'elle se resserre.
                var x = padPx - f * (nPx + gapPx) - k * (nPx + gapPx)
                placeables.forEach { pl ->
                    if (x + pl.width > -nPx && x < constraints.maxWidth + nPx) pl.place(x.roundToInt(), 0)
                    x += pl.width + gapPx
                }
            }
        }
    }
}

/** 1 = carte large (tête), 0 = bande étroite. */
private fun expansionOf(i: Int, p: Float): Float = (1f - abs(i - p)).coerceIn(0f, 1f)

@Composable
private fun AccordionCard(t: Title, index: Int, total: Int, e: Float, onClick: () -> Unit) {
    val counter = "%02d / %02d".format(index + 1, total)
    val landscape = ((e - 0.45f) / 0.55f).coerceIn(0f, 1f) // apparition progressive des textes horizontaux
    val portrait = (1f - e * 1.8f).coerceIn(0f, 1f)        // titre vertical, disparaît vite à l'élargissement

    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(RADIUS)).background(Xyd.Card)
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(RADIUS))
            .clickable(onClick = onClick),
    ) {
        // Couverture : affiche (portrait) qui cède la place à l'image 16:9 (paysage).
        RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - e })
        RemoteImage(t.backdrop ?: t.poster, Modifier.fillMaxSize().graphicsLayer { alpha = e })

        // Voile de verre sombre, bien transparent, en mode portrait.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - e }.background(Color(0x66000000)))
        // Dégradé bas en paysage, pour lire le panneau.
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = e }
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xB3000000))),
        )

        // Compteur « 02 / 05 » : en haut, centré en bande, à gauche en paysage.
        Text(
            counter,
            Modifier.align(if (e > 0.5f) Alignment.TopStart else Alignment.TopCenter).padding(top = 9.dp, start = if (e > 0.5f) 12.dp else 0.dp),
            color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp,
            maxLines = 1,
        )

        // Bande : titre à la verticale.
        if (portrait > 0f) {
            Text(
                t.name.uppercase(),
                Modifier.align(Alignment.Center).graphicsLayer { alpha = portrait }.verticalText(),
                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
            )
        }

        // Paysage : petit panneau en verre en forme de dossier, entre l'image et les textes.
        if (landscape > 0f) {
            BoxWithConstraints(
                Modifier.align(Alignment.BottomStart).padding(10.dp).fillMaxWidth(0.78f)
                    .graphicsLayer { alpha = landscape; translationX = (1f - landscape) * 14.dp.toPx() },
            ) {
                val pw = maxWidth
                Box(Modifier.width(pw).aspectRatio(FOLDER_GLASS_W / FOLDER_GLASS_H).clip(FolderGlassShape)) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0f to Color(0x55141416), 0.5f to Color(0x9E0E0E10), 1f to Color(0xD908080A)),
                        ),
                    )
                    Canvas(Modifier.fillMaxSize()) {
                        drawPath(
                            scaledFolderPath(size.width / FOLDER_GLASS_W, size.height / FOLDER_GLASS_H),
                            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.55f), 0.4f to Color.White.copy(alpha = 0.16f), 1f to Color.White.copy(alpha = 0.06f)),
                            style = Stroke(1.2.dp.toPx()),
                        )
                    }
                    Column(Modifier.fillMaxSize().padding(start = 11.dp, end = 10.dp, top = pw * 0.105f, bottom = 8.dp)) {
                        Text(t.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.synopsis.isNotBlank()) {
                            Text(
                                t.synopsis, color = Color.White.copy(alpha = 0.66f), fontSize = 10.sp, lineHeight = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(if (t.isFilm) "Film" else "Série", t.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
                                color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text("VOIR ↗", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Texte tourné de 90° (lecture de haut en bas) qui occupe la hauteur de la bande. */
private fun Modifier.verticalText(): Modifier = this
    .layout { m, c ->
        val pl = m.measure(Constraints(maxWidth = c.maxHeight, maxHeight = c.maxWidth))
        layout(pl.height, pl.width) { pl.place(-(pl.width - pl.height) / 2, (pl.width - pl.height) / 2) }
    }
    .graphicsLayer { rotationZ = 90f }
