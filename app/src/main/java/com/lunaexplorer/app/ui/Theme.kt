package com.lunaexplorer.app.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import com.lunaexplorer.app.model.*

private data class AccentColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
)

private val LightAccents = mapOf(
    Accent.TEAL to AccentColors(Color(0xFF2F6F66), Color.White, Color(0xFFCFE7E1), Color(0xFF16352F),
        Color(0xFF4E635F), Color(0xFFD3E5E1), Color(0xFF223330)),
    Accent.INDIGO to AccentColors(Color(0xFF4E5D94), Color.White, Color(0xFFDCE1FF), Color(0xFF23305C),
        Color(0xFF5A5F71), Color(0xFFDEE1F4), Color(0xFF272A38)),
    Accent.VIOLET to AccentColors(Color(0xFF6A538F), Color.White, Color(0xFFEBDCFF), Color(0xFF321B57),
        Color(0xFF655A6F), Color(0xFFECDDF6), Color(0xFF30273A)),
    Accent.ROSE to AccentColors(Color(0xFF8E4A55), Color.White, Color(0xFFFFD9DD), Color(0xFF5A1C27),
        Color(0xFF75565A), Color(0xFFFFD9DD), Color(0xFF2C151A)),
    Accent.AMBER to AccentColors(Color(0xFF7A5900), Color.White, Color(0xFFFFE08C), Color(0xFF463100),
        Color(0xFF6C5D3F), Color(0xFFF5E0BB), Color(0xFF241A04)),
    Accent.GRAPHITE to AccentColors(Color(0xFF4F5B62), Color.White, Color(0xFFD3E0E7), Color(0xFF0C1D24),
        Color(0xFF566065), Color(0xFFDAE4E9), Color(0xFF131C20)),
)

private val DarkAccents = mapOf(
    Accent.TEAL to AccentColors(Color(0xFF93CFC4), Color(0xFF00382F), Color(0xFF23514A), Color(0xFFCFE7E1),
        Color(0xFFB4C9C4), Color(0xFF364B47), Color(0xFFD3E5E1)),
    Accent.INDIGO to AccentColors(Color(0xFFB7C3FF), Color(0xFF1E2C58), Color(0xFF37456F), Color(0xFFDCE1FF),
        Color(0xFFC2C6D8), Color(0xFF42465A), Color(0xFFDEE1F4)),
    Accent.VIOLET to AccentColors(Color(0xFFCFBCFF), Color(0xFF381E5C), Color(0xFF513B75), Color(0xFFEBDCFF),
        Color(0xFFD0C2DA), Color(0xFF4C4256), Color(0xFFECDDF6)),
    Accent.ROSE to AccentColors(Color(0xFFFFB2BC), Color(0xFF56111D), Color(0xFF71333E), Color(0xFFFFD9DD),
        Color(0xFFE4BDC1), Color(0xFF5B3E42), Color(0xFFFFD9DD)),
    Accent.AMBER to AccentColors(Color(0xFFE6C264), Color(0xFF3F2E00), Color(0xFF5B4200), Color(0xFFFFE08C),
        Color(0xFFD8C5A1), Color(0xFF534531), Color(0xFFF5E0BB)),
    Accent.GRAPHITE to AccentColors(Color(0xFFB7C6CE), Color(0xFF213036), Color(0xFF38444B), Color(0xFFD3E0E7),
        Color(0xFFBFC8CC), Color(0xFF3E484D), Color(0xFFDAE4E9)),
)

private fun lightScheme(accent: AccentColors) = lightColorScheme(
    primary = accent.primary, onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryContainer, onPrimaryContainer = accent.onPrimaryContainer,
    secondary = accent.secondary, onSecondary = Color.White,
    secondaryContainer = accent.secondaryContainer, onSecondaryContainer = accent.onSecondaryContainer,
    background = Color(0xFFF4F6F4), onBackground = Color(0xFF232A2E),
    surface = Color(0xFFF4F6F4), onSurface = Color(0xFF232A2E),
    surfaceContainerLowest = Color(0xFFFCFDFB), surfaceContainerLow = Color(0xFFEFF1EE),
    surfaceContainer = Color(0xFFE9ECE8), surfaceContainerHigh = Color(0xFFE3E7E2),
    surfaceContainerHighest = Color(0xFFDDE1DC), surfaceBright = Color(0xFFF7F9F6),
    surfaceDim = Color(0xFFD6DAD5),
    surfaceVariant = Color(0xFFE4E8E3), onSurfaceVariant = Color(0xFF565F65),
    outline = Color(0xFF7A848A), outlineVariant = Color(0xFFD1D7D2),
    error = Color(0xFF9B4A47), onError = Color.White,
    errorContainer = Color(0xFFF7DAD8), onErrorContainer = Color(0xFF48211F),
)

