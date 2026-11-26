package tech.xydhub.xyd.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Barre du bas « xyd » : apparaît quand on fait défiler l'accueil (les boutons du haut ne sont plus
 * visibles), collée tout en bas de l'écran.
 *
 * Fermée : un fil lumineux qui monte des deux coins vers le centre, où le logo xyd est posé dans un cercle.
 * Ouverte (toucher le logo) : les 4 actions du haut (recherche, demande, téléchargements, réglages)
 * jaillissent du logo et glissent le long du fil, l'une après l'autre, en passant du flou au net.
 * Derrière : verre sombre et flou progressif du contenu (fort tout en bas, nul en haut de la barre).
 */

/** Hauteur de la barre, au-dessus de la barre de navigation Android. */
val DOCK_HEIGHT = 66.dp

private class DockAction(val label: String, val icon: ImageVector, val badge: Int, val onClick: () -> Unit)

@Composable
fun BottomDock(
    visible: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    activeDownloads: Int,
    onSearch: () -> Unit,
    onRequest: () -> Unit,
    onDownloads: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 300f), label = "dock")
    if (shown < 0.01f) return
    val dens = LocalDensity.current
    val inset = with(dens) { WindowInsets.navigationBars.getBottom(this).toDp() }
    val actions = listOf(
        DockAction("Rechercher", Icons.Filled.Search, 0, onSearch),
        DockAction("Demander une série", Icons.Filled.Edit, 0, onRequest),
        DockAction("Téléchargements", XIcons.Download, activeDownloads, onDownloads),
        DockAction("Réglages", Icons.Filled.Settings, 0, onSettings),
    )
    // Position des icônes le long du fil (fraction de la demi-largeur, depuis le centre).
    val slots = floatArrayOf(-0.74f, -0.38f, 0.38f, 0.74f)
    val open = remember { List(4) { Animatable(0f) } }
    LaunchedEffect(expanded) {
        // Ouverture : de l'intérieur vers l'extérieur ; fermeture : tout rentre ensemble.
        val order = if (expanded) listOf(1, 2, 0, 3) else listOf(0, 1, 2, 3)
        order.forEachIndexed { k, i ->
            launch {
                if (expanded) delay(k * 45L)
                open[i].animateTo(
                    if (expanded) 1f else 0f,
                    if (expanded) spring(dampingRatio = 0.62f, stiffness = 260f) else tween(170),
                )
            }
        }
    }
    // Logo : petit enfoncement élastique à chaque toucher, et le cercle s'illumine quand c'est ouvert.
    val press = remember { Animatable(0f) }
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(expanded) {
        if (firstRun[0]) { firstRun[0] = false; return@LaunchedEffect }
        press.snapTo(1f)
        press.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 500f))
    }
    val glow by animateFloatAsState(if (expanded) 1f else 0f, tween(260), label = "glow")

    BoxWithConstraints(
        modifier.fillMaxWidth().height(DOCK_HEIGHT + inset)
            .graphicsLayer { alpha = shown; translationY = (1f - shown) * 40.dp.toPx() },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val insetPx = with(dens) { inset.toPx() }
        val baseY = h                                                       // la bosse part du bas de l'écran
        val peakY = h - maxOf(with(dens) { 24.dp.toPx() }, insetPx + with(dens) { 12.dp.toPx() }) // bosse basse
        val r = with(dens) { 21.dp.toPx() }                                 // rayon du cercle du logo
        val logoY = peakY - with(dens) { 5.dp.toPx() }                      // logo posé sur la pointe
        // Profil de la bosse : flancs creusés qui remontent en pointe vers le centre (u = 0 centre, 1 bord).
        fun curveY(u: Float) = baseY - (baseY - peakY) * (1f - u.coerceIn(0f, 1f)).pow(2.6f)

        // Voile sombre, plus léger qu'avant : le flou progressif fait l'essentiel.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x40000000), 1f to Color(0xCC000000)),
            ),
        )
        // Bosse noire, puis le cercle du logo.
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val half = size.width / 2f
            val mound = Path().apply {
                moveTo(0f, baseY)
                for (k in 0..60) {
                    val x = k / 60f * size.width
                    lineTo(x, curveY(abs(x - cx) / half))
                }
                lineTo(size.width, baseY)
                close()
            }
            // Bosse en noir pur, sans bordure ; légèrement translucide pour laisser deviner le flou derrière.
            drawPath(mound, Color(0xE0000000))
            drawCircle(Color(0xE6050506), r, Offset(cx, logoY))
            drawCircle(Color.White.copy(alpha = 0.35f + 0.35f * glow), r, Offset(cx, logoY), style = Stroke(1.2.dp.toPx()))
        }

        // Icônes : sortent du logo et glissent le long de la bosse, du flou au net.
        val lift = with(dens) { 23.dp.toPx() }
        val floor = h - insetPx - with(dens) { 25.dp.toPx() }
        actions.forEachIndexed { i, a ->
            val t = open[i].value
            if (t > 0.001f) {
                val u = abs(slots[i])
                val target = minOf(curveY(u) - lift, floor)
                val cy = logoY + (target - logoY) * t
                DockButton(
                    a, t,
                    Modifier.align(Alignment.TopCenter).offset {
                        IntOffset((slots[i] * (w / 2f) * t).roundToInt(), (cy - with(dens) { 20.dp.toPx() }).roundToInt())
                    },
                )
            }
        }

        // Logo xyd sur la pointe : ouvre / ferme (petit « clic » élastique, le cercle s'illumine).
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, (logoY - r).roundToInt()) }
                .size(42.dp).clip(CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle)
                .semantics { contentDescription = if (expanded) "Fermer le menu" else "Ouvrir le menu" },
            contentAlignment = Alignment.Center,
        ) {
            Logo(
                24.dp,
                Modifier.graphicsLayer {
                    val s = 1f - 0.16f * press.value
                    scaleX = s; scaleY = s
                },
            )
        }
    }
}

