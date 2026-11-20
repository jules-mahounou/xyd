package tech.xydhub.xyd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.xydhub.xyd.data.Title
import java.text.Normalizer

/*
 * Recherche (loupe de l'en-tête de l'accueil) : champ en verre, clavier ouvert,
 * résultats pendant la frappe. Cherche dans les noms, les noms d'épisodes et les
 * résumés, sans tenir compte des accents ni des majuscules. Vide : derniers ajouts.
 */

private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

/** Un résultat : le titre, et pourquoi il remonte (pour l'afficher). */
private data class Hit(val title: Title, val score: Int, val why: String?)

private fun search(catalog: List<Title>, query: String): List<Hit> {
    val q = norm(query).trim()
    if (q.isEmpty()) return emptyList()
    val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
    return catalog.mapNotNull { t ->
        val name = norm(t.name)
        val score = when {
            name == q -> 100
            name.startsWith(q) -> 80
            name.split(Regex("[^a-z0-9]+")).any { it.startsWith(q) } -> 65
            name.contains(q) -> 55
            words.all { name.contains(it) } -> 45
            else -> 0
        }
        if (score > 0) return@mapNotNull Hit(t, score + (if (t.year > 0) 0 else 0), null)
        // Épisode dont le nom correspond.
        for (s in t.seasons) for (e in s.episodes) {
            if (norm(e.name).contains(q)) return@mapNotNull Hit(t, 35, "S${s.number} · É${e.number} — ${e.name}")
        }
        if (words.all { norm(t.synopsis).contains(it) }) Hit(t, 20, "Dans le résumé") else null
    }.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.title.createdAt })
}

@Composable
fun SearchSheet(catalog: List<Title>, onOpen: (Title) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(catalog, query) { search(catalog, query) }
    val recent = remember(catalog) { catalog.sortedByDescending { it.createdAt }.take(8) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    XSheet(onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            // Champ en verre (même liseré / reflet que les filtres).
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.07f))
                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.08f), 0.5f to Color.Transparent))
                    .border(1.dp, Color.White.copy(alpha = if (query.isEmpty()) 0.14f else 0.5f), RoundedCornerShape(50))
                    .padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Xyd.Muted, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Série, film, épisode…", color = Xyd.Muted, fontSize = 15.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = TextStyle(color = Xyd.Text, fontSize = 15.sp),
                        cursorBrush = SolidColor(Color(0xFF63D2FF)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Effacer", tint = Xyd.Muted, modifier = Modifier.size(18.dp)) }
                }
            }

            val list = if (query.isBlank()) recent.map { Hit(it, 0, null) } else hits
            Text(
                when {
                    query.isBlank() -> "Derniers ajouts"
                    hits.isEmpty() -> "Aucun résultat pour « ${query.trim()} »"
                    else -> "${hits.size} résultat${if (hits.size > 1) "s" else ""}"
                },
                color = Xyd.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
                modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
            )
            if (query.isNotBlank() && hits.isEmpty()) {
                Text(
                    "Il te manque ce titre ? Demande-le avec le crayon en haut de l'accueil.",
                    color = Xyd.Muted, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.title.id }) { h -> ResultRow(h, query) { keyboard?.hide(); onOpen(h.title) } }
            }
        }
    }
}

@Composable
private fun ResultRow(h: Hit, query: String, onClick: () -> Unit) {
    val t = h.title
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(54.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            RemoteImage(t.poster ?: t.backdrop, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(highlight(t.name, query), color = Xyd.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                if (t.isFilm) "Film" else "Série",
                t.year.takeIf { it > 0 }?.toString(),
                if (!t.isFilm && t.seasons.isNotEmpty()) "${t.seasons.size} saison${if (t.seasons.size > 1) "s" else ""}" else null,
                if (t.isFilm) fmtDuration(t.durationS).ifBlank { null } else null,
            )
            Text(meta.joinToString(" · "), color = Xyd.Muted, fontSize = 12.sp, maxLines = 1)
            h.why?.let { Text(it, color = Color(0xFF63D2FF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Met en évidence la partie du nom qui correspond à la recherche (sans tenir compte des accents). */
private fun highlight(name: String, query: String) = buildAnnotatedString {
    val q = norm(query).trim()
    val i = if (q.isEmpty()) -1 else norm(name).indexOf(q)
    if (i < 0 || norm(name).length != name.length) { append(name); return@buildAnnotatedString }
    append(name.substring(0, i))
    withStyle(SpanStyle(color = Color(0xFF63D2FF), fontWeight = FontWeight.Bold)) { append(name.substring(i, i + q.length)) }
    append(name.substring(i + q.length))
}
