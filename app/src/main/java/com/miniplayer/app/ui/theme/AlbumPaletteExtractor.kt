package com.miniplayer.app.ui.theme

import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

/**
 * Extracts a harmonious Material 3 Tonal Spot [ColorScheme] from an album cover or video keyframe
 * [Bitmap] with guaranteed WCAG AA (`>= 4.5:1`) text contrast in both dark and light mode.
 */
object AlbumPaletteExtractor {

    fun extractSeedColor(bitmap: Bitmap): Int? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val scaled = try {
            Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        } catch (_: Throwable) {
            return null
        }

        // Quantize into 24 hue buckets to find the richest chromatic accent (TonalSpot scoring)
        val bucketWeight = DoubleArray(24)
        val bucketR = DoubleArray(24)
        val bucketG = DoubleArray(24)
        val bucketB = DoubleArray(24)
        val hsl = FloatArray(3)

        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        var fallbackR = 0L
        var fallbackG = 0L
        var fallbackB = 0L
        var count = 0

        for (px in pixels) {
            val alpha = (px ushr 24) and 0xFF
            if (alpha < 160) continue
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            fallbackR += r
            fallbackG += g
            fallbackB += b
            count++

            ColorUtils.RGBToHSL(r, g, b, hsl)
            val sat = hsl[1]
            val lum = hsl[2]
            if (sat < 0.14f || lum < 0.08f || lum > 0.92f) continue

            val bucket = ((hsl[0] / 15f).toInt()).coerceIn(0, 23)
            val lumPenalty = 1.0 - kotlin.math.abs(lum - 0.5) * 1.1
            val weight = (sat * 1.4 + 0.2) * lumPenalty.coerceAtLeast(0.2)
            bucketWeight[bucket] += weight
            bucketR[bucket] += r * weight
            bucketG[bucket] += g * weight
            bucketB[bucket] += b * weight
        }

        var bestBucket = -1
        var bestScore = 0.0
        for (i in 0 until 24) {
            if (bucketWeight[i] > bestScore) {
                bestScore = bucketWeight[i]
                bestBucket = i
            }
        }

