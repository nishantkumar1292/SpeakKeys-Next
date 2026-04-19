// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import helium314.keyboard.onboarding.SpeakKeysColors
import helium314.keyboard.onboarding.SpeakKeysType

/**
 * Top-of-screen progress indicator. Shows "STEP N OF [total]" above a 3-segment bar.
 * The [current] step (1-indexed) and all prior segments are filled with the brand color;
 * remaining segments use the rail track color.
 */
@Composable
fun StepRail(
    current: Int,
    total: Int = 3,
    modifier: Modifier = Modifier,
) {
    val announced = "Step $current of $total"
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = announced },
    ) {
        Text(
            text = "STEP $current OF $total",
            style = SpeakKeysType.StepRailLabel,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            repeat(total) { index ->
                val filled = index < current
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (filled) SpeakKeysColors.Brand else SpeakKeysColors.StepRailTrack,
                        )
                        .padding(0.dp),
                ) {}
            }
        }
    }
}
