// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class MicStripState { Idle, Listening, Transcribed }

private val StateDurationsMs = longArrayOf(2200L, 2200L, 2600L)

@Composable
fun rememberMicStripState(animationsEnabled: Boolean): MicStripState {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) {
            index = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(StateDurationsMs[index])
            index = (index + 1) % StateDurationsMs.size
        }
    }
    return when (index) {
        0 -> MicStripState.Idle
        1 -> MicStripState.Listening
        else -> MicStripState.Transcribed
    }
}

@Composable
fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        scale > 0f
    }
}

@Composable
fun MicStrip(modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    val state = rememberMicStripState(animate)
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            fadeIn(tween(300)) togetherWith fadeOut(tween(300))
        },
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp),
        label = "micStripCrossfade",
    ) { target ->
        when (target) {
            MicStripState.Idle -> IdleStrip()
            MicStripState.Listening -> ListeningStrip(animate)
            MicStripState.Transcribed -> TranscribedStrip()
        }
    }
}

@Composable
private fun IdleStrip() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(SpeakKeysColors.BgElev2)
            .border(1.dp, SpeakKeysColors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("Hey", "Sure", "On my way").forEach { label ->
            SuggestionChip(label)
        }
        Spacer(Modifier.weight(1f))
        IdleMicButton()
    }
}

@Composable
private fun SuggestionChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SpeakKeysColors.Bg)
            .border(1.dp, SpeakKeysColors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text, style = SpeakKeysType.Chip.copy(color = SpeakKeysColors.FgDim))
    }
}

@Composable
private fun IdleMicButton() {
    Box(
        modifier = Modifier
            .size(38.dp) // 30dp button + 4dp ring on each side
            .background(SpeakKeysColors.BrandSoft, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    Brush.linearGradient(
                        colors = listOf(SpeakKeysColors.BrandGlow, SpeakKeysColors.Brand),
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                tint = SpeakKeysColors.CtaTextOnBrand,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}

@Composable
private fun ListeningStrip(animate: Boolean) {
    val glowAlpha = if (animate) {
        val t = rememberInfiniteTransition(label = "listeningGlow")
        val a by t.animateFloat(
            initialValue = 1.0f,
            targetValue = 0.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glowAlpha",
        )
        a
    } else 1f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(SpeakKeysColors.BrandDeep, SpeakKeysColors.Brand),
                ),
            )
            .border(1.dp, SpeakKeysColors.BrandGlow, RoundedCornerShape(10.dp)),
    ) {
        // radial glow overlay with pulsing opacity
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            SpeakKeysColors.BrandGlow.copy(alpha = 0.4f * glowAlpha),
                            Color.Transparent,
                        ),
                        center = Offset.Unspecified,
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Listening\u2026",
                style = SpeakKeysType.StripStatus.copy(color = Color.White),
            )
            Box(modifier = Modifier.weight(1f)) {
                WaveformBars(animate = animate)
            }
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color.White.copy(alpha = 0.18f), CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun TranscribedStrip() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .background(SpeakKeysColors.BrandSoft.copy(alpha = 0.55f))
            .border(1.dp, SpeakKeysColors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val lowConfidenceWord = "seven"
        val annotated = buildAnnotatedString {
            append("Meet at ")
            withStyle(
                SpanStyle(
                    background = SpeakKeysColors.Active.copy(alpha = 0.18f),
                    color = SpeakKeysColors.Fg,
                    textDecoration = TextDecoration.Underline,
                ),
            ) {
                append(lowConfidenceWord)
            }
            append(" tonight")
        }
        Text(
            text = annotated,
            style = SpeakKeysType.StripText.copy(color = SpeakKeysColors.Fg),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        UndoChip()
    }
}

@Composable
private fun UndoChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SpeakKeysColors.Bg)
            .border(
                width = 1.dp,
                brush = SolidColor(SpeakKeysColors.Brand.copy(alpha = 0.66f)),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = "Undo",
            style = SpeakKeysType.Chip.copy(
                color = SpeakKeysColors.BrandGlow,
                fontSize = 10.sp,
            ),
        )
    }
}
