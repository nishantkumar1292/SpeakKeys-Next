// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import com.elishaazaria.sayboard.recognition.recognizers.providers.Providers
import dev.patrickgold.jetpref.datastore.model.observeAsState
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.ActionRow
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.ListPickerDialog
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.voice.VoicePrefs
import helium314.keyboard.voice.auth.AndroidAuthTokenProvider
import helium314.keyboard.voice.auth.AuthManager
import helium314.keyboard.voice.preferences.AndroidPreferencesRepository
import helium314.keyboard.voice.speakKeysPreferenceModel
import kotlinx.coroutines.launch

@Composable
fun VoiceInputSettingsScreen(onClickBack: () -> Unit) {
    val prefs by speakKeysPreferenceModel()
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.getActivity()

    val openaiApiKey by prefs.openaiApiKey.observeAsState()
    val sarvamApiKey by prefs.sarvamApiKey.observeAsState()
    val whisperLanguage by prefs.whisperLanguage.observeAsState()
    val whisperPrompt by prefs.whisperPrompt.observeAsState()
    val whisperTransliterate by prefs.whisperTransliterateToRoman.observeAsState()
    val sarvamMode by prefs.sarvamMode.observeAsState()
    val sarvamLanguage by prefs.sarvamLanguage.observeAsState()
    val autoCapitalize by prefs.logicAutoCapitalize.observeAsState()
    val modelOrder by prefs.modelsOrder.observeAsState()
    val lastSelectedModelPath by prefs.lastSelectedModelPath.observeAsState()

    var isSignedIn by remember { mutableStateOf(AuthManager.isSignedIn) }
    var accountName by remember { mutableStateOf(AuthManager.displayName) }
    var accountEmail by remember { mutableStateOf(AuthManager.email) }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableStateOf(0) }
    var textDialog by remember { mutableStateOf<VoiceTextDialog?>(null) }
    var showSarvamModeDialog by remember { mutableStateOf(false) }

    fun refreshAuthState() {
        isSignedIn = AuthManager.isSignedIn
        accountName = AuthManager.displayName
        accountEmail = AuthManager.email
    }

    val installedModels = remember(refreshToken, isSignedIn, openaiApiKey, sarvamApiKey) {
        availableRecognizerModels()
    }

    LaunchedEffect(installedModels) {
        syncRecognizerOrder(prefs, installedModels)
    }

    val signedOutSummary = stringResource(R.string.voice_settings_account_signed_out_summary)
    val signedInSummary = stringResource(R.string.voice_settings_account_signed_in_summary)
    val notSet = stringResource(R.string.voice_settings_not_set)
    val secretSet = stringResource(R.string.voice_settings_secret_set)
    val autoDetect = stringResource(R.string.voice_settings_auto_detect)
    val emptyPrompt = stringResource(R.string.voice_settings_whisper_prompt_empty)

    val accountSummary = authError ?: if (!isSignedIn) {
        signedOutSummary
    } else {
        listOfNotNull(
            accountName?.takeIf { it.isNotBlank() },
            accountEmail?.takeIf { it.isNotBlank() },
            signedInSummary
        ).joinToString("\n")
    }

    SearchScreen<Unit>(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.voice_settings_title)) },
        filteredItems = { emptyList<Unit>() },
        itemContent = {},
        icon = {},
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(innerPadding)
                ) {
                    PreferenceCategory(stringResource(R.string.voice_settings_account_category))
                    Preference(
                        name = if (isSignedIn) {
                            stringResource(R.string.voice_settings_sign_out)
                        } else {
                            stringResource(R.string.voice_settings_sign_in)
                        },
                        description = accountSummary,
                        onClick = {
                            if (!authBusy && activity != null) {
                                authBusy = true
                                authError = null
                                scope.launch {
                                    if (isSignedIn) {
                                        AuthManager.signOut(activity)
                                        refreshAuthState()
                                    } else {
                                        val success = AuthManager.signIn(activity)
                                        refreshAuthState()
                                        if (!success) {
                                            authError = activity.getString(R.string.voice_settings_sign_in_failed)
                                        }
                                    }
                                    refreshToken++
                                    authBusy = false
                                }
                            }
                        }
                    )
                    Preference(
                        name = stringResource(R.string.voice_settings_refresh_models),
                        description = stringResource(R.string.voice_settings_refresh_models_summary),
                        onClick = { refreshToken++ }
                    )

                    PreferenceCategory(stringResource(R.string.voice_settings_models_category))
                    Text(
                        text = stringResource(R.string.voice_settings_models_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    if (modelOrder.isEmpty()) {
                        Preference(
                            name = stringResource(R.string.voice_settings_no_models_title),
                            description = stringResource(R.string.voice_settings_no_models_summary),
                            onClick = { refreshToken++ }
                        )
                    } else {
                        modelOrder.forEachIndexed { index, model ->
                            VoiceModelRow(
                                model = model,
                                index = index,
                                isLastUsed = lastSelectedModelPath == model.path,
                                canMoveUp = index > 0,
                                canMoveDown = index < modelOrder.lastIndex,
                                onMoveUp = { prefs.modelsOrder.set(modelOrder.move(index, index - 1)) },
                                onMoveDown = { prefs.modelsOrder.set(modelOrder.move(index, index + 1)) }
                            )
                        }
                    }

                    PreferenceCategory(stringResource(R.string.voice_settings_api_category))
                    Preference(
                        name = stringResource(R.string.voice_settings_openai_api_key_title),
                        description = if (openaiApiKey.isBlank()) notSet else secretSet,
                        onClick = { textDialog = VoiceTextDialog.OpenAiApiKey(openaiApiKey) }
                    )
                    Preference(
                        name = stringResource(R.string.voice_settings_sarvam_api_key_title),
                        description = if (sarvamApiKey.isBlank()) notSet else secretSet,
                        onClick = { textDialog = VoiceTextDialog.SarvamApiKey(sarvamApiKey) }
                    )
                    Preference(
                        name = stringResource(R.string.voice_settings_whisper_language_title),
                        description = whisperLanguage.ifBlank { autoDetect },
                        onClick = { textDialog = VoiceTextDialog.WhisperLanguage(whisperLanguage) }
                    )
                    Preference(
                        name = stringResource(R.string.voice_settings_whisper_prompt_title),
                        description = whisperPrompt.ifBlank { emptyPrompt }.take(90),
                        onClick = { textDialog = VoiceTextDialog.WhisperPrompt(whisperPrompt) }
                    )
                    Preference(
                        name = stringResource(R.string.voice_settings_whisper_transliterate_title),
                        description = stringResource(R.string.voice_settings_whisper_transliterate_summary),
                        onClick = { prefs.whisperTransliterateToRoman.set(!whisperTransliterate) }
                    ) {
                        Switch(
                            checked = whisperTransliterate,
                            onCheckedChange = { prefs.whisperTransliterateToRoman.set(it) }
                        )
                    }
                    Preference(
                        name = stringResource(R.string.voice_settings_sarvam_mode_title),
                        description = sarvamModeLabel(sarvamMode),
                        onClick = { showSarvamModeDialog = true }
                    )
                    Preference(
                        name = stringResource(R.string.voice_settings_sarvam_language_title),
                        description = sarvamLanguage.takeUnless {
                            it.isBlank() || it == "unknown"
                        } ?: autoDetect,
                        onClick = { textDialog = VoiceTextDialog.SarvamLanguage(sarvamLanguage) }
                    )

                    PreferenceCategory(stringResource(R.string.voice_settings_behavior_category))
                    Preference(
                        name = stringResource(R.string.voice_settings_auto_capitalize_title),
                        description = stringResource(R.string.voice_settings_auto_capitalize_summary),
                        onClick = { prefs.logicAutoCapitalize.set(!autoCapitalize) }
                    ) {
                        Switch(
                            checked = autoCapitalize,
                            onCheckedChange = { prefs.logicAutoCapitalize.set(it) }
                        )
                    }
                }
            }
        }
    )

    textDialog?.let { dialog ->
        TextInputDialog(
            onDismissRequest = { textDialog = null },
            onConfirmed = { value ->
                when (dialog) {
                    is VoiceTextDialog.OpenAiApiKey -> prefs.openaiApiKey.set(value.trim())
                    is VoiceTextDialog.SarvamApiKey -> prefs.sarvamApiKey.set(value.trim())
                    is VoiceTextDialog.WhisperLanguage -> prefs.whisperLanguage.set(value.trim())
                    is VoiceTextDialog.WhisperPrompt -> prefs.whisperPrompt.set(value)
                    is VoiceTextDialog.SarvamLanguage -> {
                        prefs.sarvamLanguage.set(value.trim().ifBlank { "unknown" })
                    }
                }
                refreshToken++
                textDialog = null
            },
            title = { Text(stringResource(dialog.title)) },
            initialText = dialog.initialValue,
            singleLine = dialog.singleLine,
            keyboardType = if (dialog.isSecret) KeyboardType.Password else KeyboardType.Text,
            checkTextValid = { true },
        )
    }

    if (showSarvamModeDialog) {
        ListPickerDialog(
            onDismissRequest = { showSarvamModeDialog = false },
            items = listOf("translit", "transcribe"),
            onItemSelected = {
                prefs.sarvamMode.set(it)
                showSarvamModeDialog = false
            },
            title = { Text(stringResource(R.string.voice_settings_sarvam_mode_title)) },
            selectedItem = normalizeSarvamMode(sarvamMode),
            getItemName = { sarvamModeLabel(it) }
        )
    }
}

