// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import helium314.keyboard.latin.R
import helium314.keyboard.onboarding.components.CtaVariant
import helium314.keyboard.onboarding.components.GhostLink
import helium314.keyboard.onboarding.components.OnboardingMicGlyph
import helium314.keyboard.onboarding.components.PrimaryCta
import helium314.keyboard.onboarding.components.StepRail

@Composable
fun PermissionScreen(
    onAllow: () -> Unit,
    onSkip: () -> Unit,
) {
    val systemBarInsets = WindowInsets.systemBars.asPaddingValues()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val context = LocalContext.current

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SpeakKeysColors.Bg)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        SpeakKeysColors.BrandSoft.copy(alpha = 0.33f),
                        Color.Transparent,
                    ),
                    radius = 900f,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = statusBarTop + 28.dp,
                    start = 24.dp,
                    end = 24.dp,
                    bottom = systemBarInsets.calculateBottomPadding() + 16.dp,
                ),
        ) {
            StepRail(current = 1)
            Spacer(Modifier.height(28.dp))
            Hero(
                title = stringResource(R.string.onboarding_permission_title),
                body = stringResource(R.string.onboarding_permission_body),
            )
            Spacer(Modifier.height(24.dp))
            PulsingMicDisc(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            TrustRow()
            Spacer(Modifier.height(16.dp))
            val ctaLabel = stringResource(
                if (micGranted) R.string.onboarding_permission_cta_continue
                else R.string.onboarding_permission_cta_allow,
            )
            val ctaSublabel = stringResource(
                if (micGranted) R.string.onboarding_permission_cta_continue_sub
                else R.string.onboarding_permission_cta_allow_sub,
            )
            PrimaryCta(
                label = ctaLabel,
                sublabel = ctaSublabel,
                onClick = {
                    if (micGranted) onAllow()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                icon = {
                    OnboardingMicGlyph(
                        tint = SpeakKeysColors.BrandGlow,
                        size = 22.dp,
                    )
                },
                variant = CtaVariant.Dark,
                accessibilityLabel = ctaLabel,
            )
            Spacer(Modifier.height(6.dp))
            GhostLink(
                label = stringResource(R.string.onboarding_permission_skip),
                onClick = onSkip,
            )
        }
    }
}

@Composable
private fun Hero(title: String, body: String) {
    Column {
        Text(
            text = title,
            style = SpeakKeysType.HeroSmall.copy(color = SpeakKeysColors.Fg),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = body,
            style = SpeakKeysType.Body,
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}

@Composable
private fun PulsingMicDisc(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }

    val transition = rememberInfiniteTransition(label = "mic-disc-pulse")
    val ringPhases = List(3) { index ->
        if (animationsEnabled) {
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(index * 800),
                ),
                label = "mic-ring-$index",
            ).value
        } else {
            0.5f
        }
    }

    val discSize = 112.dp
    val maxRingSize = 220.dp

    Box(
        modifier = modifier
            .size(maxRingSize)
            .semantics {
                contentDescription = "Microphone permission"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(maxRingSize)) {
            val minRadiusPx = discSize.toPx() / 2f
            val maxRadiusPx = maxRingSize.toPx() / 2f
            ringPhases.forEach { phase ->
                val radius = minRadiusPx + (maxRadiusPx - minRadiusPx) * phase
                val alpha = (0.35f * (1f - phase)).coerceIn(0f, 0.35f)
                drawCircle(
                    color = SpeakKeysColors.Brand.copy(alpha = alpha),
                    radius = radius,
                    center = center,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(discSize)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(SpeakKeysColors.BrandGlow, SpeakKeysColors.Brand),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            OnboardingMicGlyph(
                tint = SpeakKeysColors.CtaTextOnBrand,
                size = 44.dp,
                strokeWidthDp = 2.2.dp,
            )
        }
    }
}

@Composable
private fun TrustRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TrustItem(
            label = stringResource(R.string.onboarding_permission_trust_on_device),
            modifier = Modifier.weight(1f),
        )
        TrustItem(
            label = stringResource(R.string.onboarding_permission_trust_no_sharing),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TrustItem(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(SpeakKeysColors.Success),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = SpeakKeysType.TrustText,
        )
    }
}
