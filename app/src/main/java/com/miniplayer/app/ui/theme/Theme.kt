package com.miniplayer.app.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DefaultLightScheme = lightColorScheme(
    primary = Color(0xFF285EA7),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3E),
    secondary = Color(0xFF555F71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD9E3F8),
    onSecondaryContainer = Color(0xFF121C2B),
    tertiary = Color(0xFF6F5575),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF9D8FE),
    onTertiaryContainer = Color(0xFF28132E),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE7E8EE),
    surfaceContainerHighest = Color(0xFFE2E2E9),
)

private val DefaultDarkScheme = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00468C),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = Color(0xFFBDC7DC),
    onSecondary = Color(0xFF273141),
    secondaryContainer = Color(0xFF3E4758),
    onSecondaryContainer = Color(0xFFD9E3F8),
    tertiary = Color(0xFFDCBCE1),
    onTertiary = Color(0xFF3E2845),
    tertiaryContainer = Color(0xFF563E5C),
    onTertiaryContainer = Color(0xFFF9D8FE),
    // Google Material "Charcoal Dark" elevated surfaces (v1.0.7): lifts the in-app dark theme
    // off pure near-black so cards, sheets and the now-playing bar keep visible tonal separation.
    background = Color(0xFF1E1F24),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF1E1F24),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainerLowest = Color(0xFF181A1F),
    surfaceContainerLow = Color(0xFF26282F),
    surfaceContainer = Color(0xFF2B2E36),
    surfaceContainerHigh = Color(0xFF32353E),
    surfaceContainerHighest = Color(0xFF3B3E48),
)

/**
 * Shifts an Android 12+ dynamic (Material You) dark [ColorScheme] one tonal step brighter so the
 * in-app dark surfaces match the elevated "Charcoal Dark" look of [DefaultDarkScheme] instead of
 * the near-black `surface` the system palette generates by default (v1.0.7).
 */
private fun elevateDynamicDarkSurfaces(rawDark: ColorScheme): ColorScheme = rawDark.copy(
    background = rawDark.surfaceContainer,
    surface = rawDark.surfaceContainer,
    surfaceContainerLowest = rawDark.surface,
    surfaceContainerLow = rawDark.surfaceContainerHigh,
    surfaceContainer = rawDark.surfaceContainerHighest,
    surfaceContainerHigh = rawDark.surfaceBright,
)

val MiniPlayerTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 21.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = TextStyle(
        fontSize = 15.5.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    ),
)

/**
 * Forces OpenType tabular figures (`tnum`) on all timestamps (`02:14.2`), A/B coordinates,
 * playback speeds (`0.85x`), BPM (`120 BPM`), semitones (`-1 st`), and countdown timers so digits
 * never jitter horizontally during playback or fine-nudging.
 */
fun TextStyle.tnum(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
fun MiniPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                elevateDynamicDarkSurfaces(dynamicDarkColorScheme(context))
            } else {
                dynamicLightColorScheme(context)
            }
        }
        darkTheme -> DefaultDarkScheme
        else -> DefaultLightScheme
    }

    MaterialTheme(
        colorScheme = baseScheme,
        typography = MiniPlayerTypography,
        content = content,
    )
}

/**
 * Smoothly animates a scoped M3 [ColorScheme] over 350ms (`DESIGN.md` §3.1) for the full-screen
 * `NowPlayingSheet` when switching tracks with extracted album tonal palettes.
 */
@Composable
fun rememberAnimatedColorScheme(target: ColorScheme, durationMillis: Int = 350): ColorScheme {
    val spec = tween<Color>(durationMillis = durationMillis)
    val primary by animateColorAsState(target.primary, spec, label = "primary")
    val onPrimary by animateColorAsState(target.onPrimary, spec, label = "onPrimary")
    val primaryContainer by animateColorAsState(target.primaryContainer, spec, label = "primaryContainer")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, spec, label = "onPrimaryContainer")
    val secondary by animateColorAsState(target.secondary, spec, label = "secondary")
    val onSecondary by animateColorAsState(target.onSecondary, spec, label = "onSecondary")
    val secondaryContainer by animateColorAsState(target.secondaryContainer, spec, label = "secondaryContainer")
    val onSecondaryContainer by animateColorAsState(target.onSecondaryContainer, spec, label = "onSecondaryContainer")
    val tertiary by animateColorAsState(target.tertiary, spec, label = "tertiary")
    val onTertiary by animateColorAsState(target.onTertiary, spec, label = "onTertiary")
    val tertiaryContainer by animateColorAsState(target.tertiaryContainer, spec, label = "tertiaryContainer")
    val onTertiaryContainer by animateColorAsState(target.onTertiaryContainer, spec, label = "onTertiaryContainer")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val onSurface by animateColorAsState(target.onSurface, spec, label = "onSurface")
    val surfaceVariant by animateColorAsState(target.surfaceVariant, spec, label = "surfaceVariant")
    val onSurfaceVariant by animateColorAsState(target.onSurfaceVariant, spec, label = "onSurfaceVariant")
    val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, spec, label = "surfaceContainerLow")
    val surfaceContainer by animateColorAsState(target.surfaceContainer, spec, label = "surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, spec, label = "surfaceContainerHigh")
    val surfaceContainerHighest by animateColorAsState(target.surfaceContainerHighest, spec, label = "surfaceContainerHighest")

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = surface,
        onBackground = onSurface,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
    )
}
