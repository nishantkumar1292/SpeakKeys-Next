// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

@Composable
fun WelcomePreviewCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }, // decorative — TalkBack skips the preview entirely
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(SpeakKeysColors.BgElev)
                .border(1.dp, SpeakKeysColors.Border, RoundedCornerShape(22.dp))
                .padding(12.dp),
        ) {
            MicStrip()
            MiniKeyboard(modifier = Modifier.padding(top = 8.dp))
        }
        // floating PREVIEW label, overlaps the top border by 10dp
        Box(
            modifier = Modifier
                .offset(x = 16.dp, y = (-10).dp)
                .clip(RoundedCornerShape(6.dp))
                .background(SpeakKeysColors.BgElev)
                .border(1.dp, SpeakKeysColors.Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                text = "PREVIEW",
                style = SpeakKeysType.PreviewLabel.copy(color = SpeakKeysColors.BrandGlow),
            )
        }
    }
}

@Composable
private fun MiniKeyboard(modifier: Modifier = Modifier) {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEachIndexed { rowIndex, row ->
            val rowPadding = if (rowIndex == 1) PaddingValues(horizontal = 10.dp) else PaddingValues(0.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(rowPadding),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (rowIndex == 2) MiniKey(text = "\u21E7", wide = true)
                row.forEach { ch -> MiniKey(text = ch.toString(), modifier = Modifier.weight(1f)) }
                if (rowIndex == 2) MiniKey(text = "\u232B", wide = true)
            }
        }
    }
}

@Composable
private fun MiniKey(text: String, modifier: Modifier = Modifier, wide: Boolean = false) {
    Box(
        modifier = modifier
            .then(if (wide) Modifier.width(24.dp) else Modifier)
            .height(26.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(SpeakKeysColors.BgElev2)
            .border(1.dp, SpeakKeysColors.Hairline, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = SpeakKeysType.Key)
    }
}
