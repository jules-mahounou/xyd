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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tech.xydhub.xyd.data.Title
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/*
 * Style de cartes « carrousel incurvé » (réglé dans l'admin, par section).
 *
 * La carte du centre est à plat, face à l'écran ; les voisines pivotent vers
 * l'intérieur comme collées à l'intérieur d'un cylindre (leur bord extérieur
 * paraît plus grand). Un filet de lumière bleue souligne le haut et le bas.
 * Glisser fait tourner le cylindre ; lâché, il se cale sur la carte la plus proche.
 * Toucher la carte du centre ouvre la fiche, toucher une voisine l'amène au centre.
 */


/** Shader AGSL (Android 13+) : courbure « dedans un cylindre », décalage des couleurs, traînée en mouvement. */
private const val WARP_AGSL = """
uniform shader content;
uniform float2 size;
uniform float view;
uniform float bend;
uniform float chroma;
uniform float smear;
uniform float3 tint;

half4 main(float2 fc) {
    float2 c = size * 0.5;
    float hv = view * 0.5;                 // demi-largeur visible (le calque est plus large que l'écran)
    float x = (fc.x - c.x) / hv;           // -1 .. 1 sur la partie visible
    float y = (fc.y - c.y) / c.y;
    float ax = abs(x);
    // Bords plus hauts (la carte s'évase) et plus serrés (on voit plus loin sur le cylindre).
    float sy = 1.0 + bend * 0.62 * ax * ax * ax;
    float sx = 1.0 + bend * 0.95 * ax * ax;
    float2 src = float2(c.x + x * sx * hv, c.y + y / sy * c.y);
    float ca = chroma * ax * ax * sign(x);
    half4 acc = half4(0.0);
    for (int i = 0; i < 5; i++) {
        float2 o = float2((float(i) - 2.0) * 0.5 * smear, 0.0);
        half4 r = content.eval(src + o + float2(ca, 0.0));
        half4 g = content.eval(src + o);
        half4 b = content.eval(src + o - float2(ca, 0.0));
        acc += half4(r.r, g.g, b.b, max(max(r.a, g.a), b.a));
    }
    acc /= 5.0;
    // Lueur bleue qui monte vers les bords courbés.
    float edge = smoothstep(0.55, 1.0, ax);
    acc.rgb += half3(tint) * acc.a * edge * 0.35;
    return acc;
}
"""

