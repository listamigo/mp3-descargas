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

// ══════════════════════════════════════════════════════════════════════════
//  DARK  —  Rojo Granate Intenso (ladrillo profundo, ni pastel ni vibrante)
// ══════════════════════════════════════════════════════════════════════════
private val DarkPrimary      = Color(0xFFA63D2A)
private val DarkOnPrimary    = Color.White
private val DarkContainer    = Color(0xFF3D1008)
private val DarkOnContainer  = Color(0xFFFFD4C4)
private val DarkSecondary      = Color(0xFFA0A0A0)
private val DarkOnSecondary    = Color.Black
private val DarkSecondaryContainer = Color(0xFF2D2D2D)
private val DarkBackground   = Color(0xFF121212)
private val DarkOnBackground = Color(0xFFE1E1E1)
private val DarkSurface      = Color(0xFF1E1E1E)
private val DarkOnSurface    = Color(0xFFE1E1E1)
private val DarkSurfaceVariant    = Color(0xFF282828)
private val DarkOnSurfaceVariant  = Color(0xFFB3B3B3)
private val DarkOutline      = Color(0xFF404040)
private val DarkOutlineVariant    = Color(0xFF333333)
private val DarkError        = Color(0xFFE74C3C)
private val DarkOnError      = Color.White
private val DarkErrorContainer    = Color(0xFF3D1111)
private val DarkTertiary     = Color(0xFF4FC3B7)

// ══════════════════════════════════════════════════════════════════════════
//  LIGHT  —  Burnt Sienna Intenso (ocre tostado profundo)
// ══════════════════════════════════════════════════════════════════════════
private val LightPrimary      = Color(0xFFC94A20)
private val LightOnPrimary    = Color.White
private val LightContainer    = Color(0xFFFFE0CC)
private val LightOnContainer  = Color(0xFF3D1A0A)
private val LightSecondary      = Color(0xFF757575)
private val LightOnSecondary    = Color.White
private val LightSecondaryContainer = Color(0xFFE8E8E8)
private val LightBackground   = Color(0xFFFFFFFF)
private val LightOnBackground = Color(0xFF1A1A1A)
private val LightSurface      = Color(0xFFF7F5F3)
private val LightOnSurface    = Color(0xFF1A1A1A)
private val LightSurfaceVariant    = Color(0xFFEDE8E4)
private val LightOnSurfaceVariant  = Color(0xFF666666)
private val LightOutline      = Color(0xFFCCCCCC)
private val LightOutlineVariant    = Color(0xFFE0E0E0)
private val LightError        = Color(0xFFD32F2F)
private val LightOnError      = Color.White
private val LightErrorContainer    = Color(0xFFFFDAD6)
private val LightTertiary     = Color(0xFF3D8C7A)

// ══════════════════════════════════════════════════════════════════════════
//  NEON  —  Deep Violet (violeta intenso)
// ══════════════════════════════════════════════════════════════════════════
private val NeonPrimary      = Color(0xFF8C60C8)
private val NeonOnPrimary    = Color.White
private val NeonContainer    = Color(0xFF2D1055)
private val NeonOnContainer  = Color(0xFFE0CCFF)
private val NeonSecondary      = Color(0xFFFFD93D)
private val NeonOnSecondary    = Color.Black
private val NeonSecondaryContainer = Color(0xFF3D3500)
private val NeonBackground   = Color(0xFF0A0A14)
private val NeonOnBackground = Color(0xFFE8E6F0)
private val NeonSurface      = Color(0xFF12101E)
private val NeonOnSurface    = Color(0xFFE8E6F0)
private val NeonSurfaceVariant    = Color(0xFF1E1E30)
private val NeonOnSurfaceVariant  = Color(0xFFB0ADC0)
private val NeonOutline      = Color(0xFF3A3850)
private val NeonOutlineVariant    = Color(0xFF2A2840)
private val NeonError        = Color(0xFFFF4081)
private val NeonOnError      = Color.White
private val NeonErrorContainer    = Color(0xFF3D001A)
private val NeonTertiary     = Color(0xFF00E5FF)