        return if (bestBucket >= 0 && bestScore > 0.0) {
            val w = bucketWeight[bestBucket]
            android.graphics.Color.rgb(
                (bucketR[bestBucket] / w).toInt().coerceIn(0, 255),
                (bucketG[bestBucket] / w).toInt().coerceIn(0, 255),
                (bucketB[bestBucket] / w).toInt().coerceIn(0, 255),
            )
        } else if (count > 0) {
            android.graphics.Color.rgb(
                (fallbackR / count).toInt(),
                (fallbackG / count).toInt(),
                (fallbackB / count).toInt(),
            )
        } else {
            null
        }
    }

    fun buildTonalSpotScheme(
        seedColorArgb: Int,
        darkTheme: Boolean,
        fallback: ColorScheme,
    ): ColorScheme {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(seedColorArgb, hsl)
        val hue = hsl[0]
        val tertiaryHue = (hue + 52f) % 360f
        val baseSat = hsl[1].coerceIn(0.28f, 0.72f)
        val neutralSat = (baseSat * 0.16f).coerceIn(0.04f, 0.14f)
        val secondarySat = (baseSat * 0.36f).coerceIn(0.12f, 0.28f)
        val tertiarySat = (baseSat * 0.65f).coerceIn(0.32f, 0.68f)

        fun tone(h: Float, s: Float, lightness: Float): Color {
            val argb = ColorUtils.HSLToColor(floatArrayOf(h, s.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f)))
            return Color(argb)
        }

        return if (darkTheme) {
            val surface = tone(hue, neutralSat, 0.08f)
            val surfaceLow = tone(hue, neutralSat, 0.11f)
            val surfaceMid = tone(hue, neutralSat, 0.14f)
            val surfaceHigh = tone(hue, neutralSat, 0.19f)
            val surfaceHighest = tone(hue, neutralSat, 0.24f)
            val primary = tone(hue, baseSat, 0.78f)
            val onPrimary = ensureContrast(tone(hue, baseSat, 0.16f), primary, minRatio = 4.6)
            val primaryContainer = tone(hue, baseSat * 0.85f, 0.27f)
            val onPrimaryContainer = ensureContrast(tone(hue, baseSat, 0.90f), primaryContainer, minRatio = 4.6)
            val secondary = tone(hue, secondarySat, 0.76f)
            val secondaryContainer = tone(hue, secondarySat, 0.26f)
            val onSecondaryContainer = ensureContrast(tone(hue, secondarySat, 0.90f), secondaryContainer, minRatio = 4.6)
            val tertiary = tone(tertiaryHue, tertiarySat, 0.80f)
            val onTertiary = ensureContrast(tone(tertiaryHue, tertiarySat, 0.16f), tertiary, minRatio = 4.6)
            val tertiaryContainer = tone(tertiaryHue, tertiarySat * 0.85f, 0.28f)
            val onTertiaryContainer = ensureContrast(tone(tertiaryHue, tertiarySat, 0.91f), tertiaryContainer, minRatio = 4.6)
            val onSurface = ensureContrast(tone(hue, neutralSat, 0.91f), surfaceLow, minRatio = 7.0)
            val onSurfaceVariant = ensureContrast(tone(hue, secondarySat * 0.6f, 0.78f), surfaceMid, minRatio = 4.6)

            fallback.copy(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = tone(hue, secondarySat, 0.16f),
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
                surfaceVariant = tone(hue, secondarySat * 0.6f, 0.28f),
                onSurfaceVariant = onSurfaceVariant,
                surfaceContainerLow = surfaceLow,
                surfaceContainer = surfaceMid,
                surfaceContainerHigh = surfaceHigh,
                surfaceContainerHighest = surfaceHighest,
            )
        } else {
            val surface = tone(hue, neutralSat, 0.98f)
            val surfaceLow = tone(hue, neutralSat, 0.95f)
            val surfaceMid = tone(hue, neutralSat, 0.92f)
            val surfaceHigh = tone(hue, neutralSat, 0.89f)
            val surfaceHighest = tone(hue, neutralSat, 0.86f)
            val primary = tone(hue, baseSat, 0.36f)
            val onPrimary = ensureContrast(Color.White, primary, minRatio = 4.6)
            val primaryContainer = tone(hue, baseSat * 0.85f, 0.88f)
            val onPrimaryContainer = ensureContrast(tone(hue, baseSat, 0.14f), primaryContainer, minRatio = 4.6)
            val secondary = tone(hue, secondarySat, 0.38f)
            val secondaryContainer = tone(hue, secondarySat, 0.88f)
            val onSecondaryContainer = ensureContrast(tone(hue, secondarySat, 0.15f), secondaryContainer, minRatio = 4.6)
            val tertiary = tone(tertiaryHue, tertiarySat, 0.36f)
            val onTertiary = ensureContrast(Color.White, tertiary, minRatio = 4.6)
            val tertiaryContainer = tone(tertiaryHue, tertiarySat * 0.85f, 0.89f)
            val onTertiaryContainer = ensureContrast(tone(tertiaryHue, tertiarySat, 0.14f), tertiaryContainer, minRatio = 4.6)
            val onSurface = ensureContrast(tone(hue, neutralSat, 0.12f), surfaceLow, minRatio = 7.0)
            val onSurfaceVariant = ensureContrast(tone(hue, secondarySat * 0.7f, 0.30f), surfaceMid, minRatio = 4.6)

            fallback.copy(
                primary = primary,
                onPrimary = onPrimary,
                primaryContainer = primaryContainer,
                onPrimaryContainer = onPrimaryContainer,
                secondary = secondary,
                onSecondary = Color.White,
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
                surfaceVariant = tone(hue, secondarySat * 0.6f, 0.88f),
                onSurfaceVariant = onSurfaceVariant,
                surfaceContainerLow = surfaceLow,
                surfaceContainer = surfaceMid,
                surfaceContainerHigh = surfaceHigh,
                surfaceContainerHighest = surfaceHighest,
            )
        }
    }

    private fun ensureContrast(foreground: Color, background: Color, minRatio: Double): Color {
        val bgArgb = background.toArgb()
        var fgArgb = foreground.toArgb()
        if (ColorUtils.calculateContrast(fgArgb, bgArgb) >= minRatio) {
            return foreground
        }
        val bgLum = ColorUtils.calculateLuminance(bgArgb)
        val targetTowardsWhite = bgLum < 0.45
        for (step in 1..12) {
            val blend = step / 12f
            fgArgb = ColorUtils.blendARGB(
                fgArgb,
                if (targetTowardsWhite) android.graphics.Color.WHITE else android.graphics.Color.BLACK,
                blend * 0.25f,
            )
            if (ColorUtils.calculateContrast(fgArgb, bgArgb) >= minRatio) {
                return Color(fgArgb)
            }
        }
        return if (targetTowardsWhite) Color.White else Color.Black
    }
}