@androidx.annotation.RequiresApi(33)
private class WarpShader {
    val shader = android.graphics.RuntimeShader(WARP_AGSL)
    fun effect(w: Float, h: Float, view: Float, smear: Float): androidx.compose.ui.graphics.RenderEffect {
        shader.setFloatUniform("size", w, h)
        shader.setFloatUniform("view", view)
        shader.setFloatUniform("bend", 1f)
        shader.setFloatUniform("chroma", 7f * (view / 1080f).coerceAtLeast(0.6f) + abs(smear) * 0.25f)
        shader.setFloatUniform("smear", smear)
        shader.setFloatUniform("tint", 0.30f, 0.42f, 1.0f)
        return android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

private val Accent = Color(0xFF4C6BFF)
private val Cyan = Color(0xFF6FD6FF)

@Composable
fun WarpCarousel(titles: List<Title>, onClick: (Title) -> Unit) {
    if (titles.isEmpty()) return
    val n = titles.size
    var p by remember(n) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var settle by remember { mutableStateOf<Job?>(null) }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val dens = LocalDensity.current
        val shaded = android.os.Build.VERSION.SDK_INT >= 33
        val cardW = (maxWidth * (if (shaded) 0.5f else 0.42f)).coerceIn(140.dp, 260.dp)
        val cardH = cardW * 1.45f
        val height = cardH + 44.dp
        val stepPx = with(dens) { (cardW * (if (shaded) 1.08f else 0.9f)).toPx() }
        // Vitesse lissée (px / image) : alimente la traînée du shader pendant le glissement et le calage.
        var smear by remember { mutableFloatStateOf(0f) }
        if (shaded) androidx.compose.runtime.LaunchedEffect(stepPx) {
            var prev = p
            while (true) {
                androidx.compose.runtime.withFrameNanos { }
                val d = (p - prev) * stepPx
                prev = p
                val next = smear * 0.55f + (-d).coerceIn(-60f, 60f) * 0.45f
                smear = if (abs(next) < 0.3f) 0f else next
            }
        }
        val warp = remember { if (shaded) WarpShader() else null }
        val max = (n - 1).toFloat()

        fun goTo(target: Float, velocity: Float = 0f) {
            settle?.cancel()
            settle = scope.launch {
                animate(p, target, initialVelocity = velocity, animationSpec = spring(dampingRatio = 0.8f, stiffness = 220f)) { v, _ -> p = v }
            }
        }

        val dragState = rememberDraggableState { dx -> p = (p - dx / stepPx).coerceIn(-0.35f, max + 0.35f) }
        Box(
            Modifier.fillMaxWidth().height(height).draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                onDragStarted = { settle?.cancel() },
                onDragStopped = { v ->
                    val vItems = -v / stepPx
                    goTo((p + vItems * 0.2f).roundToInt().toFloat().coerceIn(0f, max), vItems)
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            // Filets de lumière en arc, en haut et en bas (le bord du cylindre).
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val glow = Brush.horizontalGradient(
                    0f to Color.Transparent, 0.3f to Cyan.copy(alpha = 0.55f), 0.5f to Accent.copy(alpha = 0.9f),
                    0.7f to Cyan.copy(alpha = 0.55f), 1f to Color.Transparent,
                )
                val bow = 14.dp.toPx()
                fun arc(y: Float, dir: Float) = Path().apply {
                    moveTo(0f, y + dir * bow)
                    quadraticTo(w / 2f, y - dir * bow, w, y + dir * bow)
                }
                val top = 8.dp.toPx(); val bottom = size.height - 8.dp.toPx()
                for ((y, dir) in listOf(top to 1f, bottom to -1f)) {
                    drawPath(arc(y, dir), glow, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round), alpha = 0.18f)
                    drawPath(arc(y, dir), glow, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
                }
            }

            // Les cartes sont posées à plat ; le shader courbe le tout (Android 13+).
            // Calque 2,2× plus large que l'écran : les bords courbés montrent les cartes plus lointaines.
            val viewPx = with(dens) { maxWidth.toPx() }
            Box(
                Modifier.requiredWidth(if (shaded) maxWidth * 2.2f else maxWidth).fillMaxHeight().graphicsLayer {
                    if (warp != null) renderEffect = warp.effect(size.width, size.height, viewPx, smear)
                },
                contentAlignment = Alignment.Center,
            ) {
                titles.forEachIndexed { i, t ->
                    val d = i - p
                    if (abs(d) < 3.2f) {
                        WarpCard(
                            t, i, n, d,
                            Modifier.size(cardW, cardH).zIndex(-abs(d)),
                            onClick = { if (abs(d) < 0.5f) onClick(t) else goTo(i.toFloat()) },
                            stepPx = stepPx, flat = shaded,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WarpCard(t: Title, index: Int, total: Int, d: Float, modifier: Modifier, onClick: () -> Unit, stepPx: Float, flat: Boolean) {
    val ad = abs(d)
    val focus = (1f - ad).coerceIn(0f, 1f) // 1 = au centre
    Box(
        modifier
            .graphicsLayer {
                if (flat) {
                    // Bande plate : c'est le shader qui donne la courbure.
                    translationX = d * stepPx
                    alpha = (3.2f - ad).coerceIn(0f, 1f)
                    return@graphicsLayer
                }
                // Position sur le cylindre : les voisines se rapprochent un peu en s'éloignant du centre.
                translationX = d * stepPx * (1f - 0.1f * ad.coerceAtMost(2f))
                // Tournées vers l'intérieur : le bord extérieur vient vers nous (effet « dedans »).
                rotationY = (-d * 52f).coerceIn(-72f, 72f)
                cameraDistance = 7f * density
                transformOrigin = TransformOrigin(if (d < 0f) 1f else 0f, 0.5f)
                val s = 1f - 0.06f * ad.coerceAtMost(2f)
                scaleX = s; scaleY = s
                alpha = (3.2f - ad).coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Xyd.Card)
            .border(1.dp, Color.White.copy(alpha = 0.10f + 0.12f * focus), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize())
        // Reflet bleuté sur les voisines (lumière du cylindre), absent au centre.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    if (d < 0f) listOf(Accent.copy(alpha = 0.35f * (1f - focus)), Color.Transparent)
                    else listOf(Color.Transparent, Accent.copy(alpha = 0.35f * (1f - focus))),
                ),
            ),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC000000))))
        Text(
            "%02d / %02d".format(index + 1, total),
            Modifier.align(Alignment.TopStart).padding(10.dp),
            color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, fontFamily = FontFamily.Monospace,
        )
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 11.dp, vertical = 10.dp).graphicsLayer { alpha = 0.35f + 0.65f * focus }) {
            Text(t.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
            Text(
                listOfNotNull(if (t.isFilm) "Film" else "Série", t.year.takeIf { it > 0 }?.toString()).joinToString(" · "),
                color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}
