// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import helium314.keyboard.onboarding.SpeakKeysType

/**
 * Centered text link used as a secondary/skip action below a [PrimaryCta].
 * Keeps the 48dp min touch target via padding.
 */
@Composable
fun GhostLink(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = SpeakKeysType.GhostLinkLabel)
    }
}
