// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import dev.patrickgold.jetpref.datastore.model.observeAsState
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.voice.speakKeysPreferenceModel
import helium314.keyboard.voice.theme.SpaceAccentBlue
import helium314.keyboard.voice.theme.SpaceAccentCyan
import helium314.keyboard.voice.theme.SpaceBackdrop
import helium314.keyboard.voice.theme.SpaceControlIcon
import helium314.keyboard.voice.theme.SpaceFieldFill
import helium314.keyboard.voice.theme.SpaceOutline
import helium314.keyboard.voice.theme.SpaceOutlineStrong
import helium314.keyboard.voice.theme.SpacePanel
import helium314.keyboard.voice.theme.SpaceSuccessGreen
import helium314.keyboard.voice.theme.SpaceTextPrimary
import helium314.keyboard.voice.theme.SpaceTextSecondary
import helium314.keyboard.voice.theme.SpaceWarningAmber
import helium314.keyboard.voice.theme.spacePanelBrush
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val imm = remember { ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }

    var isEnabled by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)) }
    var isCurrent by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) }

    // Re-check IME status when the activity resumes (e.g. back from system settings)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isEnabled = UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)
                isCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Switching IME via the system picker doesn't pause our activity, so ON_RESUME
    // never fires and our cached `isCurrent` goes stale. Watch the setting directly.
    DisposableEffect(Unit) {
        val resolver = ctx.contentResolver
        val uri = AndroidSettings.Secure.getUriFor(AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                isEnabled = UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)
                isCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
            }
        }
        resolver.registerContentObserver(uri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    // Poll briefly after the user taps a status-changing button, so the UI updates
    // quickly without waiting for the IME picker dialog to dismiss.
    val scope = rememberCoroutineScope()
    fun pollStatus() {
        scope.launch {
            repeat(20) {
                delay(250)
                isEnabled = UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)
                isCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
            }
        }
    }

    var testText by remember { mutableStateOf("") }

    val prefs by speakKeysPreferenceModel()
    val modelsOrder by prefs.modelsOrder.observeAsState()
    val lastSelectedModelPath by prefs.lastSelectedModelPath.observeAsState()
    val currentModel: InstalledModelReference? = remember(modelsOrder, lastSelectedModelPath) {
        modelsOrder.firstOrNull { it.path == lastSelectedModelPath } ?: modelsOrder.firstOrNull()
    }
    val needsSetup = currentModel == null
    val currentModelName = currentModel?.let { formatModelName(it) }
        ?: stringResource(R.string.home_engine_needs_setup)

    SpaceBackdrop(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(top = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    text = stringResource(ctx.applicationInfo.labelRes),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    color = SpaceTextPrimary,
                    lineHeight = 52.sp
                )
                Text(
                    text = stringResource(R.string.home_tagline),
                    fontSize = 16.sp,
                    color = SpaceTextSecondary,
                    lineHeight = 24.sp
                )

                Spacer(Modifier.height(4.dp))

                // Status cards row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StatusCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.home_status_enabled_label),
                        icon = if (isEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        ok = isEnabled,
                        value = stringResource(
                            if (isEnabled) R.string.home_status_enabled_yes
                            else R.string.home_status_enabled_no
                        )
                    )
                    CurrentModelCard(
                        modifier = Modifier.weight(1f),
                        needsSetup = needsSetup,
                        modelName = currentModelName,
                        onClick = onOpenVoiceSettings
                    )
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val primaryColors = ButtonDefaults.buttonColors(
                        containerColor = SpaceAccentCyan,
                        contentColor = Color(0xFF041018)
                    )
                    val outlinedColors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SpaceTextPrimary
                    )

                    if (!isEnabled) {
                        Button(
                            onClick = {
                                ctx.startActivity(
                                    Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                pollStatus()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = primaryColors
                        ) {
                            Icon(Icons.Default.Keyboard, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_action_enable), fontWeight = FontWeight.SemiBold)
                        }
                    } else if (!isCurrent) {
                        Button(
                            onClick = {
                                imm.showInputMethodPicker()
                                pollStatus()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = primaryColors
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_action_switch), fontWeight = FontWeight.SemiBold)
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, SpaceOutlineStrong),
                        colors = outlinedColors
                    ) {
                        Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_action_settings))
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.home_test_drive_title),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SpaceTextPrimary
                )

                OutlinedTextField(
                    value = testText,
                    onValueChange = { testText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    placeholder = {
                        Text(
                            text = stringResource(R.string.home_test_drive_hint),
                            color = SpaceTextSecondary.copy(alpha = 0.6f)
                        )
                    },
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SpaceTextPrimary,
                        unfocusedTextColor = SpaceTextPrimary,
                        cursorColor = SpaceAccentCyan,
                        focusedBorderColor = SpaceAccentCyan,
                        unfocusedBorderColor = SpaceOutlineStrong.copy(alpha = 0.75f),
                        focusedContainerColor = SpaceFieldFill,
                        unfocusedContainerColor = SpaceFieldFill
                    )
                )

                OutlinedButton(
                    onClick = { testText = "" },
                    modifier = Modifier.align(Alignment.End),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, SpaceOutlineStrong),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SpaceTextPrimary)
                ) {
                    Text(
                        text = stringResource(R.string.home_test_drive_clear),
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    modifier: Modifier,
    label: String,
    icon: ImageVector,
    ok: Boolean,
    value: String,
) {
    val accent = if (ok) SpaceSuccessGreen else SpaceWarningAmber
    SpacePanel(
        modifier = modifier.heightIn(min = 110.dp),
        shape = RoundedCornerShape(22.dp),
        borderColor = SpaceOutline,
        backgroundBrush = spacePanelBrush(alpha = 0.92f),
        contentAlignment = Alignment.CenterStart,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = SpaceTextSecondary,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpaceControlIcon(
                    icon = icon,
                    tint = accent,
                    glowColor = accent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = value,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (ok) SpaceTextPrimary else accent
                )
            }
        }
    }
}

