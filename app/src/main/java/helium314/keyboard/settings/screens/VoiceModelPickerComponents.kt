// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R

internal enum class VoiceChoiceLocation { ONLINE, ON_PHONE }

internal enum class VoiceChoiceActionStyle { PRIMARY, SECONDARY }

internal data class VoiceChoiceUiModel(
    val stableId: String,
    val title: String,
    val description: String,
    val location: VoiceChoiceLocation,
    val badges: List<String>,
    val fitLabel: String? = null,
    val recommended: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val actionEnabled: Boolean = true,
    val selectionLocksAction: Boolean = true,
    val actionLabel: String,
    val actionStyle: VoiceChoiceActionStyle = VoiceChoiceActionStyle.SECONDARY,
    val progress: Float? = null,
    val supportingText: String? = null,
    val secondaryActionLabel: String? = null,
    val secondaryActionEnabled: Boolean = true,
)

@Composable
internal fun VoicePickerIntro(
    modifier: Modifier = Modifier,
    title: String,
    body: String,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.linearGradient(
                    listOf(colors.primaryContainer, colors.secondaryContainer.copy(alpha = 0.72f)),
                ),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onPrimaryContainer,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onPrimaryContainer.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
internal fun VoiceChoiceCard(
    model: VoiceChoiceUiModel,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = when {
        model.selected -> colors.primary
        model.recommended -> colors.tertiary.copy(alpha = 0.8f)
        else -> colors.outlineVariant
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (model.selected) {
                colors.primaryContainer.copy(alpha = 0.42f)
            } else {
                colors.surfaceContainer
            },
        ),
        border = BorderStroke(if (model.selected) 2.dp else 1.dp, borderColor),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                VoiceLocationGlyph(
                    location = model.location,
                    selected = model.selected,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (model.selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (model.recommended) {
                    VoiceBadge(
                        stringResource(R.string.voice_settings_badge_recommended),
                        colors.tertiaryContainer,
                    )
                }
                model.fitLabel?.let { VoiceBadge(it, colors.primaryContainer) }
                model.badges.forEach { VoiceBadge(it, colors.surfaceContainerHighest) }
            }

            model.progress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            model.supportingText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            when (model.actionStyle) {
                VoiceChoiceActionStyle.PRIMARY -> Button(
                    onClick = onAction,
                    enabled = model.enabled && model.actionEnabled &&
                        (!model.selected || !model.selectionLocksAction),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(),
                ) {
                    Text(
                        if (model.selected && model.selectionLocksAction) {
                            stringResource(R.string.voice_settings_using_now)
                        } else {
                            model.actionLabel
                        },
                    )
                }

                VoiceChoiceActionStyle.SECONDARY -> OutlinedButton(
                    onClick = onAction,
                    enabled = model.enabled && model.actionEnabled &&
                        (!model.selected || !model.selectionLocksAction),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (model.selected && model.selectionLocksAction) {
                            stringResource(R.string.voice_settings_using_now)
                        } else {
                            model.actionLabel
                        },
                    )
                }
            }
            if (model.secondaryActionLabel != null && onSecondaryAction != null) {
                TextButton(
                    onClick = onSecondaryAction,
                    enabled = model.enabled && model.secondaryActionEnabled,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(model.secondaryActionLabel)
                }
            }
        }
    }
}

@Composable
private fun VoiceLocationGlyph(
    location: VoiceChoiceLocation,
    selected: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val icon: ImageVector = when (location) {
        VoiceChoiceLocation.ONLINE -> Icons.Default.Cloud
        VoiceChoiceLocation.ON_PHONE -> Icons.Default.PhoneAndroid
    }
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Surface(
        shape = CircleShape,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerHighest,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun VoiceBadge(label: String, color: Color) {
    Surface(shape = CircleShape, color = color) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
