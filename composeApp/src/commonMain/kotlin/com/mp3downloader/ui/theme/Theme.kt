package com.mp3downloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Theme identifiers ──────────────────────────────────────────────────
enum class AppTheme(val label: String, val icon: String) {
    DARK("Nocturno", "moon"),
    LIGHT("Claro", "sun"),
    NEON("Neón", "bolt"),
    MIDNIGHT("Medianoche", "star"),
    GLASS("Vidrio", "glass"),
    SAKURA("Sakura", "flower")
}

// ── Color palettes ─────────────────────────────────────────────────────

private val DarkPrimary = Color(0xFF1DB954)        // Spotify green
private val DarkOnPrimary = Color.Black
private val DarkContainer = Color(0xFF1AA34A)
private val DarkOnContainer = Color.White
private val DarkSecondary = Color(0xFFB3B3B3)
private val DarkOnSecondary = Color.Black
private val DarkSecondaryContainer = Color(0xFF2A2A2A)
private val DarkBackground = Color(0xFF0D0D0D)
private val DarkOnBackground = Color(0xFFE8E8E8)
private val DarkSurface = Color(0xFF1A1A1A)
private val DarkOnSurface = Color(0xFFE8E8E8)
private val DarkSurfaceVariant = Color(0xFF252525)
private val DarkOnSurfaceVariant = Color(0xFFA0A0A0)
private val DarkOutline = Color(0xFF3A3A3A)
private val DarkOutlineVariant = Color(0xFF2D2D2D)
private val DarkError = Color(0xFFFF5252)
private val DarkOnError = Color.White
private val DarkErrorContainer = Color(0xFF3D1111)
private val DarkTertiary = Color(0xFF7C4DFF)

private val LightPrimary = Color(0xFF1565C0)
private val LightOnPrimary = Color.White
private val LightContainer = Color(0xFFD6EAFF)
private val LightOnContainer = Color(0xFF001C3A)
private val LightSecondary = Color(0xFF545F70)
private val LightOnSecondary = Color.White
private val LightSecondaryContainer = Color(0xFFD8E3F8)
private val LightBackground = Color(0xFFF8FAFE)
private val LightOnBackground = Color(0xFF1A1C1E)
private val LightSurface = Color(0xFFFFFFFF)
private val LightOnSurface = Color(0xFF1A1C1E)
private val LightSurfaceVariant = Color(0xFFEDF1F6)
private val LightOnSurfaceVariant = Color(0xFF43474E)
private val LightOutline = Color(0xFFC3C7CF)
private val LightOutlineVariant = Color(0xFFE0E3EA)
private val LightError = Color(0xFFBA1A1A)
private val LightOnError = Color.White
private val LightErrorContainer = Color(0xFFFFDAD6)
private val LightTertiary = Color(0xFF6E56CF)

private val NeonPrimary = Color(0xFFFF006E)
private val NeonOnPrimary = Color.White
private val NeonContainer = Color(0xFF3D001A)
private val NeonOnContainer = Color(0xFFFFD9E3)
private val NeonSecondary = Color(0xFFFFD93D)
private val NeonOnSecondary = Color.Black
private val NeonSecondaryContainer = Color(0xFF3D3500)
private val NeonBackground = Color(0xFF0A0A14)
private val NeonOnBackground = Color(0xFFE8E6F0)
private val NeonSurface = Color(0xFF141420)
private val NeonOnSurface = Color(0xFFE8E6F0)
private val NeonSurfaceVariant = Color(0xFF1E1E30)
private val NeonOnSurfaceVariant = Color(0xFFB0ADC0)
private val NeonOutline = Color(0xFF3A3850)
private val NeonOutlineVariant = Color(0xFF2A2840)
private val NeonError = Color(0xFFFF4081)
private val NeonOnError = Color.White
private val NeonErrorContainer = Color(0xFF3D001A)
private val NeonTertiary = Color(0xFF00E5FF)

