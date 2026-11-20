package tech.xydhub.xyd.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/*
 * Boutons et barres de téléchargement « Surge » : deux références combinées.
 * - Liquide bleu lumineux à surface ondulée : il remplit le bouton au rythme du
 *   téléchargement (plein = prêt à lire).
 * - Trait de lumière qui tourne sur le contour : téléchargement actif (en cours / en attente).
 * - Fond sombre et brillant (reflet en haut, liseré fin) quand il n'y a pas encore de liquide.
 */

private val LiquidTop = Color(0xFF63D2FF)
private val LiquidMid = Color(0xFF2F8BFF)
private val LiquidDeep = Color(0xFF1B4FE0)
private val Glow = Color(0xFF3D7BFF)

/** Animations partagées (vague + rotation du trait), coupées quand rien ne bouge. */
@Composable
private fun surgeAnim(waving: Boolean, spinning: Boolean): Pair<Float, Float> {
    if (!waving && !spinning) return 0f to 0f
    val t = rememberInfiniteTransition(label = "surge")
    val wave by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "wave")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "spin")
    return (if (waving) wave else 0f) to (if (spinning) spin else 0f)
}

/** Liquide horizontal (le remplissage avance de gauche à droite), surface ondulée en haut. */
private fun DrawScope.liquid(fill: Float, phase: Float, radius: Float, calm: Boolean) {
    if (fill <= 0f) return
    val w = size.width * fill.coerceIn(0f, 1f)
    val h = size.height
    val amp = if (calm) h * 0.03f else h * 0.075f
    val level = h * (if (fill >= 1f) 0.16f else 0.22f)
    val path = Path().apply {
        moveTo(0f, h)
        lineTo(0f, level)
        var x = 0f
        val step = 4f
        while (x <= w) {
            val y = level + amp * sin(x / h * 3.1f + phase) + amp * 0.5f * sin(x / h * 7.3f - phase * 1.6f)
            lineTo(x, y)
            x += step
        }
        lineTo(w, h)
        close()
    }
    drawPath(path, Brush.verticalGradient(0f to LiquidTop, 0.45f to LiquidMid, 1f to LiquidDeep, startY = level - amp, endY = h))
    // Reflet de la surface + bord avant adouci (pas de coupure nette).
    drawPath(path, Color.White.copy(alpha = 0.22f), style = Stroke(1.2.dp.toPx()))
    if (fill < 1f) {
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color(0x55A8E6FF), startX = (w - 18.dp.toPx()).coerceAtLeast(0f), endX = w),
            topLeft = Offset((w - 18.dp.toPx()).coerceAtLeast(0f), level), size = Size(18.dp.toPx().coerceAtMost(w), h - level),
        )
    }
}

/** Dégradé balayé dont le trait lumineux est placé à `angle` degrés. */
private fun DrawScope.rotatedSweep(angle: Float): Brush {
    val a = ((angle % 360f) + 360f) % 360f / 360f
    fun at(f: Float) = ((a + f) % 1f + 1f) % 1f
    // Fenêtre lumineuse de ~22 % du tour centrée sur a.
    val stops = listOf(
        at(-0.11f) to Color.Transparent, at(-0.04f) to Color(0x66BFE6FF), at(0f) to Color.White,
        at(0.02f) to Color(0x66BFE6FF), at(0.06f) to Color.Transparent,
    ).sortedBy { it.first }
    val full = buildList {
        add(0f to stops.colorAt(0f))
        addAll(stops)
        add(1f to stops.colorAt(1f))
    }.distinctBy { it.first }
    return Brush.sweepGradient(*full.toTypedArray(), center = center)
}

/** Couleur du dégradé circulaire au point f (interpolée entre les arrêts voisins, en bouclant). */
private fun List<Pair<Float, Color>>.colorAt(f: Float): Color {
    val before = lastOrNull { it.first <= f } ?: (last().first - 1f to last().second)
    val after = firstOrNull { it.first >= f } ?: (first().first + 1f to first().second)
    val span = (after.first - before.first).takeIf { it > 0f } ?: return before.second
    val t = (f - before.first) / span
    return androidx.compose.ui.graphics.lerp(before.second, after.second, t)
}

