package com.miniplayer.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 24x24dp Material-style diagonal guitar icon (angled 45° from bottom-left figure-8 body with
 * round soundhole and bridge to top-right neck and 4-peg headstock) representing Practice Mode.
 */
val GuitarIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "GuitarPracticeMode",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 1. Symmetric figure-8 guitar body at bottom-left
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(3.8f, 20.2f)
            // Lower-right contour: base -> lower bout -> waist -> upper bout -> neck heel
            curveTo(5.8f, 22.2f, 8.2f, 22.6f, 9.6f, 21.2f)
            curveTo(10.7f, 20.1f, 9.4f, 18.2f, 10.2f, 17.4f)
            curveTo(11.0f, 16.6f, 12.1f, 17.1f, 13.1f, 16.1f)
            curveTo(14.1f, 15.1f, 13.8f, 13.6f, 12.7f, 12.5f)
            // Neck joint
            lineTo(11.5f, 11.3f)
            // Upper-left contour (exact diagonal mirror): neck heel -> upper bout -> waist -> lower bout -> base
            curveTo(10.4f, 10.2f, 8.9f, 9.9f, 7.9f, 10.9f)
            curveTo(6.9f, 11.9f, 7.4f, 13.0f, 6.6f, 13.8f)
            curveTo(5.8f, 14.6f, 3.9f, 13.3f, 2.8f, 14.4f)
            curveTo(1.4f, 15.8f, 1.8f, 18.2f, 3.8f, 20.2f)
            close()
        }

        // 2. Round soundhole inside the body
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(7.05f, 15.3f)
            arcTo(1.65f, 1.65f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.35f, 15.3f)
            arcTo(1.65f, 1.65f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7.05f, 15.3f)
            close()
        }

        // 3. Acoustic bridge bar in lower bout
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.75f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(4.9f, 16.7f)
            lineTo(7.3f, 19.1f)
        }

        // 4. Guitar neck extending from body shoulder to headstock nut
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.1f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(12.1f, 11.9f)
            lineTo(17.6f, 6.4f)
        }

        // 5. Headstock + 4 tuning pegs at top-right
        path(
            fill = SolidColor(Color.Black),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(16.7f, 5.5f)
            lineTo(19.0f, 3.2f)
            lineTo(20.8f, 5.0f)
            lineTo(18.5f, 7.3f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
        ) {
            // Upper-left tuning pegs
            moveTo(17.4f, 4.8f)
            lineTo(16.2f, 3.6f)
            moveTo(18.5f, 3.7f)
            lineTo(17.3f, 2.5f)
            // Lower-right tuning pegs
            moveTo(19.2f, 6.6f)
            lineTo(20.4f, 7.8f)
            moveTo(20.3f, 5.5f)
            lineTo(21.5f, 6.7f)
        }
    }.build()
}
