package tech.xydhub.xyd.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tech.xydhub.xyd.data.Title
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Deux styles de cartes animés (réglés dans l'admin, par section) :
 *
 *  - « halo » : les affiches tournent sur un halo (une ellipse vue de côté). L'affiche de face est
 *    grande, à droite du halo ; les suivantes s'éloignent vers le haut à gauche, les précédentes vers
 *    le bas à gauche, plus petites et plus sombres. Le titre de l'affiche de face est écrit à droite.
 *  - « zoom » : rangée en escalier, alignée en bas. La carte active est la plus grande, la suivante
 *    dépasse au bord droit, les précédentes rapetissent vers la gauche. Le titre est posé au-dessus
 *    de la carte active.
 *
 * Les deux tournent en boucle (à partir de 3 cartes), défilent seuls toutes les quelques secondes
 * et se pilotent au doigt (glisser à gauche = suivant). Position continue `p` : la carte i est à la
 * distance d = i - p de la place active (modulo le nombre de cartes en boucle).
 */

private val RADIUS = 12.dp

/** Position continue + calage animé + défilement automatique, partagés par les deux styles. */
private class ReelState(val n: Int, val loop: Boolean) {
    var p by mutableFloatStateOf(0f)
    var settle: Job? = null
    var touchedAt by mutableLongStateOf(0L)
    fun clamp(v: Float) = if (loop) v else v.coerceIn(0f, (n - 1).toFloat())
    fun index(virtual: Int) = ((virtual % n) + n) % n
}

@Composable
private fun rememberReel(n: Int, loop: Boolean, autoMs: Long): Pair<ReelState, (Float, Float) -> Unit> {
    var saved by rememberSaveable(n) { mutableFloatStateOf(0f) }
    val st = remember(n) { ReelState(n, loop).also { it.p = saved } }
    val scope = rememberCoroutineScope()
    val goTo: (Float, Float) -> Unit = remember(st) {
        { target, velocity ->
            st.settle?.cancel()
            st.settle = scope.launch {
                animate(st.p, st.clamp(target), initialVelocity = velocity, animationSpec = spring(dampingRatio = 0.82f, stiffness = 140f)) { v, _ ->
                    st.p = v
                    saved = v
                }
            }
        }
    }
    // Défilement automatique : en pause 6 s après chaque geste. S'arrête quand la rangée quitte l'écran.
    if (n > 1) LaunchedEffect(st) {
        while (true) {
            delay(autoMs)
            if (System.currentTimeMillis() - st.touchedAt < 6000) continue
            val next = floor(st.p + 0.5f) + 1f
            goTo(if (!loop && next > n - 1) 0f else next, 0f)
        }
    }
    return st to goTo
}

@Composable
private fun Modifier.reelDrag(st: ReelState, stepPx: Float, goTo: (Float, Float) -> Unit): Modifier {
    val drag = rememberDraggableState { dx -> st.p = st.clamp(st.p - dx / stepPx); st.touchedAt = System.currentTimeMillis() }
    return this.draggable(
        state = drag,
        orientation = Orientation.Horizontal,
        onDragStarted = { st.settle?.cancel(); st.touchedAt = System.currentTimeMillis() },
        onDragStopped = { v ->
            st.touchedAt = System.currentTimeMillis()
            val vItems = -v / stepPx
            goTo(floor(st.p + vItems * 0.22f + 0.5f), vItems)
        },
    )
}

private fun metaOf(t: Title): String = listOfNotNull(
    if (t.ad != null) "Annonce" else if (t.isFilm) "Film" else "Série",
    t.year.takeIf { it > 0 }?.toString(),
    if (!t.isFilm && t.ad == null) t.seasons.sumOf { it.episodes.size }.takeIf { it > 0 }?.let { "$it ép." } else null,
).joinToString(" · ")

@Composable
private fun ReelCover(t: Title, w: Dp, h: Dp, dim: Float, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(RADIUS)).background(Xyd.Card)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(RADIUS)),
        contentAlignment = Alignment.Center,
    ) {
        // Taille fixe (celle de la plus grande carte) : chargée une fois, la carte la recadre sans l'étirer.
        RemoteImage(t.poster ?: t.backdrop, Modifier.requiredSize(w, h))
        if (dim > 0.01f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

// ---------------------------------------------------------------- Halo

@Composable
fun HaloReel(titles: List<Title>, onClick: (Title) -> Unit) {
    if (titles.isEmpty()) return
    val n = titles.size
    val loop = n >= 3
    val (st, goTo) = rememberReel(n, loop, autoMs = 3600)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dens = LocalDensity.current
        val W = maxWidth
        val cardW = (W * 0.36f).coerceIn(118.dp, 210.dp)
        val cardH = cardW * 1.5f
        val rowH = cardH + 74.dp
        val stepPx = with(dens) { (cardW * 0.9f).toPx() }
        val k = floor(st.p).toInt()
        val near = st.index(floor(st.p + 0.5f).toInt())
        val textAlpha = (1f - abs(st.p - floor(st.p + 0.5f)) * 2.4f).coerceIn(0f, 1f)

        Box(Modifier.fillMaxWidth().height(rowH).clipToBounds().reelDrag(st, stepPx, goTo)) {
            // Cartes du halo : de la plus lointaine à la plus proche (la carte de face est dessinée en dernier).
            val slots = (-3..4).map { k + it }
                .filter { loop || it in 0 until n }
                .sortedByDescending { abs(it - st.p) }
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    slots.forEach { v ->
                        key(v) {
                            val t = titles[st.index(v)]
                            val d = v - st.p
                            ReelCover(
                                t, cardW, cardH, dim = (abs(d) * 0.22f).coerceAtMost(0.7f),
                                modifier = Modifier.layoutId(v).clickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                ) {
                                    st.touchedAt = System.currentTimeMillis()
                                    if (abs(d) < 0.3f) onClick(t) else goTo(v.toFloat(), 0f)
                                },
                            )
                        }
                    }
                },
            ) { measurables, cons ->
                val cw = cardW.toPx(); val ch = cardH.toPx()
                val cxFront = cons.maxWidth * 0.40f       // centre de la carte de face
                val cy = cons.maxHeight / 2f
                val rx = cons.maxWidth * 0.52f             // ellipse du halo (vue de côté)
                val ry = cons.maxHeight * 0.30f
                val placed = measurables.map { m ->
                    val v = m.layoutId as Int
                    val d = v - st.p
                    val a = d * 0.62f
                    val s = (1f - abs(d) * 0.19f).coerceIn(0.38f, 1f)
                    val w = (cw * s).roundToInt().coerceAtLeast(1); val h = (ch * s).roundToInt().coerceAtLeast(1)
                    val cx = cxFront - rx * (1f - cos(a))
                    val y = cy - ry * sin(a)
                    Triple(m.measure(Constraints.fixed(w, h)), cx - w / 2f, y - h / 2f) to abs(d)
                }
                layout(cons.maxWidth, cons.maxHeight) {
                    placed.forEach { (t, dist) ->
                        val (pl, x, y) = t
                        if (dist < 3.6f) pl.placeWithLayer(x.roundToInt(), y.roundToInt()) { alpha = (3.6f - dist).coerceIn(0f, 1f) }
                    }
                }
            }

            // Titre de l'affiche de face, à droite du halo.
            val t = titles[near]
            Column(
                Modifier.align(Alignment.CenterEnd).width(W * 0.36f).padding(end = 18.dp)
                    .graphicsLayer { alpha = textAlpha; translationX = (1f - textAlpha) * 10.dp.toPx() }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick(t) },
            ) {
                Text("%02d / %02d".format(near + 1, n), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(
                    t.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                )
                Text(metaOf(t), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
                Text("VOIR ↗", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- Zoom (escalier)

/** Largeur relative d'une carte à la distance d de la place active (escalier vers la gauche). */
private fun zoomScale(d: Float): Float {
    if (d >= 0f) return 1f
    val steps = floatArrayOf(1f, 0.64f, 0.43f, 0.30f, 0.22f, 0.16f)
    val x = (-d).coerceAtMost(5f)
    val i = floor(x).toInt().coerceAtMost(4)
    val f = x - i
    return steps[i] + (steps[i + 1] - steps[i]) * f
}

@Composable
fun ZoomSlider(titles: List<Title>, onClick: (Title) -> Unit) {
    if (titles.isEmpty()) return
    val n = titles.size
    val loop = n >= 4
    val (st, goTo) = rememberReel(n, loop, autoMs = 4200)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dens = LocalDensity.current
        val W = maxWidth
        val bigW = (W * 0.5f).coerceIn(150.dp, 280.dp)
        val bigH = bigW * 1.28f
        val labelH = 58.dp // titre + infos au-dessus de la carte active (assez haut pour ne rien couper)
        val peek = 34.dp
        val gap = 6.dp
        val stepPx = with(dens) { (bigW * 0.75f).toPx() }
        val k = floor(st.p).toInt()
        val f = st.p - k
        val near = st.index(floor(st.p + 0.5f).toInt())
        val textAlpha = (1f - abs(st.p - floor(st.p + 0.5f)) * 2.4f).coerceIn(0f, 1f)

        Box(Modifier.fillMaxWidth().height(bigH + labelH).clipToBounds().reelDrag(st, stepPx, goTo)) {
            val slots = (-6..2).map { k + it }.filter { loop || it in 0 until n }
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    slots.forEach { v ->
                        key(v) {
                            val t = titles[st.index(v)]
                            val d = v - st.p
                            ReelCover(
                                t, bigW, bigH,
                                dim = if (d > 0.05f) (d * 0.45f).coerceAtMost(0.45f) else ((-d) * 0.12f).coerceAtMost(0.4f),
                                modifier = Modifier.layoutId(v).clickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                ) {
                                    st.touchedAt = System.currentTimeMillis()
                                    if (abs(d) < 0.3f) onClick(t) else goTo(v.toFloat(), 0f)
                                },
                            )
                        }
                    }
                },
            ) { measurables, cons ->
                val bw = bigW.toPx(); val bh = bigH.toPx(); val g = gap.toPx()
                val bottom = cons.maxHeight.toFloat()
                // Bord gauche de la carte k (au repos : place active ; f → 1 : elle recule d'une marche).
                val activeLeft = cons.maxWidth - peek.toPx() - g - bw
                val lefts = HashMap<Int, Float>()
                val widths = HashMap<Int, Float>()
                fun w(v: Int) = widths.getOrPut(v) { bw * zoomScale(v - st.p) }
                lefts[k] = activeLeft - f * (g + bw * zoomScale(-1f))
                for (v in k + 1..k + 2) lefts[v] = lefts.getValue(v - 1) + w(v - 1) + g
                for (v in k - 1 downTo k - 6) lefts[v] = lefts.getValue(v + 1) - g - w(v)
                val pls = measurables.map { m ->
                    val v = m.layoutId as Int
                    val wv = w(v); val hv = bh * (wv / bw)
                    Triple(m.measure(Constraints.fixed(wv.roundToInt().coerceAtLeast(1), hv.roundToInt().coerceAtLeast(1))), lefts.getValue(v), bottom - hv)
                }
                layout(cons.maxWidth, cons.maxHeight) {
                    pls.forEach { (pl, x, y) -> if (x < cons.maxWidth && x + pl.width > 0) pl.place(x.roundToInt(), y.roundToInt()) }
                }
            }

            // Titre au-dessus de la carte active.
            val t = titles[near]
            Column(
                Modifier.align(Alignment.TopEnd).offset(x = -(peek + gap)).width(bigW).height(labelH).padding(bottom = 10.dp)
                    .graphicsLayer { alpha = textAlpha; translationY = (1f - textAlpha) * 6.dp.toPx() }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick(t) },
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom,
            ) {
                Text(
                    t.name.uppercase(), color = Color.White, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "%02d / %02d · %s".format(near + 1, n, metaOf(t)),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 15.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
