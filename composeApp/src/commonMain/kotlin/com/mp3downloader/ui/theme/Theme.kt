package com.mp3downloader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF1DB954),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1AA34A),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFB3B3B3),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF333333),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE1E1E1),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE1E1E1),
    surfaceVariant = Color(0xFF282828),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF535353),
    outlineVariant = Color(0xFF404040),
    error = Color(0xFFE74C3C),
    onError = Color.White,
    errorContainer = Color(0xFF4A1C1C),
    inverseSurface = Color(0xFFE1E1E1),
    inverseOnSurface = Color(0xFF121212),
    surfaceTint = Color(0xFF1DB954)
)

@Composable
fun Mp3DownloaderTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
