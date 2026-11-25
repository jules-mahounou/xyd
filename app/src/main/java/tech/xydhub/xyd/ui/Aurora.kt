package tech.xydhub.xyd.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Traînée de lumière bleue animée en boucle, posée tout en bas d'un écran
 * (fond noir conservé). 100 % Canvas : aucun asset, aucune dépendance.
 *
 * La bande descend jusque sous la barre de navigation : elle touche le bas de l'écran.
 */
@Composable
fun AuroraBand(modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(DURATION_MS, easing = LinearEasing)),
        label = "aurora-t",
    )
    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.matchParentSize()
                // Calque séparé : les masques de fondu (DstIn) n'effacent que la lueur, pas l'écran dessous.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val w = size.width
            val h = size.height
            for (s in STREAKS) {
                // Dérive lente gauche/droite + légère respiration de l'intensité (boucle sans couture).
                val cx = w * (s.x + s.drift * sin(t * s.speed + s.phase))
                val cy = h * s.y
                val alpha = s.alpha * (0.78f + 0.22f * sin(t * 2f + s.phase))
                val len = w * 2.4f
                val th = h * s.thick
                rotate(ANGLE + s.tilt * sin(t + s.phase), Offset(cx, cy)) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to s.color.copy(alpha = alpha),
                            1f to Color.Transparent,
                            startY = cy - th / 2f,
                            endY = cy + th / 2f,
                        ),
                        topLeft = Offset(cx - len / 2f, cy - th / 2f),
                        size = Size(len, th),
                    )
                }
            }
            // Fondu vers le haut (la lueur naît du noir) et sur les bords gauche / droit.
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Black, 1f to Color.Black),
                blendMode = BlendMode.DstIn,
            )
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 0.25f to Color.Black, 0.85f to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
        }
        // Fixe la hauteur : `height` + la barre de navigation (si elle n'est pas déjà prise par le parent).
        Spacer(Modifier.fillMaxWidth().navigationBarsPadding().height(height))
    }
}

private const val TWO_PI = (2 * PI).toFloat()
private const val DURATION_MS = 9000
private const val ANGLE = 24f // degrés : la traînée descend vers la droite

private val Blue = Color(0xFF4C6BFF)
private val BlueLight = Color(0xFF9AACFF)
private val BlueDeep = Color(0xFF2E47E6)

private class Streak(
    val x: Float, val y: Float, val thick: Float, val alpha: Float, val color: Color,
    val drift: Float, val speed: Float, val phase: Float, val tilt: Float,
)

// Positions relatives à la bande (0..1). Du plus diffus au plus net.
private val STREAKS = listOf(
    Streak(x = 0.50f, y = 0.85f, thick = 1.10f, alpha = 0.34f, color = BlueDeep, drift = 0.04f, speed = 1f, phase = 0.0f, tilt = 1.5f), // halo
    Streak(x = 0.60f, y = 0.62f, thick = 0.48f, alpha = 0.55f, color = Blue, drift = 0.05f, speed = 1f, phase = 0.6f, tilt = 1.0f),    // lueur
    Streak(x = 0.57f, y = 0.80f, thick = 0.16f, alpha = 0.40f, color = Blue, drift = 0.06f, speed = 2f, phase = 2.1f, tilt = 2.0f),    // 2e traînée
    Streak(x = 0.61f, y = 0.60f, thick = 0.07f, alpha = 0.75f, color = BlueLight, drift = 0.05f, speed = 1f, phase = 0.6f, tilt = 1.0f), // filet lumineux
    Streak(x = 0.86f, y = 0.35f, thick = 0.40f, alpha = 0.10f, color = Blue, drift = 0.03f, speed = 1f, phase = 3.5f, tilt = 0.5f),   // reflet à droite
)

/**
 * Ondulations de lumière bleue (mêmes bleus que [AuroraBand]) : plusieurs filets en vagues qui
 * ondulent et se croisent lentement, avec une lueur diffuse. Fond du texte du style « halo ».
 * 100 % Canvas, boucle sans couture, fondu sur tous les bords.
 */
@Composable
fun WaveGlow(modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "waves").animateFloat(
        initialValue = 0f,
        targetValue = TWO_PI,
        animationSpec = infiniteRepeatable(tween(WAVE_MS, easing = LinearEasing)),
        label = "waves-t",
    )
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val w = size.width
        val h = size.height
        // Lueur de fond qui respire doucement.
        drawRect(
            Brush.radialGradient(
                0f to BlueDeep.copy(alpha = 0.20f + 0.06f * sin(t)),
                1f to Color.Transparent,
                center = Offset(w * (0.55f + 0.06f * sin(t + 1f)), h * 0.5f),
                radius = w * 0.75f,
            ),
        )
        val path = androidx.compose.ui.graphics.Path()
        for (wv in WAVES) {
            path.reset()
            val steps = 48
            for (i in 0..steps) {
                val u = i / steps.toFloat()
                val x = w * (-0.1f + 1.2f * u)
                // Deux sinus superposés : vague principale + petite ondulation qui « frémit ».
                val y = h * (wv.y + wv.amp * sin(u * wv.freq * TWO_PI + t * wv.speed + wv.phase)
                    + wv.amp * 0.35f * sin(u * wv.freq * 2.3f * TWO_PI - t * wv.speed * 2f + wv.phase * 1.7f))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val breathe = 0.75f + 0.25f * sin(t * 2f + wv.phase)
            // Halo large et diffus, puis le filet net par-dessus.
            drawPath(path, wv.color.copy(alpha = wv.alpha * 0.22f * breathe), style = androidx.compose.ui.graphics.drawscope.Stroke(width = wv.width * 9f * density))
            drawPath(path, wv.color.copy(alpha = wv.alpha * 0.45f * breathe), style = androidx.compose.ui.graphics.drawscope.Stroke(width = wv.width * 3f * density))
            drawPath(path, wv.color.copy(alpha = wv.alpha * breathe), style = androidx.compose.ui.graphics.drawscope.Stroke(width = wv.width * density))
        }
        // Fondus : vers la gauche (les affiches), en haut et en bas.
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.35f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.25f to Color.Black, 0.75f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
}

private const val WAVE_MS = 8000

private class Wave(
    val y: Float, val amp: Float, val freq: Float, val speed: Float, val phase: Float,
    val width: Float, val alpha: Float, val color: Color,
)

private val WAVES = listOf(
    Wave(y = 0.30f, amp = 0.07f, freq = 0.9f, speed = 1f, phase = 0.0f, width = 1.2f, alpha = 0.55f, color = Blue),
    Wave(y = 0.42f, amp = 0.10f, freq = 0.7f, speed = 1f, phase = 1.3f, width = 1.6f, alpha = 0.80f, color = BlueLight),
    Wave(y = 0.52f, amp = 0.08f, freq = 1.1f, speed = 2f, phase = 2.4f, width = 1.0f, alpha = 0.50f, color = Blue),
    Wave(y = 0.63f, amp = 0.11f, freq = 0.6f, speed = 1f, phase = 3.6f, width = 1.4f, alpha = 0.65f, color = BlueDeep),
    Wave(y = 0.74f, amp = 0.06f, freq = 1.3f, speed = 1f, phase = 4.8f, width = 0.9f, alpha = 0.40f, color = BlueLight),
)
