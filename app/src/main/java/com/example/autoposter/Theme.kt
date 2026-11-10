package com.example.autoposter

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** True when the dark palette is active (system, light and dark modes resolve to this). */
val LocalIsDark = staticCompositionLocalOf { true }

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7C6CF2), onPrimary = Color(0xFF0B1020),
    primaryContainer = Color(0xFF2A2563), onPrimaryContainer = Color(0xFFE6E2FF),
    secondary = Color(0xFF43D9B7), onSecondary = Color(0xFF062B24),
    secondaryContainer = Color(0xFF123A38), onSecondaryContainer = Color(0xFFC2F5EA),
    tertiary = Color(0xFFFF9D7D), onTertiary = Color(0xFF3A1407),
    tertiaryContainer = Color(0xFF4A2418), onTertiaryContainer = Color(0xFFFFDACE),
    background = Color(0xFF0B1020), onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF141B2D), onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1E2740), onSurfaceVariant = Color(0xFFB9C1DA),
    surfaceTint = Color(0xFF7C6CF2), outline = Color(0xFF7D86A3), outlineVariant = Color(0xFF2B3452),
    error = Color(0xFFFF6B7A), onError = Color(0xFF3A0008), errorContainer = Color(0xFF5A1620), onErrorContainer = Color(0xFFFFD9DD),
    surfaceContainerLowest = Color(0xFF080C19), surfaceContainerLow = Color(0xFF101728), surfaceContainer = Color(0xFF141B2D),
    surfaceContainerHigh = Color(0xFF1A2236), surfaceContainerHighest = Color(0xFF212A42)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B4DE2), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E0FF), onPrimaryContainer = Color(0xFF1B1262),
    secondary = Color(0xFF0B7A67), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDF5EC), onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFFC2512F), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDBD0), onTertiaryContainer = Color(0xFF3B0F03),
    background = Color(0xFFF6F7FB), onBackground = Color(0xFF0B1020),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0B1020),
    surfaceVariant = Color(0xFFECEEF6), onSurfaceVariant = Color(0xFF4A5170),
    surfaceTint = Color(0xFF5B4DE2), outline = Color(0xFF7A819C), outlineVariant = Color(0xFFD9DDEA),
    error = Color(0xFFBA1A2A), onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDADD), onErrorContainer = Color(0xFF410007),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF9FAFD), surfaceContainer = Color(0xFFF2F3F9),
    surfaceContainerHigh = Color(0xFFECEEF6), surfaceContainerHighest = Color(0xFFE6E9F3)
)

private val Sans = FontFamily.SansSerif

val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.ExtraBold, fontSize = 27.sp, lineHeight = 33.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp)
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp)
)

@Composable
private fun ColorScheme.animated(): ColorScheme {
    @Composable fun a(c: Color): Color = animateColorAsState(c, tween(320), label = "scheme").value
    return copy(
        primary = a(primary), onPrimary = a(onPrimary), primaryContainer = a(primaryContainer), onPrimaryContainer = a(onPrimaryContainer),
        secondary = a(secondary), onSecondary = a(onSecondary), secondaryContainer = a(secondaryContainer), onSecondaryContainer = a(onSecondaryContainer),
        tertiary = a(tertiary), onTertiary = a(onTertiary), tertiaryContainer = a(tertiaryContainer), onTertiaryContainer = a(onTertiaryContainer),
        background = a(background), onBackground = a(onBackground), surface = a(surface), onSurface = a(onSurface),
        surfaceVariant = a(surfaceVariant), onSurfaceVariant = a(onSurfaceVariant), outline = a(outline), outlineVariant = a(outlineVariant),
        error = a(error), onError = a(onError), errorContainer = a(errorContainer), onErrorContainer = a(onErrorContainer),
        surfaceContainerLowest = a(surfaceContainerLowest), surfaceContainerLow = a(surfaceContainerLow), surfaceContainer = a(surfaceContainer),
        surfaceContainerHigh = a(surfaceContainerHigh), surfaceContainerHighest = a(surfaceContainerHighest)
    )
}

@Composable
fun AutoPosterTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val scheme = (if (dark) DarkScheme else LightScheme).animated()
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(scheme.background.toArgb()))
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
    }
}
