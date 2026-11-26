package tech.xydhub.xyd.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tech.xydhub.xyd.data.CineEvent
import tech.xydhub.xyd.data.Repo
import tech.xydhub.xyd.download.DlStatus
import tech.xydhub.xyd.download.DownloadItem
import tech.xydhub.xyd.download.Downloads
import tech.xydhub.xyd.events.EventReminder
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Section « Soirée ciné » de l'accueil :
 *  - fond liquide bleu et noir animé (shader), fondu dans le noir sur tous les bords ;
 *  - titre en métal liquide ;
 *  - cadre holographique : couverture de l'événement, date, compte à rebours, programme ;
 *  - participants (pastilles qui se chevauchent + « +N ») ;
 *  - bouton « Je participe » en métal liquide + pastille de téléchargement.
 * Contenu de la soirée : téléchargé tout seul après l'inscription (Wi-Fi seulement si réglé),
 * hors quota personnel. Pendant la séance : on rejoint au moment en cours, comme au cinéma.
 */

private val Electric = Color(0xFF2F5BFF)

@Composable
fun EventSection(e: CineEvent, downloads: Map<String, DownloadItem>, onPlay: (String) -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val playable = e.mediaId?.let { Repo.playable(it) }
    val dl = e.mediaId?.let { downloads[it] }
    val ready = e.mediaId != null && Downloads.isReady(e.mediaId)
    val live = e.live(now)
    val wifiOnly by Repo.wifiOnly.collectAsStateWithLifecycle()

    // Notifications (Android 13+) : demandées au moment de l'inscription, pour les rappels.
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Téléchargement automatique du contenu de la soirée, une fois inscrit.
    LaunchedEffect(e.joined, e.mediaId, playable != null, dl?.status) {
        if (e.joined && playable != null && dl == null && !(wifiOnly && Downloads.isMetered(ctx))) {
            Downloads.enqueue(ctx, playable)
        }
    }

    fun join() {
        if (e.joined) {
            EventReminder.cancel(ctx, e)
            Repo.setJoined(e, false)
            return
        }
        Repo.setJoined(e, true)
        EventReminder.schedule(ctx, e)
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun watch(fromStart: Boolean) {
        val p = playable ?: return
        if (!ready) return
        val dur = (p.durationS * 1000L).coerceAtLeast(1L)
        val at = if (fromStart) 0L else (now - e.startsAt).coerceIn(0L, dur - 5_000L)
        Repo.saveProgress(p.mediaId, at.coerceAtLeast(1L), dur)
        onPlay(p.mediaId)
    }

    Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        LiquidBackground(Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 34.dp)) {
            Text(
                if (live) "● EN DIRECT · SOIRÉE CINÉ" else "SOIRÉE CINÉ",
                color = if (live) Color(0xFFFF5A5F) else Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.5.sp,
            )
            LiquidMetalText(e.title, Modifier.padding(top = 6.dp))

            HoloFrame(Modifier.padding(top = 18.dp).fillMaxWidth()) {
                Column {
                    // Couverture de l'événement (sinon l'image du contenu projeté).
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))) {
                        RemoteImage(e.cover ?: playable?.title?.backdrop ?: playable?.title?.poster, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE6050816))))
                        Text(
                            countdown(e, now),
                            Modifier.align(Alignment.BottomStart).padding(14.dp),
                            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(dateLabel(e.startsAt), color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, letterSpacing = 1.2.sp)
                        playable?.let {
                            Text(
                                "Au programme : " + it.title.name + (if (it.episode != null) " · " + it.shortLabel else ""),
                                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (e.description.isNotBlank()) {
                            Text(
                                e.description, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 17.sp,
                                modifier = Modifier.padding(top = 6.dp), maxLines = 4, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // Participants.
            Row(Modifier.padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarGroup(e.people, e.count)
                Text(
                    when (e.count) { 0 -> "Sois le premier à participer"; 1 -> "1 participant"; else -> "${e.count} participants" },
                    color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp),
                )
            }

            // Actions.
            Row(Modifier.padding(top = 18.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val label = when {
                    live && e.joined && ready -> "Rejoindre la séance"
                    e.joined -> "Inscrit ✓"
                    else -> "Je participe"
                }
                LiquidMetalButton(label, Modifier.weight(1f)) {
                    if (live && e.joined && ready) watch(fromStart = false) else join()
                }
                Spacer(Modifier.width(12.dp))
                DownloadOrb(dl, ready) {
                    // Téléchargement immédiat, même en données mobiles (choix explicite).
                    if (playable != null && !ready) Downloads.enqueue(ctx, playable)
                }
            }

            // Ligne d'état sous les boutons.
            val status = when {
                !e.joined -> "Inscris-toi : le film se télécharge tout seul avant la séance, sans toucher à ton quota."
                ready && live -> "Tu rejoins au moment en cours. Ou regarde depuis le début →"
                ready -> "Prêt pour la séance ✓ · Touche « Inscrit » pour te désinscrire"
                dl?.status == DlStatus.QUEUED || dl?.status == DlStatus.RUNNING -> "Téléchargement en cours · ${pct(dl)} %"
                dl?.status == DlStatus.PAUSED || dl?.status == DlStatus.FAILED -> "Téléchargement interrompu : touche ⤓ pour reprendre"
                wifiOnly && Downloads.isMetered(ctx) && e.startsAt - now < 3 * 3600_000L ->
                    "Pas encore téléchargé : touche ⤓ pour le faire maintenant avec tes données (${mb(playable?.sizeBytes)})"
                wifiOnly && Downloads.isMetered(ctx) -> "Se téléchargera dès que tu seras en Wi-Fi"
                else -> "Téléchargement en préparation…"
            }
            Text(
                status, color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 10.dp).then(
                    if (ready && live) Modifier.clickable { watch(fromStart = true) } else Modifier,
                ),
            )
        }
    }
}

private fun pct(d: DownloadItem): Int = if (d.sizeBytes > 0) ((d.downloaded * 100) / d.sizeBytes).toInt().coerceIn(0, 100) else 0
private fun mb(b: Long?): String = if (b == null || b <= 0) "?" else if (b >= 1_000_000_000) "%.1f Go".format(b / 1e9) else "${b / 1_000_000} Mo"

private fun dateLabel(t: Long): String =
    SimpleDateFormat("EEEE d MMMM · HH'h'mm", Locale.FRENCH).format(t).replaceFirstChar { it.uppercase() }

private fun countdown(e: CineEvent, now: Long): String {
    if (e.live(now)) {
        val m = (now - e.startsAt) / 60_000
        return "En cours · %d h %02d".format(m / 60, m % 60)
    }
    var s = ((e.startsAt - now) / 1000).coerceAtLeast(0)
    val d = s / 86400; s %= 86400
    val h = s / 3600; s %= 3600
    val m = s / 60; val sec = s % 60
    return when {
        d > 0 -> "Dans $d j ${h} h %02d".format(m)
        h > 0 -> "Dans $h h %02d min".format(m)
        else -> "Dans %d:%02d".format(m, sec)
    }
}

// ---------------------------------------------------------------- fond liquide

@Composable
private fun LiquidBackground(modifier: Modifier) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { time = ((it - start) / 1e9f) % 3600f }
    }
    val sh = remember { if (Build.VERSION.SDK_INT >= 33) runCatching { LiquidShader() }.getOrNull() else null }
    Canvas(modifier) {
        drawRect(Color.Black)
        if (sh != null) {
            drawRect(sh.brush(size.width, size.height, time, density))
        } else {
            // Repli : nappes bleues qui dérivent, fondues dans le noir.
            for (i in 0 until 4) {
                val a = time * 0.15f + i * 1.7f
                val c = Offset(size.width * (0.5f + 0.35f * cos(a)), size.height * (0.5f + 0.3f * sin(a * 1.3f)))
                drawCircle(
                    Brush.radialGradient(listOf(Electric.copy(alpha = 0.55f), Color.Transparent), center = c, radius = size.width * 0.6f),
                    radius = size.width * 0.6f, center = c,
                )
            }
            drawRect(Brush.verticalGradient(0f to Color.Black, 0.15f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color.Black))
            drawRect(Brush.horizontalGradient(0f to Color.Black, 0.18f to Color.Transparent, 0.82f to Color.Transparent, 1f to Color.Black))
        }
    }
}

