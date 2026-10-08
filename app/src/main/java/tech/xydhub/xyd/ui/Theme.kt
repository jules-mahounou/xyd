package tech.xydhub.xyd.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Xyd {
    val Black = Color(0xFF000000)
    val Grey = Color(0xFFC6C6C6)
    val Surface = Color(0xFF0D0D0D)
    val Card = Color(0xFF161616)
    val Line = Color(0xFF262626)
    val Text = Color(0xFFEDEDED)
    val Muted = Color(0xFF8C8C8C)
    val Danger = Color(0xFFE5484D)
}

private val scheme = darkColorScheme(
    primary = Xyd.Grey,
    onPrimary = Xyd.Black,
    primaryContainer = Xyd.Card,
    onPrimaryContainer = Xyd.Text,
    secondary = Xyd.Grey,
    onSecondary = Xyd.Black,
    secondaryContainer = Xyd.Line,
    onSecondaryContainer = Xyd.Text,
    background = Xyd.Black,
    onBackground = Xyd.Text,
    surface = Xyd.Black,
    onSurface = Xyd.Text,
    surfaceVariant = Xyd.Card,
    onSurfaceVariant = Xyd.Muted,
    surfaceContainerLowest = Xyd.Black,
    surfaceContainerLow = Xyd.Surface,
    surfaceContainer = Xyd.Surface,
    surfaceContainerHigh = Xyd.Card,
    surfaceContainerHighest = Xyd.Line,
    outline = Xyd.Line,
    outlineVariant = Xyd.Line,
    error = Xyd.Danger,
)

@Composable
fun XydTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
