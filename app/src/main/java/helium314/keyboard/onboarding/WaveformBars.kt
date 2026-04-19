// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max

private const val BAR_COUNT = 15
private val BAR_BASE_AMPLITUDES = floatArrayOf(
    0.35f, 0.60f, 0.90f, 0.50f, 0.75f,
    0.40f, 0.85f, 0.55f, 0.95f, 0.45f,
    0.70f, 0.30f, 0.80f, 0.55f, 0.65f,
)

@Composable
fun WaveformBars(
    animate: Boolean,
    modifier: Modifier = Modifier,
    barColor: Color = Color.White,
    glowColor: Color = SpeakKeysColors.BrandGlow,
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val frozen = remember { mutableStateOf(0.5f) }
    val scales: List<State<Float>> = (0 until BAR_COUNT).map { i ->
        // full period = 0.7 + (i % 3) * 0.2 seconds; half-period drives the reverse tween.
        val halfPeriodMs = (700 + (i % 3) * 200) / 2
        val offsetMs = i * 50
        if (animate) {
            transition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(halfPeriodMs, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(offsetMs),
                ),
                label = "waveBar$i",
            )
        } else {
            frozen
        }
    }

    Row(
        modifier = modifier.height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until BAR_COUNT) {
            val base = BAR_BASE_AMPLITUDES[i]
            val scale = scales[i].value
            Canvas(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight(),
            ) {
                val maxBarHeight = 22.dp.toPx()
                val barHeight = max(2f, maxBarHeight * base * scale)
                val centerY = size.height / 2f
                val top = centerY - barHeight / 2f
                val widthPx = size.width
                val corner = CornerRadius(1.5.dp.toPx())
                // layered translucent halos approximate a blur glow
                val glowLayers = listOf(
                    glowColor.copy(alpha = 0.12f) to 7.dp.toPx(),
                    glowColor.copy(alpha = 0.22f) to 3.5.dp.toPx(),
                )
                glowLayers.forEach { (c, extra) ->
                    drawRoundRect(
                        color = c,
                        topLeft = Offset(-extra / 2f, top - extra / 2f),
                        size = Size(widthPx + extra, barHeight + extra),
                        cornerRadius = CornerRadius(corner.x + extra / 2f, corner.y + extra / 2f),
                    )
                }
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(0f, top),
                    size = Size(widthPx, barHeight),
                    cornerRadius = corner,
                )
            }
        }
    }
}