private val MidnightPrimary = Color(0xFF6C63FF)
private val MidnightOnPrimary = Color.White
private val MidnightContainer = Color(0xFF2B2670)
private val MidnightOnContainer = Color(0xFFE4E0FF)
private val MidnightSecondary = Color(0xFF90CAF9)
private val MidnightOnSecondary = Color(0xFF0D1B2A)
private val MidnightSecondaryContainer = Color(0xFF1B2838)
private val MidnightBackground = Color(0xFF0B0E17)
private val MidnightOnBackground = Color(0xFFE2E2EC)
private val MidnightSurface = Color(0xFF121622)
private val MidnightOnSurface = Color(0xFFE2E2EC)
private val MidnightSurfaceVariant = Color(0xFF1C2030)
private val MidnightOnSurfaceVariant = Color(0xFFA0A4B8)
private val MidnightOutline = Color(0xFF303548)
private val MidnightOutlineVariant = Color(0xFF242838)
private val MidnightError = Color(0xFFFF5252)
private val MidnightOnError = Color.White
private val MidnightErrorContainer = Color(0xFF3D1111)
private val MidnightTertiary = Color(0xFF00E5FF)

private val GlassPrimary = Color(0xFFCE93D8)
private val GlassOnPrimary = Color.Black
private val GlassContainer = Color(0xFF3D1844)
private val GlassOnContainer = Color(0xFFF3DFF5)
private val GlassSecondary = Color(0xFF9C27B0)
private val GlassOnSecondary = Color.White
private val GlassSecondaryContainer = Color(0xFF2A0033)
private val GlassBackground = Color(0xFF0E0E14)
private val GlassOnBackground = Color(0xFFE8E6EE)
private val GlassSurface = Color(0xFF181820)
private val GlassOnSurface = Color(0xFFE8E6EE)
private val GlassSurfaceVariant = Color(0xFF22222E)
private val GlassOnSurfaceVariant = Color(0xFFB0AEC0)
private val GlassOutline = Color(0xFF3A3848)
private val GlassOutlineVariant = Color(0xFF2A2838)
private val GlassError = Color(0xFFFF5252)
private val GlassOnError = Color.White
private val GlassErrorContainer = Color(0xFF3D1111)
private val GlassTertiary = Color(0xFF7C4DFF)

private val SakuraPrimary = Color(0xFFE91E63)
private val SakuraOnPrimary = Color.White
private val SakuraContainer = Color(0xFF4A0E22)
private val SakuraOnContainer = Color(0xFFFFD9E3)
private val SakuraSecondary = Color(0xFFF48FB1)
private val SakuraOnSecondary = Color.Black
private val SakuraSecondaryContainer = Color(0xFF3D1520)
private val SakuraBackground = Color(0xFF0E0A10)
private val SakuraOnBackground = Color(0xFFEDE8EE)
private val SakuraSurface = Color(0xFF18121A)
private val SakuraOnSurface = Color(0xFFEDE8EE)
private val SakuraSurfaceVariant = Color(0xFF221A22)
private val SakuraOnSurfaceVariant = Color(0xFFB0A0B0)
private val SakuraOutline = Color(0xFF3A2A3A)
private val SakuraOutlineVariant = Color(0xFF2A1E2A)
private val SakuraError = Color(0xFFFF5252)
private val SakuraOnError = Color.White
private val SakuraErrorContainer = Color(0xFF3D1111)
private val SakuraTertiary = Color(0xFFFF80AB)

// ── Color schemes ──────────────────────────────────────────────────────

val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkContainer,
    onPrimaryContainer = DarkOnContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    tertiary = DarkTertiary
)

val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightContainer,
    onPrimaryContainer = LightOnContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    tertiary = LightTertiary
)

