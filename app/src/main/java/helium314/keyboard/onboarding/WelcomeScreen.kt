// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.onboarding.components.CtaVariant
import helium314.keyboard.onboarding.components.PrimaryCta

@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
) {
    val systemBarInsets = WindowInsets.systemBars.asPaddingValues()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

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
                    bottom = systemBarInsets.calculateBottomPadding() + 24.dp,
                ),
        ) {
            BrandRow()
            Spacer(Modifier.height(44.dp))
            Hero()
            Spacer(Modifier.height(28.dp))
            WelcomePreviewCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 20.dp,
                        shape = RoundedCornerShape(22.dp),
                        ambientColor = Color.Black,
                        spotColor = Color.Black,
                    ),
            )
            Spacer(Modifier.weight(1f))
            PrimaryCta(
                label = stringResource(R.string.onboarding_cta_get_started),
                sublabel = stringResource(R.string.onboarding_cta_get_started_sub),
                onClick = onGetStarted,
                icon = {
                    Text(
                        text = "\u2726",
                        style = SpeakKeysType.Button.copy(color = SpeakKeysColors.CtaTextOnBrand),
                    )
                },
                variant = CtaVariant.Brand,
                accessibilityLabel = stringResource(R.string.onboarding_cta_get_started),
            )
        }
    }
}

@Composable
private fun BrandRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_speakkeys_logo),
            contentDescription = stringResource(R.string.onboarding_logo_content_description),
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.onboarding_brand_wordmark),
            style = SpeakKeysType.Wordmark.copy(color = SpeakKeysColors.Fg),
        )
    }
}

@Composable
private fun Hero() {
    Column {
        Pill(text = stringResource(R.string.onboarding_pill_voice_first))
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_title),
            style = SpeakKeysType.Hero.copy(color = SpeakKeysColors.Fg),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.onboarding_welcome_body),
            style = SpeakKeysType.Body,
            modifier = Modifier.widthIn(max = 300.dp),
        )
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SpeakKeysColors.BrandSoft)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text.uppercase(),
            style = SpeakKeysType.Pill.copy(color = SpeakKeysColors.BrandGlow),
        )
    }
}

