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
import androidx.compose.runtime.setValue
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
 * Vapeur bleue qui tourbillonne, monte et se dissipe (mêmes bleus que [AuroraBand]).
 * Fond du texte du style « halo ».
 *
 * Android 13+ : shader AGSL (bruit fractal « domain warping » : la fumée se replie sur elle-même,
 * des volutes naissent, s'étirent et s'effacent). Ailleurs, ou si le shader ne compile pas :
 * bouffées de vapeur douces qui montent, gonflent et s'évaporent.
 */
@Composable
fun VaporGlow(modifier: Modifier = Modifier) {
    var time by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            androidx.compose.runtime.withFrameNanos { time = ((it - start) / 1e9f) % 3600f }
        }
    }
    val vapor = androidx.compose.runtime.remember {
        if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { VaporShader() }.getOrNull() else null
    }
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        if (vapor != null) {
            drawRect(vapor.brush(size.width, size.height, time, density))
        } else {
            drawPuffs(time)
        }
        // Fondus : côté affiches, en haut et en bas.
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.3f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
        drawRect(
            Brush.verticalGradient(0f to Color.Transparent, 0.22f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent),
            blendMode = BlendMode.DstIn,
        )
    }
}

@androidx.annotation.RequiresApi(33)
private class VaporShader {
    private val shader = android.graphics.RuntimeShader(VAPOR_AGSL)
    private val brush = androidx.compose.ui.graphics.ShaderBrush(shader)
    fun brush(w: Float, h: Float, t: Float, density: Float): Brush {
        shader.setFloatUniform("res", w, h)
        shader.setFloatUniform("t", t)
        shader.setFloatUniform("scale", 1f / (110f * density)) // une volute ≈ 110 dp, quelle que soit la densité d'écran
        return brush
    }
}

private const val VAPOR_AGSL = """
uniform float2 res;
uniform float t;
uniform float scale;

float hash(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 5; i++) {
        v += a * noise(p);
        p = float2(1.6 * p.x + 1.2 * p.y, -1.2 * p.x + 1.6 * p.y) + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fc) {
    float2 p = fc * scale;
    // La vapeur monte (le motif glisse vers le haut) et dérive un peu de côté.
    p += float2(sin(t * 0.07) * 0.6, t * 0.16);
    // Repliement du bruit sur lui-même : volutes qui tourbillonnent.
    float2 q = float2(fbm(p + float2(0.0, t * 0.05)), fbm(p + float2(5.2, 1.3) - t * 0.04));
    float2 r = float2(fbm(p + 3.6 * q + float2(1.7, 9.2) + t * 0.11),
                      fbm(p + 3.6 * q + float2(8.3, 2.8) - t * 0.09));
    float f = fbm(p + 3.2 * r);
    // Nappes de densité qui apparaissent puis s'évaporent lentement.
    float life = smoothstep(0.18, 0.62, fbm(p * 0.45 + float2(t * 0.035, -t * 0.02) + 11.0));
    float d = smoothstep(0.38, 0.88, f) * life;
    d = pow(d, 1.4);
    half3 deep = half3(0.18, 0.28, 0.90);
    half3 blue = half3(0.30, 0.42, 1.00);
    half3 light = half3(0.62, 0.70, 1.00);
    half3 col = mix(deep, blue, half(clamp(length(q) * 1.1, 0.0, 1.0)));
    col = mix(col, light, half(clamp(r.x * r.x * 1.6, 0.0, 1.0)));
    half a = half(clamp(d * 1.5, 0.0, 0.8));
    return half4(col * a, a);
}
"""

/** Repli (avant Android 13) : bouffées de vapeur qui montent, gonflent, ondulent et s'évaporent. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPuffs(t: Float) {
    val w = size.width
    val h = size.height
    for (i in 0 until PUFFS) {
        val life = 7f + (i % 4) * 1.3f
        val age = ((t + i * 0.83f) % life) / life           // 0 → 1 sur la vie de la bouffée
        val seed = (i * 37 % 100) / 100f
        val x = w * (0.25f + 0.65f * seed) + w * 0.08f * sin(t * 0.6f + i)
        val y = h * (1.05f - age * 1.1f)
        val r = w * (0.10f + 0.28f * age)
        val a = 0.32f * sin(age * PI.toFloat()) * (0.7f + 0.3f * sin(t * 1.7f + i * 2f))
        val c = when (i % 3) { 0 -> BlueDeep; 1 -> Blue; else -> BlueLight }
        drawCircle(
            Brush.radialGradient(0f to c.copy(alpha = a), 0.55f to c.copy(alpha = a * 0.35f), 1f to Color.Transparent, center = Offset(x, y), radius = r),
            radius = r, center = Offset(x, y),
        )
    }
}

private const val PUFFS = 16