// ══════════════════════════════════════════════════════════════════════════
//  MIDNIGHT  —  Steel Blue Intenso (azul acero profundo)
// ══════════════════════════════════════════════════════════════════════════
private val MidnightPrimary      = Color(0xFF3578C8)
private val MidnightOnPrimary    = Color.White
private val MidnightContainer    = Color(0xFF0D2055)
private val MidnightOnContainer  = Color(0xFFC8E0FF)
private val MidnightSecondary      = Color(0xFF90CAF9)
private val MidnightOnSecondary    = Color(0xFF0D1B2A)
private val MidnightSecondaryContainer = Color(0xFF1B2838)
private val MidnightBackground   = Color(0xFF0B0E17)
private val MidnightOnBackground = Color(0xFFE2E2EC)
private val MidnightSurface      = Color(0xFF10151F)
private val MidnightOnSurface    = Color(0xFFE2E2EC)
private val MidnightSurfaceVariant    = Color(0xFF1C2030)
private val MidnightOnSurfaceVariant  = Color(0xFFA0A4B8)
private val MidnightOutline      = Color(0xFF303548)
private val MidnightOutlineVariant    = Color(0xFF242838)
private val MidnightError        = Color(0xFFFF5252)
private val MidnightOnError      = Color.White
private val MidnightErrorContainer    = Color(0xFF3D1111)
private val MidnightTertiary     = Color(0xFF4DD0B7)

// ══════════════════════════════════════════════════════════════════════════
//  GLASS  —  Deep Lavender (púrpura lavanda intenso)
// ══════════════════════════════════════════════════════════════════════════
private val GlassPrimary      = Color(0xFF7860C8)
private val GlassOnPrimary    = Color.White
private val GlassContainer    = Color(0xFF2A1055)
private val GlassOnContainer  = Color(0xFFD4C8FF)
private val GlassSecondary      = Color(0xFFA0A0C0)
private val GlassOnSecondary    = Color.Black
private val GlassSecondaryContainer = Color(0xFF2A2A3E)
private val GlassBackground   = Color(0xFF121212)
private val GlassOnBackground = Color(0xFFE1E1E1)
private val GlassSurface      = Color(0xFF18182A)
private val GlassOnSurface    = Color(0xFFE1E1E1)
private val GlassSurfaceVariant    = Color(0xFF22223E)
private val GlassOnSurfaceVariant  = Color(0xFFB0AEC8)
private val GlassOutline      = Color(0xFF3A3850)
private val GlassOutlineVariant    = Color(0xFF2A2840)
private val GlassError        = Color(0xFFCF6679)
private val GlassOnError      = Color.Black
private val GlassErrorContainer    = Color(0xFF3D001A)
private val GlassTertiary     = Color(0xFFFFD54F)

// ══════════════════════════════════════════════════════════════════════════
//  SAKURA  —  Rosewood Intenso (rosa cerezo profundo)
// ══════════════════════════════════════════════════════════════════════════
private val SakuraPrimary      = Color(0xFFC86080)
private val SakuraOnPrimary    = Color.White
private val SakuraContainer    = Color(0xFF3D0830)
private val SakuraOnContainer  = Color(0xFFFFD0DC)
private val SakuraSecondary      = Color(0xFFC4A0B0)
private val SakuraOnSecondary    = Color.Black
private val SakuraSecondaryContainer = Color(0xFF2D1A28)
private val SakuraBackground   = Color(0xFF1A1218)
private val SakuraOnBackground = Color(0xFFE1D8DC)
private val SakuraSurface      = Color(0xFF221820)
private val SakuraOnSurface    = Color(0xFFE1D8DC)
private val SakuraSurfaceVariant    = Color(0xFF2D242A)
private val SakuraOnSurfaceVariant  = Color(0xFFB8A8B0)
private val SakuraOutline      = Color(0xFF403840)
private val SakuraOutlineVariant    = Color(0xFF2D242A)
private val SakuraError        = Color(0xFFCF6679)
private val SakuraOnError      = Color.Black
private val SakuraErrorContainer    = Color(0xFF3D0020)
private val SakuraTertiary     = Color(0xFF7CC4A8)

// ══════════════════════════════════════════════════════════════════════════
//  Color Schemes
// ══════════════════════════════════════════════════════════════════════════

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