@Composable
private fun CurrentModelCard(
    modifier: Modifier,
    needsSetup: Boolean,
    modelName: String,
    onClick: () -> Unit,
) {
    val iconScale = remember { Animatable(1f) }
    LaunchedEffect(needsSetup) {
        if (needsSetup) {
            iconScale.animateTo(1.12f, animationSpec = tween(220))
            iconScale.animateTo(1f, animationSpec = tween(220))
        }
    }

    val accent = if (needsSetup) SpaceWarningAmber else SpaceAccentBlue
    val iconTint = if (needsSetup) SpaceWarningAmber else SpaceTextPrimary
    val valueColor = if (needsSetup) SpaceWarningAmber else SpaceTextPrimary

    SpacePanel(
        modifier = modifier
            .heightIn(min = 110.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        borderColor = SpaceOutline,
        backgroundBrush = spacePanelBrush(alpha = 0.92f),
        contentAlignment = Alignment.CenterStart,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(R.string.home_voice_engine_label),
                fontSize = 11.sp,
                color = SpaceTextSecondary,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpaceControlIcon(
                    icon = if (needsSetup) Icons.Default.Warning else Icons.Default.Mic,
                    tint = iconTint,
                    glowColor = accent,
                    modifier = Modifier.size(24.dp).scale(iconScale.value)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = modelName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = valueColor,
                    lineHeight = 20.sp
                )
            }
        }
    }
}

@Composable
private fun formatModelName(model: InstalledModelReference): String {
    val engineLabel = when (model.type) {
        ModelType.WhisperCloud -> stringResource(R.string.voice_settings_model_type_whisper)
        ModelType.SarvamCloud -> stringResource(R.string.voice_settings_model_type_sarvam)
        ModelType.ProxiedWhisperCloud,
        ModelType.ProxiedSarvamCloud -> stringResource(R.string.voice_settings_model_type_proxied)
    }
    return "$engineLabel · ${model.name}"
}