@Composable
private fun VoiceModelRow(
    model: InstalledModelReference,
    index: Int,
    isLastUsed: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    ActionRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyLarge
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.voice_settings_priority_prefix, index + 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = modelTypeLabel(model.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isLastUsed) {
                    Text(
                        text = stringResource(R.string.voice_settings_last_used_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        IconButton(
            onClick = onMoveUp,
            enabled = canMoveUp
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.voice_settings_move_up),
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = onMoveDown,
            enabled = canMoveDown
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.voice_settings_move_down),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun availableRecognizerModels(): List<InstalledModelReference> {
    return Providers(
        prefs = AndroidPreferencesRepository(),
        authTokenProvider = AndroidAuthTokenProvider()
    ).installedModels().toList()
}

private fun syncRecognizerOrder(
    prefs: VoicePrefs,
    installedModels: List<InstalledModelReference>
) {
    val currentOrder = prefs.modelsOrder.get()
    val updatedOrder = currentOrder.toMutableList().apply {
        removeAll { it !in installedModels }
        installedModels.forEach { model ->
            if (model !in this) add(model)
        }
    }
    if (updatedOrder != currentOrder) {
        prefs.modelsOrder.set(updatedOrder)
    }
    val selectedPath = prefs.lastSelectedModelPath.get()
    if (selectedPath.isNotEmpty() && updatedOrder.none { it.path == selectedPath }) {
        prefs.lastSelectedModelPath.set(updatedOrder.firstOrNull()?.path.orEmpty())
    }
}

private fun List<InstalledModelReference>.move(from: Int, to: Int): List<InstalledModelReference> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply {
        add(to, removeAt(from))
    }
}

@Composable
private fun sarvamModeLabel(mode: String): String {
    return when (normalizeSarvamMode(mode)) {
        "transcribe" -> stringResource(R.string.voice_settings_sarvam_mode_transcribe)
        else -> stringResource(R.string.voice_settings_sarvam_mode_translit)
    }
}

@Composable
private fun modelTypeLabel(type: ModelType): String {
    return when (type) {
        ModelType.WhisperCloud -> stringResource(R.string.voice_settings_model_type_whisper)
        ModelType.SarvamCloud -> stringResource(R.string.voice_settings_model_type_sarvam)
        ModelType.ProxiedWhisperCloud,
        ModelType.ProxiedSarvamCloud -> stringResource(R.string.voice_settings_model_type_proxied)
    }
}

private fun normalizeSarvamMode(mode: String): String = when (mode) {
    "native" -> "transcribe"
    "transcribe", "translit" -> mode
    else -> "translit"
}

private sealed class VoiceTextDialog(
    val title: Int,
    val initialValue: String,
    val singleLine: Boolean = true,
    val isSecret: Boolean = false,
) {
    class OpenAiApiKey(value: String) : VoiceTextDialog(
        title = R.string.voice_settings_openai_api_key_title,
        initialValue = value,
        isSecret = true
    )

    class SarvamApiKey(value: String) : VoiceTextDialog(
        title = R.string.voice_settings_sarvam_api_key_title,
        initialValue = value,
        isSecret = true
    )

    class WhisperLanguage(value: String) : VoiceTextDialog(
        title = R.string.voice_settings_whisper_language_title,
        initialValue = value
    )

    class WhisperPrompt(value: String) : VoiceTextDialog(
        title = R.string.voice_settings_whisper_prompt_title,
        initialValue = value,
        singleLine = false
    )

    class SarvamLanguage(value: String) : VoiceTextDialog(
        title = R.string.voice_settings_sarvam_language_title,
        initialValue = value.takeUnless { it == "unknown" }.orEmpty()
    )
}
