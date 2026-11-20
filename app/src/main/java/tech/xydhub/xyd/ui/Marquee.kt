package tech.xydhub.xyd.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.xydhub.xyd.data.Repo
import tech.xydhub.xyd.data.Supabase

/*
 * Carrousel diagonal (écran de connexion / inscription) : trois rangées
 * d'images du catalogue, inclinées, qui défilent sans fin en sens alternés,
 * entre un fondu noir en haut et un fondu vers le fond du modal en bas.
 * Images : celles du catalogue déjà en cache si on en a, sinon tirées au
 * hasard par la base (URL publiques uniquement).
 */

private val CARD_W = 150.dp
private val CARD_H = 92.dp
private val GAP = 10.dp

@Composable
fun CoverMarquee(height: Dp = 230.dp, fade: Color = Xyd.Surface) {
    val covers by produceState<List<String>>(emptyList()) {
        val cached = Repo.catalog.value.mapNotNull { it.backdrop ?: it.poster }
        value = (cached.ifEmpty { runCatching { Supabase.publicCovers(30) }.getOrDefault(emptyList()) }).shuffled()
    }
    Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
        if (covers.size >= 3) {
            // Trois rangées, chacune avec sa part des images (au moins 6 cartes pour boucler sans trou).
            val rows = (0 until 3).map { r -> covers.filterIndexed { i, _ -> i % 3 == r }.ifEmpty { covers } }
                .map { l -> generateSequence { l }.flatten().take(maxOf(6, l.size)).toList() }
            Column(
                Modifier.align(Alignment.Center).graphicsLayer { rotationZ = -14f; scaleX = 1.25f; scaleY = 1.25f },
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                rows.forEachIndexed { r, list -> MarqueeRow(list, reverse = r % 2 == 1, speedDpPerSec = 22f + r * 6f) }
            }
        }
        // Fondus haut / bas.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to fade, 0.28f to fade.copy(alpha = 0f), 0.62f to fade.copy(alpha = 0f), 1f to fade)))
    }
}

@Composable
private fun MarqueeRow(urls: List<String>, reverse: Boolean, speedDpPerSec: Float) {
    val dens = LocalDensity.current
    val loopPx = with(dens) { ((CARD_W + GAP) * urls.size).toPx() }
    val lead = with(dens) { ((CARD_W + GAP) * 2).toPx() }
    val durationMs = (urls.size * (CARD_W.value + GAP.value) / speedDpPerSec * 1000f).toInt().coerceAtLeast(4000)
    val t by rememberInfiniteTransition(label = "marquee").animateFloat(
        0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing)), label = "marquee-x",
    )
    Row(
        Modifier.wrapContentWidth(Alignment.Start, unbounded = true)
            // Décalage de départ de 2 cartes : l'inclinaison ne laisse jamais voir de vide sur les bords.
            .graphicsLayer { translationX = -lead + if (reverse) -loopPx + t * loopPx else -t * loopPx },
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        // La liste deux fois : quand la première moitié est sortie, la seconde est exactement à sa place.
        (urls + urls + urls).forEach { url ->
            Box(Modifier.size(CARD_W, CARD_H).clip(RoundedCornerShape(10.dp))) {
                RemoteImage(url, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color(0x22000000)))
            }
        }
    }
}