@Composable
private fun DockButton(a: DockAction, t: Float, modifier: Modifier) {
    val size: Dp = 40.dp
    Box(
        modifier.size(size)
            .graphicsLayer {
                alpha = t.coerceIn(0f, 1f)
                val s = 0.4f + 0.6f * t
                scaleX = s; scaleY = s
                // Apparition floue → nette (Android 12+).
                if (Build.VERSION.SDK_INT >= 31 && t < 0.98f) {
                    val b = (1f - t.coerceIn(0f, 1f)) * 10.dp.toPx()
                    if (b > 0.5f) renderEffect = androidx.compose.ui.graphics.BlurEffect(b, b, androidx.compose.ui.graphics.TileMode.Decal)
                }
            }
            .clip(CircleShape)
            .background(Color(0x26FFFFFF))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f))), CircleShape)
            .clickable(onClick = a.onClick)
            .semantics { contentDescription = a.label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(a.icon, null, tint = Color.White, modifier = Modifier.size(21.dp))
        if (a.badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(15.dp).clip(CircleShape).background(Xyd.Grey),
                contentAlignment = Alignment.Center,
            ) { Text("${a.badge}", color = Xyd.Black, fontSize = 9.sp) }
        }
    }
}

// ---------------------------------------------------------------- flou progressif du contenu

/**
 * Flou progressif appliqué au contenu de l'accueil, sur la bande du bas occupée par la barre :
 * nul en haut de la bande, maximal tout en bas. Android 13+ (shader) ; ailleurs, le verre sombre suffit.
 * `amount` : rayon maximal en px (0 = aucun effet).
 */
fun progressiveBlurEffect(width: Float, height: Float, band: Float, amount: Float): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < 33 || amount < 0.5f || width <= 0f || height <= 0f) return null
    return runCatching {
        val sh = android.graphics.RuntimeShader(PROGRESSIVE_BLUR)
        sh.setFloatUniform("size", width, height)
        sh.setFloatUniform("band", band)
        sh.setFloatUniform("amount", amount)
        android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content").asComposeRenderEffect()
    }.getOrNull()
}

private const val PROGRESSIVE_BLUR = """
uniform shader content;
uniform float2 size;
uniform float band;
uniform float amount;

half4 main(float2 p) {
    float start = size.y - band;
    if (p.y < start) return content.eval(p);
    float t = clamp((p.y - start) / band, 0.0, 1.0);
    float r = amount * t * t;
    if (r < 0.5) return content.eval(p);
    half4 c = content.eval(p) * 0.16;
    float wsum = 0.16;
    // Deux anneaux de 8 échantillons : flou doux et bon marché.
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        float2 d = float2(cos(a), sin(a));
        c += content.eval(p + d * r) * 0.06;
        c += content.eval(p + d * r * 0.5 + float2(-d.y, d.x) * r * 0.2) * 0.045;
        wsum += 0.105;
    }
    return c / wsum;
}
"""