@androidx.annotation.RequiresApi(33)
private class LiquidShader {
    private val shader = android.graphics.RuntimeShader(LIQUID_AGSL)
    private val brush = ShaderBrush(shader)
    fun brush(w: Float, h: Float, t: Float, density: Float): Brush {
        shader.setFloatUniform("res", w, h)
        shader.setFloatUniform("t", t)
        shader.setFloatUniform("scale", 1f / (200f * density))
        return brush
    }
}

private const val LIQUID_AGSL = """
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
    for (int i = 0; i < 3; i++) {
        v += a * noise(p);
        p = float2(1.6 * p.x + 1.2 * p.y, -1.2 * p.x + 1.6 * p.y) + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fc) {
    float2 p = fc * scale;
    float2 q = float2(fbm(p + t * 0.05), fbm(p + float2(5.2, 1.3) - t * 0.04));
    float2 r = float2(fbm(p + 2.2 * q + float2(1.7, 9.2) + t * 0.07),
                      fbm(p + 2.2 * q + float2(8.3, 2.8) - t * 0.06));
    float f = fbm(p + 2.4 * r);
    float v = smoothstep(0.40, 0.62, f);
    half3 col = half3(0.0, 0.25, 1.0) * half(v) + half3(0.35, 0.55, 1.0) * half(pow(v, 4.0) * 0.35);
    // Noir tout autour : aucune limite visible entre le bleu et la page.
    float2 uv = fc / res;
    float e = smoothstep(0.0, 0.2, uv.x) * smoothstep(0.0, 0.2, 1.0 - uv.x)
            * smoothstep(0.0, 0.12, uv.y) * smoothstep(0.0, 0.18, 1.0 - uv.y);
    col *= half(e * 0.8);
    return half4(col, 1.0);
}
"""

