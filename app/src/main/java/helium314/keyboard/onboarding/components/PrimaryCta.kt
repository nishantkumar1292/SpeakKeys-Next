// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import helium314.keyboard.onboarding.SpeakKeysColors
import helium314.keyboard.onboarding.SpeakKeysType

enum class CtaVariant { Brand, Dark }

/**
 * Bottom-anchored primary action. Use [CtaVariant.Brand] for the main decision on a screen,
 * [CtaVariant.Dark] when the hero on the screen is the visual anchor and the CTA should
 * recede (e.g. the mic disc on the permission screen).
 */
@Composable
fun PrimaryCta(
    label: String,
    sublabel: String?,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    variant: CtaVariant = CtaVariant.Brand,
    accessibilityLabel: String = label,
) {
    val isBrand = variant == CtaVariant.Brand
    val textColor = if (isBrand) SpeakKeysColors.CtaTextOnBrand else SpeakKeysColors.Fg
    val subTextColor = if (isBrand) {
        SpeakKeysColors.CtaTextOnBrand.copy(alpha = 0.7f)
    } else {
        SpeakKeysColors.FgDim
    }
    val iconTileBg = if (isBrand) Color(0x1E0A1528) else SpeakKeysColors.BrandSoft

    val base = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)

    val styled = if (isBrand) {
        base
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = SpeakKeysColors.Brand,
                spotColor = SpeakKeysColors.Brand,
            )
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(SpeakKeysColors.BrandGlow, SpeakKeysColors.Brand),
                ),
            )
    } else {
        base
            .clip(RoundedCornerShape(18.dp))
            .background(SpeakKeysColors.BgElev2)
            .border(1.dp, SpeakKeysColors.Border, RoundedCornerShape(18.dp))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = styled
            .clickable(onClick = onClick)
            .semantics { contentDescription = accessibilityLabel }
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconTileBg),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text = label, style = SpeakKeysType.Button.copy(color = textColor))
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = SpeakKeysType.SubLabel.copy(color = subTextColor),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Text(
            text = "\u2192",
            style = SpeakKeysType.Button.copy(color = textColor.copy(alpha = 0.7f)),
        )
    }
}
