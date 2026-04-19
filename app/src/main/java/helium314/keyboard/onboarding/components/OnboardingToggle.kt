// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import helium314.keyboard.onboarding.SpeakKeysColors

/**
 * 40 × 22dp toggle rendered purely for preview (no real state). The [on] flag
 * decides track color and knob position. When [pulse] is true the toggle emits
 * a soft outset glow ring to draw attention (used for the SpeakKeys row on the
 * enable-in-settings screen). Respects the system "remove animations" setting
 * by collapsing the pulse to a static ring.
 */
@Composable
fun OnboardingToggle(
    on: Boolean,
    pulse: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }

    val glowProgress = if (pulse && animationsEnabled) {
        val transition = rememberInfiniteTransition(label = "toggle-pulse")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "toggle-pulse-progress",
        ).value
    } else if (pulse) {
        0.5f
    } else {
        0f
    }

    val trackColor = if (on) SpeakKeysColors.ToggleTrackOn else SpeakKeysColors.ToggleTrackOff

    Box(
        modifier = modifier.size(width = 52.dp, height = 34.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (pulse) {
            Canvas(modifier = Modifier.size(width = 52.dp, height = 34.dp)) {
                val maxOutset = 6.dp.toPx()
                val baseHalfWidth = 40.dp.toPx() / 2f
                val baseHalfHeight = 22.dp.toPx() / 2f
                val alpha = (0.66f * (1f - glowProgress)).coerceIn(0f, 0.66f)
                val outset = maxOutset * glowProgress
                drawRoundRect(
                    color = SpeakKeysColors.Brand.copy(alpha = alpha),
                    topLeft = Offset(
                        x = center.x - baseHalfWidth - outset,
                        y = center.y - baseHalfHeight - outset,
                    ),
                    size = Size(
                        width = (baseHalfWidth + outset) * 2,
                        height = (baseHalfHeight + outset) * 2,
                    ),
                    cornerRadius = CornerRadius(
                        x = baseHalfHeight + outset,
                        y = baseHalfHeight + outset,
                    ),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(trackColor),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}