// ---------------------------------------------------------------- titre en métal liquide

@Composable
private fun LiquidMetalText(text: String, modifier: Modifier = Modifier) {
    val t by rememberInfiniteTransition(label = "metal").animateFloat(
        0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "metal-t",
    )
    // Reflets chromés qui coulent le long des lettres.
    val shift = t * 900f
    val brush = Brush.linearGradient(
        colors = listOf(Color(0xFF8E9AAF), Color.White, Color(0xFF5E6A80), Color(0xFFDCE4F5), Color(0xFF7D8AA3), Color.White, Color(0xFF8E9AAF)),
        start = Offset(shift - 900f, 0f), end = Offset(shift, 260f), tileMode = TileMode.Mirror,
    )
    Text(
        text, modifier,
        style = TextStyle(brush = brush, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
        maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

// ---------------------------------------------------------------- cadre holographique

@Composable
private fun HoloFrame(modifier: Modifier, content: @Composable () -> Unit) {
    val t by rememberInfiniteTransition(label = "holo").animateFloat(
        0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "holo-t",
    )
    val shape = RoundedCornerShape(18.dp)
    val holo = listOf(
        Color(0xFFB9C4D6), Color(0xFFFFB3C7), Color(0xFFFFE29A), Color(0xFFA8F0D1), Color(0xFF9EC9FF), Color(0xFFD7B8FF), Color(0xFFB9C4D6),
    )
    Box(
        modifier.clip(shape).background(Color(0xCC07090F))
            .drawWithContent {
                drawContent()
                // Reflet irisé qui balaie la carte en diagonale.
                val x = (t * 2.4f - 0.7f) * size.width
                drawRect(
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.45f to Color(0x14FFFFFF),
                        0.5f to Color(0x24C8D8FF),
                        0.55f to Color(0x14FFD9E8),
                        1f to Color.Transparent,
                        start = Offset(x - size.width * 0.5f, 0f), end = Offset(x + size.width * 0.5f, size.height),
                    ),
                )
            }
            .border(
                1.4.dp,
                Brush.sweepGradient(holo.map { it.copy(alpha = 0.9f) }.rotate((t * holo.size).toInt())),
                shape,
            ),
    ) { content() }
}

private fun <T> List<T>.rotate(n: Int): List<T> = if (isEmpty()) this else (indices).map { this[(it + n) % size] }

// ---------------------------------------------------------------- participants

@Composable
private fun AvatarGroup(people: List<tech.xydhub.xyd.data.Person>, count: Int) {
    val shown = people.take(4)
    val extra = (count - shown.size).coerceAtLeast(0)
    val size = 34.dp
    val step = 22.dp
    val n = shown.size + if (extra > 0) 1 else 0
    if (n == 0) return
    Box(Modifier.width(size + step * (n - 1)).height(size)) {
        shown.forEachIndexed { i, p ->
            UserAvatar(p.name, p.avatar, size, Modifier.offset(x = step * i))
        }
        if (extra > 0) {
            Box(
                Modifier.offset(x = step * shown.size).size(size).clip(CircleShape).background(Color(0xFF15161A))
                    .border(2.dp, Color.Black, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("+$extra", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Pastille de profil : la photo si l'utilisateur en a mis une, sinon son initiale sur un dégradé. */
@Composable
fun UserAvatar(name: String, avatar: String?, size: Dp, modifier: Modifier = Modifier, ring: Color = Color.Black) {
    val hue = (name.lowercase().hashCode() and 0x7fffffff) % 360
    val c1 = Color.hsv(hue.toFloat(), 0.55f, 0.95f)
    val c2 = Color.hsv(((hue + 50) % 360).toFloat(), 0.7f, 0.55f)
    Box(
        modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(c1, c2)))
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifBlank { "?" }, color = Color.White,
            fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold,
        )
        if (avatar != null) RemoteImage(avatar, Modifier.fillMaxSize().clip(CircleShape), background = Color.Transparent)
    }
}

// ---------------------------------------------------------------- bouton métal liquide

@Composable
private fun LiquidMetalButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val t by rememberInfiniteTransition(label = "btn").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "btn-t",
    )
    val shape = RoundedCornerShape(50)
    Box(
        modifier.height(54.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF1B1C20), Color(0xFF050506))))
            .drawWithContent {
                drawContent()
                // Bord en métal liquide : reflets chromés qui tournent autour du bouton.
                val stroke = 2.dp.toPx()
                val r = size.height / 2f
                val a = t * 2f * PI.toFloat()
                drawRoundRect(
                    Brush.sweepGradient(
                        listOf(Color(0xFF3A3D45), Color.White, Color(0xFF5A6070), Color(0xFF1E2026), Color(0xFFCFD6E6), Color(0xFF3A3D45)),
                        Offset(center.x + cos(a) * size.width * 0.3f, center.y + sin(a) * size.height * 0.3f),
                    ),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                    style = Stroke(stroke),
                )
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    }
}

/** Pastille ronde : état du téléchargement du contenu de la soirée (anneau de progression). */
@Composable
private fun DownloadOrb(dl: DownloadItem?, ready: Boolean, onClick: () -> Unit) {
    val p = dl?.let { pct(it) / 100f } ?: 0f
    Box(
        Modifier.size(54.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color(0xFF24262C), Color(0xFF050506))))
            .border(1.5.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.8f), Color(0xFF3A3D45))), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (!ready && dl != null && p > 0f) {
            Canvas(Modifier.size(42.dp)) {
                drawArc(Electric, -90f, 360f * p, false, style = Stroke(3.dp.toPx()))
            }
        }
        Text(if (ready) "✓" else "⤓", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
