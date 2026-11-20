package tech.xydhub.xyd.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.xydhub.xyd.data.Title

/**
 * Affiche de série qui se retourne en 3D (axe vertical) : recto = l'affiche,
 * verso = infos de la série (saisons, épisodes, année, résumé) + bouton « Voir ».
 * Le retournement est piloté par l'appelant (appui long), pour n'en avoir qu'une ouverte à la fois.
 */
@Composable
fun FlipSeriesPoster(t: Title, flipped: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val rot by animateFloatAsState(
        if (flipped) 180f else 0f,
        spring(dampingRatio = 0.72f, stiffness = 170f),
        label = "flip",
    )
    // Retournée : la carte grandit un peu avec un rebond et passe au-dessus de ses voisines.
    // 1,1× reste dans la marge de dessin des rangées (pas de rognage) et garde les voisines visibles.
    val lift by animateFloatAsState(
        if (flipped) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = 320f),
        label = "lift",
    )
    Box(
        modifier
            .zIndex(if (flipped || lift > 0.01f) 1f else 0f)
            .aspectRatio(2f / 3f)
            .graphicsLayer {
                rotationY = rot
                cameraDistance = 10f * density
                scaleX = 1f + 0.1f * lift
                scaleY = 1f + 0.1f * lift
                translationY = -6.dp.toPx() * lift
                shadowElevation = 18.dp.toPx() * lift.coerceIn(0f, 1f)
                shape = RoundedCornerShape(10.dp)
                clip = true
            },
    ) {
        if (rot <= 90f) {
            RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize())
            if (t.poster == null && t.backdrop == null) {
                Text(t.name, Modifier.align(Alignment.Center).padding(8.dp), color = Xyd.Grey, fontWeight = FontWeight.SemiBold)
            }
        } else {
            // Verso : redressé (sinon le texte serait en miroir).
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { SeriesBack(t, onOpen) }
        }
    }
}

@Composable
private fun SeriesBack(t: Title, onOpen: () -> Unit) {
    val episodes = t.seasons.sumOf { it.episodes.size }
    Box(Modifier.fillMaxSize().background(Color(0xFF101012))) {
        // Fond : l'affiche floutée et assombrie (flou réel dès Android 12).
        RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize().blur(18.dp))
        Box(Modifier.fillMaxSize().background(Color(0xD90B0B0D)))
        Box(
            Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
        )
        Column(Modifier.fillMaxSize().padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Stat("${t.seasons.size}", if (t.seasons.size > 1) "saisons" else "saison", Modifier.weight(1f))
                Stat("$episodes", "ép.", Modifier.weight(1f))
                Stat(if (t.year > 0) "${t.year}".takeLast(4) else "—", "année", Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                t.name, color = Xyd.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Text(
                t.synopsis.ifBlank { "Pas encore de résumé." },
                color = Xyd.Text.copy(alpha = 0.62f), fontSize = 9.5.sp, lineHeight = 12.sp,
                maxLines = 5, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp).weight(1f),
            )
            Text(
                "Voir la série",
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Xyd.Grey)
                    .clickable(onClick = onOpen).padding(vertical = 6.dp),
                color = Xyd.Black, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    // Compact : pas de marge de police, interligne = taille du texte, chiffre et libellé collés.
    val tight = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.08f)).padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = Xyd.Text, fontSize = 12.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = tight)
        Text(label, color = Xyd.Muted, fontSize = 8.sp, lineHeight = 8.sp, maxLines = 1, style = tight)
    }
}