val NeonColorScheme = darkColorScheme(
    primary = NeonPrimary,
    onPrimary = NeonOnPrimary,
    primaryContainer = NeonContainer,
    onPrimaryContainer = NeonOnContainer,
    secondary = NeonSecondary,
    onSecondary = NeonOnSecondary,
    secondaryContainer = NeonSecondaryContainer,
    background = NeonBackground,
    onBackground = NeonOnBackground,
    surface = NeonSurface,
    onSurface = NeonOnSurface,
    surfaceVariant = NeonSurfaceVariant,
    onSurfaceVariant = NeonOnSurfaceVariant,
    outline = NeonOutline,
    outlineVariant = NeonOutlineVariant,
    error = NeonError,
    onError = NeonOnError,
    errorContainer = NeonErrorContainer,
    tertiary = NeonTertiary
)

val MidnightColorScheme = darkColorScheme(
    primary = MidnightPrimary,
    onPrimary = MidnightOnPrimary,
    primaryContainer = MidnightContainer,
    onPrimaryContainer = MidnightOnContainer,
    secondary = MidnightSecondary,
    onSecondary = MidnightOnSecondary,
    secondaryContainer = MidnightSecondaryContainer,
    background = MidnightBackground,
    onBackground = MidnightOnBackground,
    surface = MidnightSurface,
    onSurface = MidnightOnSurface,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = MidnightOnSurfaceVariant,
    outline = MidnightOutline,
    outlineVariant = MidnightOutlineVariant,
    error = MidnightError,
    onError = MidnightOnError,
    errorContainer = MidnightErrorContainer,
    tertiary = MidnightTertiary
)

val GlassColorScheme = darkColorScheme(
    primary = GlassPrimary,
    onPrimary = GlassOnPrimary,
    primaryContainer = GlassContainer,
    onPrimaryContainer = GlassOnContainer,
    secondary = GlassSecondary,
    onSecondary = GlassOnSecondary,
    secondaryContainer = GlassSecondaryContainer,
    background = GlassBackground,
    onBackground = GlassOnBackground,
    surface = GlassSurface,
    onSurface = GlassOnSurface,
    surfaceVariant = GlassSurfaceVariant,
    onSurfaceVariant = GlassOnSurfaceVariant,
    outline = GlassOutline,
    outlineVariant = GlassOutlineVariant,
    error = GlassError,
    onError = GlassOnError,
    errorContainer = GlassErrorContainer,
    tertiary = GlassTertiary
)

val SakuraColorScheme = darkColorScheme(
    primary = SakuraPrimary,
    onPrimary = SakuraOnPrimary,
    primaryContainer = SakuraContainer,
    onPrimaryContainer = SakuraOnContainer,
    secondary = SakuraSecondary,
    onSecondary = SakuraOnSecondary,
    secondaryContainer = SakuraSecondaryContainer,
    background = SakuraBackground,
    onBackground = SakuraOnBackground,
    surface = SakuraSurface,
    onSurface = SakuraOnSurface,
    surfaceVariant = SakuraSurfaceVariant,
    onSurfaceVariant = SakuraOnSurfaceVariant,
    outline = SakuraOutline,
    outlineVariant = SakuraOutlineVariant,
    error = SakuraError,
    onError = SakuraOnError,
    errorContainer = SakuraErrorContainer,
    tertiary = SakuraTertiary
)

// ── Theme resolver ─────────────────────────────────────────────────────

fun colorSchemeFor(theme: AppTheme, isDark: Boolean) = when (theme) {
    AppTheme.LIGHT -> if (isDark) DarkColorScheme else LightColorScheme
    AppTheme.DARK -> DarkColorScheme
    AppTheme.NEON -> NeonColorScheme
    AppTheme.MIDNIGHT -> MidnightColorScheme
    AppTheme.GLASS -> GlassColorScheme
    AppTheme.SAKURA -> SakuraColorScheme
}

@Composable
fun Mp3DownloaderTheme(
    theme: AppTheme = AppTheme.DARK,
    isDark: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = colorSchemeFor(theme, isDark),
        typography = AppTypography,
        content = content
    )
}
