// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Minimalist mic glyph (viewBox 24×24). Used inside the pulsing mic disc on the
 * permission screen and as an accent icon in the mic-permission CTA. Rendered in
 * a single stroke color so it can sit on brand/dark surfaces alike.
 */
@Composable
fun OnboardingMicGlyph(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    strokeWidthDp: Dp = 1.8.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val unit = this.size.width / 24f
        val strokePx = strokeWidthDp.toPx()
        val stroke = Stroke(
            width = strokePx,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )

        // Mic capsule body: pill from y=3 to y=14, x=9..15, corner radius 3
        val body = Path().apply {
            val left = 9f * unit
            val right = 15f * unit
            val top = 3f * unit
            val bottom = 14f * unit
            val r = 3f * unit
            moveTo(left, top + r)
            arcTo(
                rect = Rect(Offset(left, top), Size(right - left, r * 2)),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            lineTo(right, bottom - r)
            arcTo(
                rect = Rect(Offset(left, bottom - r * 2), Size(right - left, r * 2)),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false,
            )
            close()
        }
        drawPath(body, color = tint, style = stroke)

        // Cradle: half-circle U wrapping the lower part of the body.
        // Open at the top, curving from (5, 11) through (12, 18) to (19, 11).
        val cradle = Path().apply {
            val cx = 12f * unit
            val cy = 11f * unit
            val r = 7f * unit
            arcTo(
                rect = Rect(
                    offset = Offset(cx - r, cy - r),
                    size = Size(r * 2, r * 2),
                ),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 180f,
                forceMoveTo = true,
            )
        }
        drawPath(cradle, color = tint, style = stroke)

        // Stem from (12, 18) to (12, 21)
        drawLine(
            color = tint,
            start = Offset(12f * unit, 18f * unit),
            end = Offset(12f * unit, 21f * unit),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
        // Base from (9, 21) to (15, 21)
        drawLine(
            color = tint,
            start = Offset(9f * unit, 21f * unit),
            end = Offset(15f * unit, 21f * unit),
            strokeWidth = strokePx,
            cap = StrokeCap.Round,
        )
    }
}