/** Fond sombre et brillant (sans liquide). */
private fun DrawScope.darkGloss(radius: Float) {
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF1E1F23), Color(0xFF0B0B0D))), cornerRadius = CornerRadius(radius, radius))
    drawRoundRect(
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.09f), 0.45f to Color.Transparent),
        cornerRadius = CornerRadius(radius, radius),
    )
    val sw = 1.dp.toPx()
    drawRoundRect(
        Color.White.copy(alpha = 0.10f), topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
        cornerRadius = CornerRadius(radius, radius), style = Stroke(sw),
    )
}

/**
 * Bouton principal (lire / télécharger / progression).
 * @param fill 0 = vide (fond sombre), 0..1 = progression, 1 = plein (prêt).
 * @param active téléchargement en cours ou en attente : trait lumineux sur le contour.
 * @param moving vagues animées (téléchargement qui avance).
 */
@Composable
fun SurgeButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    fill: Float = 0f,
    active: Boolean = false,
    moving: Boolean = false,
    height: Dp = 50.dp,
) {
    val f by animateFloatAsState(fill.coerceIn(0f, 1f), tween(600), label = "fill")
    val (phase, spin) = surgeAnim(waving = f > 0f, spinning = active)
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(height).clip(shape)
            .drawBehind {
                val r = size.height / 2
                // Halo bleu sous le liquide.
                if (f > 0f) drawRoundRect(Glow.copy(alpha = 0.18f * f), cornerRadius = CornerRadius(r, r))
                darkGloss(r)
                liquid(f, phase, r, calm = !moving && f < 1f)
            }
            .drawWithContent {
                drawContent()
                if (active) {
                    val r = size.height / 2
                    val sw = 1.5.dp.toPx()
                    drawRoundRect(
                        rotatedSweep(spin), topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                        cornerRadius = CornerRadius(r, r), style = Stroke(sw),
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
            Text(
                label.uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.6.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Petit bouton rond (épisodes) : le liquide monte du bas, trait lumineux autour quand c'est actif. */
@Composable
fun SurgeRing(fill: Float, active: Boolean, moving: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val f by animateFloatAsState(fill.coerceIn(0f, 1f), tween(600), label = "fill")
    val (phase, spin) = surgeAnim(waving = f > 0f && f < 1f, spinning = active)
    Box(modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(34.dp).clip(CircleShape)) {
            drawCircle(Brush.verticalGradient(listOf(Color(0xFF1E1F23), Color(0xFF0B0B0D))))
            if (f > 0f) {
                val h = size.height
                val level = h * (1f - f)
                val amp = if (f >= 1f) 0f else h * (if (moving) 0.06f else 0.025f)
                val path = Path().apply {
                    moveTo(0f, h); lineTo(0f, level)
                    var x = 0f
                    while (x <= size.width) { lineTo(x, level + amp * sin(x / size.width * 6.3f + phase)); x += 2f }
                    lineTo(size.width, h); close()
                }
                drawPath(path, Brush.verticalGradient(listOf(LiquidTop, LiquidMid, LiquidDeep), startY = level, endY = h))
            }
            drawCircle(Color.White.copy(alpha = 0.12f), style = Stroke(1.dp.toPx()), radius = size.minDimension / 2 - 0.5.dp.toPx())
            if (active) drawCircle(rotatedSweep(spin), style = Stroke(1.6.dp.toPx()), radius = size.minDimension / 2 - 0.8.dp.toPx())
        }
        content()
    }
}

/** Barre de progression fine : liquide lumineux, avec un reflet qui file quand ça avance. */
@Composable
fun SurgeBar(fraction: Float, moving: Boolean, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(500), label = "bar")
    val sheen = if (moving) {
        val t = rememberInfiniteTransition(label = "sheen")
        t.animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "sheen-x").value
    } else -1f
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2
        drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = CornerRadius(r, r))
        if (f <= 0f) return@Canvas
        val w = size.width * f
        // Halo puis liquide.
        drawRoundRect(Glow.copy(alpha = 0.35f), size = Size(w, size.height), cornerRadius = CornerRadius(r, r))
        drawRoundRect(
            Brush.horizontalGradient(listOf(LiquidDeep, LiquidMid, LiquidTop), endX = w),
            size = Size(w, size.height), cornerRadius = CornerRadius(r, r),
        )
        if (sheen >= 0f) {
            val x = w * sheen
            drawRoundRect(
                Brush.horizontalGradient(0f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.55f), 1f to Color.Transparent,
                    startX = x - 30.dp.toPx(), endX = x + 30.dp.toPx()),
                size = Size(w, size.height), cornerRadius = CornerRadius(r, r),
            )
        }
    }
}