private fun darkScheme(accent: AccentColors) = darkColorScheme(
    primary = accent.primary, onPrimary = accent.onPrimary,
    primaryContainer = accent.primaryContainer, onPrimaryContainer = accent.onPrimaryContainer,
    secondary = accent.secondary, onSecondary = Color(0xFF1E262A),
    secondaryContainer = accent.secondaryContainer, onSecondaryContainer = accent.onSecondaryContainer,
    background = Color(0xFF14181B), onBackground = Color(0xFFD9E0E3),
    surface = Color(0xFF14181B), onSurface = Color(0xFFD9E0E3),
    surfaceContainerLowest = Color(0xFF0E1114), surfaceContainerLow = Color(0xFF191E21),
    surfaceContainer = Color(0xFF1D2327), surfaceContainerHigh = Color(0xFF262C31),
    surfaceContainerHighest = Color(0xFF31383D), surfaceBright = Color(0xFF373E43),
    surfaceDim = Color(0xFF14181B),
    surfaceVariant = Color(0xFF262C31), onSurfaceVariant = Color(0xFFAFB8BD),
    outline = Color(0xFF7E888E), outlineVariant = Color(0xFF343B40),
    error = Color(0xFFE8A9A5), onError = Color(0xFF4E1B19),
    errorContainer = Color(0xFF6B2E2B), onErrorContainer = Color(0xFFF7DAD8),
)

private fun seeded(seed: Int, dark: Boolean): AccentColors {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed, hsl)
    val hue = hsl[0]
    val saturation = hsl[1].coerceIn(0.18f, 0.62f)
    fun shade(lightness: Float, chroma: Float = saturation) =
        Color(ColorUtils.HSLToColor(floatArrayOf(hue, chroma, lightness)))
    return if (dark) AccentColors(
        primary = shade(0.72f), onPrimary = shade(0.14f, saturation * 0.8f),
        primaryContainer = shade(0.26f), onPrimaryContainer = shade(0.88f, saturation * 0.5f),
        secondary = shade(0.70f, saturation * 0.45f), secondaryContainer = shade(0.28f, saturation * 0.35f),
        onSecondaryContainer = shade(0.88f, saturation * 0.4f),
    ) else AccentColors(
        primary = shade(0.34f), onPrimary = Color.White,
        primaryContainer = shade(0.86f, saturation * 0.6f), onPrimaryContainer = shade(0.16f),
        secondary = shade(0.40f, saturation * 0.45f), secondaryContainer = shade(0.88f, saturation * 0.35f),
        onSecondaryContainer = shade(0.18f, saturation * 0.5f),
    )
}

@Composable
private fun rememberColorScheme(mode: ThemeMode, accent: Accent, seed: Int): ColorScheme {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val context = LocalContext.current
    return remember(mode, accent, seed, dark, context) {
        buildColorScheme(context, dark, accent, seed)
    }
}

private fun buildColorScheme(
    context: Context,
    dark: Boolean,
    accent: Accent,
    seed: Int,
): ColorScheme {
    if (accent == Accent.SYSTEM && Build.VERSION.SDK_INT >= 31) {
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    val colors = when {
        accent == Accent.CUSTOM -> seeded(seed, dark)
        else -> (if (dark) DarkAccents else LightAccents)
            .getValue(if (accent == Accent.SYSTEM) Accent.TEAL else accent)
    }
    return if (dark) darkScheme(colors) else lightScheme(colors)
}

@Composable
fun LunaTheme(
    mode: ThemeMode,
    accent: Accent,
    seed: Int,
    uiScale: Float,
    content: @Composable () -> Unit,
) {
    // UI zoom scales the density only; the system fontScale is kept.
    val base = LocalDensity.current
    val scaled = remember(base, uiScale) {
        if (uiScale == 1f) base else Density(base.density * uiScale, base.fontScale)
    }
    CompositionLocalProvider(LocalDensity provides scaled) {
        MaterialTheme(
            colorScheme = rememberColorScheme(mode, accent, seed),
            typography = LunaTypography,
            content = content,
        )
    }
}

private val LunaTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)
