package io.benchbridge.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4355B9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E4FF),
    onPrimaryContainer = Color(0xFF233275),
    secondary = Color(0xFF48665F),
    secondaryContainer = Color(0xFFD1EDE3),
    onSecondaryContainer = Color(0xFF173E33),
    surface = Color(0xFFFAF8FF),
    surfaceContainer = Color(0xFFF0EFF7),
    surfaceContainerLow = Color(0xFFF5F3FC),
    onSurface = Color(0xFF1B1B24),
    onSurfaceVariant = Color(0xFF454652),
    outlineVariant = Color(0xFFD8D8E5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBBC4FF),
    onPrimary = Color(0xFF182878),
    primaryContainer = Color(0xFF303F98),
    onPrimaryContainer = Color(0xFFE0E4FF),
    secondary = Color(0xFFABD0C3),
    secondaryContainer = Color(0xFF294C41),
    onSecondaryContainer = Color(0xFFD1EDE3),
    surface = Color(0xFF11131C),
    surfaceContainer = Color(0xFF1D202B),
    surfaceContainerLow = Color(0xFF181B25),
    onSurface = Color(0xFFE5E1ED),
    onSurfaceVariant = Color(0xFFC7C5D4),
    outlineVariant = Color(0xFF454652),
)

@Composable
fun BenchBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
