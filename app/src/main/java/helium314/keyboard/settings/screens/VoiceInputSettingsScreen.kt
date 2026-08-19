// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState as observeLiveDataAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.work.WorkInfo
import com.elishaazaria.sayboard.data.InstalledModelReference
import com.elishaazaria.sayboard.data.ModelType
import dev.patrickgold.jetpref.datastore.model.observeAsState as observePreferenceAsState
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.settings.ActionRow
import helium314.keyboard.settings.SearchScreen
import helium314.keyboard.settings.dialogs.TextInputDialog
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.voice.AndroidRecognizerProviders
import helium314.keyboard.voice.VoicePrefs
import helium314.keyboard.voice.auth.AndroidAuthTokenProvider
import helium314.keyboard.voice.auth.AuthManager
import helium314.keyboard.voice.credentials.VoiceCredentialConfiguration
import helium314.keyboard.voice.credentials.VoiceCredentialService
import helium314.keyboard.voice.credentials.VoiceCredentialVault
import helium314.keyboard.voice.local.AndroidOnDeviceLanguageAvailability
import helium314.keyboard.voice.local.AndroidOnDeviceCapabilityStore
import helium314.keyboard.voice.local.AndroidOnDeviceLanguageDownloadState
import helium314.keyboard.voice.local.AndroidOnDeviceLanguagePackManager
import helium314.keyboard.voice.local.AndroidOnDeviceLanguagePackSnapshot
import helium314.keyboard.voice.local.AndroidOnDeviceLanguageSupportState
import helium314.keyboard.voice.local.LocalModelInstallPhase
import helium314.keyboard.voice.local.LocalModelInstallError
import helium314.keyboard.voice.local.LocalModelInstallWorker
import helium314.keyboard.voice.local.LocalModelRepository
import helium314.keyboard.voice.local.LocalSpeechModelCatalog
import helium314.keyboard.voice.local.LocalSpeechModelDescriptor
import helium314.keyboard.voice.local.overallAndroidLanguageDownloadPercent
import helium314.keyboard.voice.preferences.AndroidPreferencesRepository
import helium314.keyboard.voice.setModelsOrder
import helium314.keyboard.voice.speakKeysPreferenceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VoiceInputSettingsScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context.getActivity()
    val prefs by speakKeysPreferenceModel()
    val scope = rememberCoroutineScope()
    val localModels = remember(context) { LocalModelRepository(context) }
    val phoneCapabilityStore = remember(context) { AndroidOnDeviceCapabilityStore(context) }
    val credentialVault = remember(context) { VoiceCredentialVault.get(context) }

    val selectedPath by prefs.lastSelectedModelPath.observePreferenceAsState()
    val modelsOrder by prefs.modelsOrder.observePreferenceAsState()
    val outputStyle by prefs.voiceOutputStyle.observePreferenceAsState()
    val autoCapitalize by prefs.logicAutoCapitalize.observePreferenceAsState()

    var isSignedIn by remember { mutableStateOf(AuthManager.isSignedIn) }
    var accountName by remember { mutableStateOf(AuthManager.displayName) }
    var accountEmail by remember { mutableStateOf(AuthManager.email) }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var showTechnicalDetails by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf<VoiceServiceDialog?>(null) }
    var credentialConfiguration by remember {
        mutableStateOf<VoiceCredentialConfiguration?>(null)
    }
    var credentialError by remember { mutableStateOf<String?>(null) }
    var showCredentialResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var credentialResetBusy by remember { mutableStateOf(false) }
    var credentialRefresh by remember { mutableIntStateOf(0) }
    var localRefresh by remember { mutableIntStateOf(0) }
    var phoneLanguagePacks by remember { mutableStateOf(AndroidOnDeviceLanguagePackSnapshot()) }
    val phoneLanguagePackManager = remember(context) {
        AndroidOnDeviceLanguagePackManager(context) { snapshot -> phoneLanguagePacks = snapshot }
    }

    DisposableEffect(phoneLanguagePackManager) {
        phoneLanguagePackManager.refreshSupport()
        onDispose(phoneLanguagePackManager::close)
    }

    LaunchedEffect(phoneLanguagePacks.download) {
        if (phoneLanguagePacks.download is AndroidOnDeviceLanguageDownloadState.Complete) {
            phoneLanguagePackManager.refreshSupport()
        }
    }

    LaunchedEffect(phoneLanguagePacks.support) {
        if (phoneCapabilityStore.recordSupportState(phoneLanguagePacks.support)) {
            localRefresh++
        }
    }

    LaunchedEffect(credentialRefresh) {
        credentialConfiguration = withContext(Dispatchers.IO) {
            credentialVault.configuration()
        }
        credentialError = when {
            credentialConfiguration?.storageError == true -> {
                context.getString(R.string.voice_settings_key_storage_error)
            }
            credentialConfiguration?.storageAvailable == false -> {
                context.getString(R.string.voice_settings_key_storage_locked)
            }
            else -> null
        }
    }

    val sarvamConfigured = credentialConfiguration
        ?.isConfigured(VoiceCredentialService.SARVAM) == true
    val elevenLabsConfigured = credentialConfiguration
        ?.isConfigured(VoiceCredentialService.ELEVENLABS) == true
    val openaiConfigured = credentialConfiguration
        ?.isConfigured(VoiceCredentialService.OPENAI) == true
    val taraAvailable = BuildConfig.TARA_REALTIME_ENDPOINT.isNotBlank()

    fun refreshAuth() {
        isSignedIn = AuthManager.isSignedIn
        accountName = AuthManager.displayName
        accountEmail = AuthManager.email
    }

    fun choose(model: InstalledModelReference) {
        val available = availableRecognizerModels(context)
        val resolved = available.firstOrNull { it.path == model.path } ?: model
        prefs.setModelsOrder(
            buildList {
                add(resolved)
                modelsOrder.filterTo(this) { it.path != resolved.path }
                available.filterTo(this) { candidate -> none { it.path == candidate.path } }
            },
        )
        prefs.lastSelectedModelPath.set(resolved.path)
    }

    fun updateCredential(dialog: VoiceServiceDialog, replacement: String?) {
        textDialog = null
        credentialError = null
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                if (replacement == null) {
                    credentialVault.clear(dialog.service)
                } else {
                    credentialVault.set(dialog.service, replacement)
                }
            }
            if (success) {
                credentialRefresh++
            } else {
                credentialError = context.getString(R.string.voice_settings_key_storage_error)
            }
        }
    }

    LaunchedEffect(
        sarvamConfigured,
        elevenLabsConfigured,
        openaiConfigured,
        isSignedIn,
        selectedPath,
        localRefresh,
    ) {
        syncRecognizerOrder(prefs, availableRecognizerModels(context))
    }

    SearchScreen<Unit>(
        onClickBack = onClickBack,
        title = { Text(stringResource(R.string.voice_settings_title)) },
        filteredItems = { emptyList() },
        itemContent = {},
        icon = {},
        content = {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    VoicePickerIntro(
                        title = stringResource(R.string.voice_picker_intro_title),
                        body = stringResource(R.string.voice_picker_intro_body),
                    )
                    credentialError?.let { message ->
                        Column {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            if (credentialConfiguration?.storageError == true) {
                                TextButton(
                                    enabled = !credentialResetBusy,
                                    onClick = { showCredentialResetConfirmation = true },
                                ) {
                                    Text(
                                        stringResource(
                                            if (credentialResetBusy) {
                                                R.string.voice_settings_reset_key_storage_working
                                            } else {
                                                R.string.voice_settings_reset_key_storage
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }

                    VoiceSectionTitle(
                        title = stringResource(R.string.voice_picker_online_title),
                        description = stringResource(R.string.voice_picker_online_summary),
                    )
                    if (taraAvailable) {
                        VoiceChoiceCard(
                            model = VoiceChoiceUiModel(
                                stableId = AndroidRecognizerProviders.TARA_REALTIME_PATH,
                                title = stringResource(R.string.voice_choice_tara_title),
                                description = stringResource(R.string.voice_choice_tara_summary),
                                location = VoiceChoiceLocation.ONLINE,
                                badges = listOf(
                                    stringResource(R.string.voice_badge_uses_internet),
                                    stringResource(R.string.voice_badge_hindi_english),
                                    stringResource(R.string.voice_badge_after_you_stop),
                                    stringResource(R.string.voice_badge_no_service_key),
                                ),
                                fitLabel = stringResource(R.string.voice_fit_natural_hinglish),
                                selected = selectedPath == AndroidRecognizerProviders.TARA_REALTIME_PATH,
                                actionLabel = if (isSignedIn) {
                                    stringResource(R.string.voice_action_use)
                                } else {
                                    stringResource(R.string.voice_settings_sign_in)
                                },
                                enabled = !authBusy && activity != null,
                                supportingText = authError,
                            ),
                            onAction = {
                                if (isSignedIn) {
                                    choose(taraReference())
                                } else if (!authBusy && activity != null) {
                                    authBusy = true
                                    authError = null
                                    scope.launch {
                                        val success = AuthManager.signIn(activity)
                                        refreshAuth()
                                        if (success) {
                                            choose(taraReference())
                                        } else {
                                            authError = activity.getString(
                                                R.string.voice_settings_sign_in_failed,
                                            )
                                        }
                                        authBusy = false
                                    }
                                }
                            },
                        )
                    }
                    VoiceChoiceCard(
                        model = VoiceChoiceUiModel(
                            stableId = SARVAM_PATH,
                            title = stringResource(R.string.voice_choice_sarvam_title),
                            description = stringResource(R.string.voice_choice_sarvam_summary),
                            location = VoiceChoiceLocation.ONLINE,
                            badges = listOf(
                                stringResource(R.string.voice_badge_uses_internet),
                                stringResource(R.string.voice_badge_hindi_english),
                                stringResource(R.string.voice_badge_live_results),
                            ),
                            fitLabel = stringResource(R.string.voice_fit_best_mixed_speech),
                            selected = selectedPath == SARVAM_PATH,
                            actionLabel = if (!sarvamConfigured) {
                                stringResource(R.string.voice_action_connect)
                            } else {
                                stringResource(R.string.voice_action_use)
                            },
                            actionStyle = VoiceChoiceActionStyle.PRIMARY,
                            supportingText = if (!sarvamConfigured) {
                                stringResource(R.string.voice_choice_key_needed)
                            } else null,
                        ),
                        onAction = {
                            if (!sarvamConfigured) {
                                textDialog = VoiceServiceDialog.Sarvam(sarvamConfigured)
                            } else {
                                choose(sarvamReference())
                            }
                        },
                    )
                    VoiceChoiceCard(
                        model = VoiceChoiceUiModel(
                            stableId = AndroidRecognizerProviders.ELEVENLABS_REALTIME_PATH,
                            title = stringResource(R.string.voice_choice_elevenlabs_title),
                            description = stringResource(R.string.voice_choice_elevenlabs_summary),
                            location = VoiceChoiceLocation.ONLINE,
                            badges = listOf(
                                stringResource(R.string.voice_badge_uses_internet),
                                stringResource(R.string.voice_badge_hindi_english),
                                stringResource(R.string.voice_badge_live_results),
                            ),
                            fitLabel = stringResource(R.string.voice_fit_clear_read_speech),
                            selected = selectedPath == AndroidRecognizerProviders.ELEVENLABS_REALTIME_PATH,
                            actionLabel = if (!elevenLabsConfigured) {
                                stringResource(R.string.voice_action_connect)
                            } else {
                                stringResource(R.string.voice_action_use)
                            },
                            supportingText = if (!elevenLabsConfigured) {
                                stringResource(R.string.voice_choice_key_needed)
                            } else null,
                        ),
                        onAction = {
                            if (!elevenLabsConfigured) {
                                textDialog = VoiceServiceDialog.ElevenLabs(elevenLabsConfigured)
                            } else {
                                choose(elevenLabsReference())
                            }
                        },
                    )
                    VoiceChoiceCard(
                        model = VoiceChoiceUiModel(
                            stableId = PROXIED_SARVAM_PATH,
                            title = stringResource(R.string.voice_choice_speakkeys_online_title),
                            description = stringResource(R.string.voice_choice_speakkeys_online_summary),
                            location = VoiceChoiceLocation.ONLINE,
                            badges = listOf(
                                stringResource(R.string.voice_badge_uses_internet),
                                stringResource(R.string.voice_badge_no_service_key),
                            ),
                            selected = selectedPath == PROXIED_SARVAM_PATH,
                            actionLabel = if (isSignedIn) {
                                stringResource(R.string.voice_action_use)
                            } else {
                                stringResource(R.string.voice_settings_sign_in)
                            },
                            enabled = !authBusy && activity != null,
                            supportingText = authError,
                        ),
                        onAction = {
                            if (isSignedIn) {
                                choose(proxiedSarvamReference())
                            } else if (!authBusy && activity != null) {
                                authBusy = true
                                authError = null
                                scope.launch {
                                    val success = AuthManager.signIn(activity)
                                    refreshAuth()
                                    if (success) {
                                        choose(proxiedSarvamReference())
                                    } else {
                                        authError = activity.getString(R.string.voice_settings_sign_in_failed)
                                    }
                                    authBusy = false
                                }
                            }
                        },
                    )

                    VoiceSectionTitle(
                        title = stringResource(R.string.voice_picker_on_phone_title),
                        description = stringResource(R.string.voice_picker_on_phone_summary),
                    )
                    PhoneOfflineChoice(
                        snapshot = phoneLanguagePacks,
                        selected = selectedPath == LocalSpeechModelCatalog.ANDROID_ON_DEVICE_ID,
                        onUse = { choose(androidOnDeviceReference()) },
                        onRefresh = phoneLanguagePackManager::refreshSupport,
                        onDownload = phoneLanguagePackManager::downloadMissingLanguagePacks,
                    )

                    LocalModelChoice(
                        repository = localModels,
                        descriptor = LocalSpeechModelCatalog.voskHindiSmall,
                        modelType = ModelType.VoskLocal,
                        selectedPath = selectedPath,
                        title = stringResource(R.string.voice_choice_hindi_offline_title),
                        description = stringResource(R.string.voice_choice_hindi_offline_summary),
                        speechBadge = stringResource(R.string.voice_badge_mostly_hindi),
                        onChoose = ::choose,
                        onRepositoryChanged = { localRefresh++ },
                    )
                    LocalModelChoice(
                        repository = localModels,
                        descriptor = LocalSpeechModelCatalog.voskEnglishIndiaSmall,
                        modelType = ModelType.VoskLocal,
                        selectedPath = selectedPath,
                        title = stringResource(R.string.voice_choice_english_offline_title),
                        description = stringResource(R.string.voice_choice_english_offline_summary),
                        speechBadge = stringResource(R.string.voice_badge_mostly_english),
                        onChoose = ::choose,
                        onRepositoryChanged = { localRefresh++ },
                    )

                    VoiceSectionTitle(
                        title = stringResource(R.string.voice_output_title),
                        description = stringResource(R.string.voice_output_summary),
                    )
                    OutputStylePicker(
                        selected = outputStyle,
                        onSelected = { style ->
                            prefs.voiceOutputStyle.set(style)
                            prefs.sarvamMode.set(if (style == OUTPUT_LATIN) "translit" else "transcribe")
                            prefs.whisperTransliterateToRoman.set(style == OUTPUT_LATIN)
                        },
                    )

                    PreferenceCategory(stringResource(R.string.voice_settings_behavior_category))
                    Preference(
                        name = stringResource(R.string.voice_settings_auto_capitalize_title),
                        description = stringResource(R.string.voice_settings_auto_capitalize_summary),
                        onClick = { prefs.logicAutoCapitalize.set(!autoCapitalize) },
                    ) {
                        Switch(
                            checked = autoCapitalize,
                            onCheckedChange = prefs.logicAutoCapitalize::set,
                        )
                    }

                    ActionRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTechnicalDetails = !showTechnicalDetails }
                            .padding(vertical = 12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.voice_technical_title),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.voice_technical_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = if (showTechnicalDetails) {
                                Icons.Default.ExpandLess
                            } else {
                                Icons.Default.ExpandMore
                            },
                            contentDescription = null,
                        )
                    }

                    if (showTechnicalDetails) {
                        TechnicalVoiceSettings(
                            isSignedIn = isSignedIn,
                            accountName = accountName,
                            accountEmail = accountEmail,
                            authBusy = authBusy,
                            sarvamConfigured = sarvamConfigured,
                            elevenLabsConfigured = elevenLabsConfigured,
                            openaiConfigured = openaiConfigured,
                            onDialog = { textDialog = it },
                            onAccountAction = {
                                if (!authBusy && activity != null) {
                                    authBusy = true
                                    scope.launch {
                                        if (isSignedIn) AuthManager.signOut(activity)
                                        else AuthManager.signIn(activity)
                                        refreshAuth()
                                        authBusy = false
                                    }
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        },
    )

    textDialog?.let { dialog ->
        TextInputDialog(
            onDismissRequest = { textDialog = null },
            onConfirmed = { value ->
                updateCredential(dialog, value.trim())
            },
            onNeutral = { updateCredential(dialog, null) },
            neutralButtonText = if (dialog.configured) {
                stringResource(R.string.voice_settings_clear_key)
            } else null,
            confirmButtonText = stringResource(
                if (dialog.configured) {
                    R.string.voice_settings_replace_key
                } else {
                    R.string.voice_settings_save_key
                },
            ),
            title = { Text(stringResource(dialog.title)) },
            description = {
                Text(
                    stringResource(
                        if (dialog.configured) {
                            R.string.voice_settings_replace_key_summary
                        } else {
                            R.string.voice_settings_save_key_summary
                        },
                    ),
                )
            },
            initialText = "",
            singleLine = true,
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            saveTextInInstanceState = false,
            properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
            checkTextValid = { it.isNotBlank() },
        )
    }

    if (showCredentialResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showCredentialResetConfirmation = false },
            title = { Text(stringResource(R.string.voice_settings_reset_key_storage_title)) },
            text = { Text(stringResource(R.string.voice_settings_reset_key_storage_summary)) },
            dismissButton = {
                TextButton(onClick = { showCredentialResetConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCredentialResetConfirmation = false
                        credentialResetBusy = true
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                credentialVault.reset()
                            }
                            credentialResetBusy = false
                            if (success) {
                                credentialRefresh++
                            } else {
                                credentialError = context.getString(
                                    R.string.voice_settings_key_storage_error,
                                )
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.voice_settings_reset_key_storage_confirm))
                }
            },
        )
    }
}

private enum class PhoneOfflineAction { USE, DOWNLOAD, REFRESH, NONE }

@Composable
private fun PhoneOfflineChoice(
    snapshot: AndroidOnDeviceLanguagePackSnapshot,
    selected: Boolean,
    onUse: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: () -> Unit,
) {
    var action = PhoneOfflineAction.NONE
    var actionLabel = stringResource(R.string.voice_action_checking)
    var fitLabel = stringResource(R.string.voice_fit_checking_phone)
    var speechBadge = stringResource(R.string.voice_badge_language_checking)
    var supportingText: String? = null
    var secondaryActionLabel: String? = null
    var secondaryAction: (() -> Unit)? = null
    var progress: Float? = null

    when (val supportState = snapshot.support) {
        AndroidOnDeviceLanguageSupportState.Idle,
        AndroidOnDeviceLanguageSupportState.Checking,
        -> Unit

        is AndroidOnDeviceLanguageSupportState.Unverified -> {
            action = PhoneOfflineAction.USE
            actionLabel = stringResource(R.string.voice_action_use)
            fitLabel = stringResource(R.string.voice_fit_phone_language_unverified)
            speechBadge = stringResource(R.string.voice_badge_mostly_hindi)
            supportingText = stringResource(R.string.voice_phone_language_unverified)
        }

        is AndroidOnDeviceLanguageSupportState.Verified -> {
            val hindi = supportState.support.statusFor("hi-IN")?.availability
                ?: AndroidOnDeviceLanguageAvailability.UNSUPPORTED
            val english = supportState.support.statusFor("en-IN")?.availability
                ?: AndroidOnDeviceLanguageAvailability.UNSUPPORTED
            val bilingualReady = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                hindi == AndroidOnDeviceLanguageAvailability.INSTALLED &&
                english == AndroidOnDeviceLanguageAvailability.INSTALLED

            speechBadge = if (bilingualReady) {
                stringResource(R.string.voice_badge_hindi_english)
            } else {
                stringResource(R.string.voice_badge_mostly_hindi)
            }
            when (hindi) {
                AndroidOnDeviceLanguageAvailability.INSTALLED -> {
                    action = PhoneOfflineAction.USE
                    actionLabel = stringResource(R.string.voice_action_use)
                    fitLabel = if (bilingualReady) {
                        stringResource(R.string.voice_fit_phone_mixed_ready)
                    } else {
                        stringResource(R.string.voice_fit_phone_hindi_ready)
                    }
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        supportingText = stringResource(R.string.voice_phone_hindi_primary_summary)
                    } else if (english == AndroidOnDeviceLanguageAvailability.DOWNLOADABLE) {
                        supportingText = stringResource(R.string.voice_phone_add_english_summary)
                        secondaryActionLabel = stringResource(R.string.voice_action_add_english_pack)
                        secondaryAction = onDownload
                    } else if (english == AndroidOnDeviceLanguageAvailability.PENDING) {
                        supportingText = stringResource(R.string.voice_phone_english_downloading)
                    } else if (english == AndroidOnDeviceLanguageAvailability.UNSUPPORTED) {
                        supportingText = stringResource(R.string.voice_phone_english_unavailable)
                    }
                }

                AndroidOnDeviceLanguageAvailability.PENDING -> {
                    actionLabel = stringResource(R.string.voice_action_phone_downloading)
                    fitLabel = stringResource(R.string.voice_fit_phone_pack_pending)
                    supportingText = stringResource(R.string.voice_phone_pack_pending)
                }

                AndroidOnDeviceLanguageAvailability.DOWNLOADABLE -> {
                    action = PhoneOfflineAction.DOWNLOAD
                    actionLabel = stringResource(R.string.voice_action_get_language_packs)
                    fitLabel = stringResource(R.string.voice_fit_phone_needs_pack)
                    supportingText = stringResource(R.string.voice_phone_pack_needed)
                }

                AndroidOnDeviceLanguageAvailability.UNSUPPORTED -> {
                    actionLabel = stringResource(R.string.voice_action_not_available)
                    fitLabel = stringResource(R.string.voice_fit_unavailable_phone)
                    supportingText = stringResource(R.string.voice_phone_hindi_unavailable)
                }
            }
        }

        is AndroidOnDeviceLanguageSupportState.Unavailable -> {
            actionLabel = stringResource(R.string.voice_action_not_available)
            fitLabel = stringResource(R.string.voice_fit_unavailable_phone)
            speechBadge = stringResource(R.string.voice_badge_language_varies)
            supportingText = stringResource(R.string.voice_phone_service_unavailable)
        }

        is AndroidOnDeviceLanguageSupportState.Error -> {
            action = PhoneOfflineAction.REFRESH
            actionLabel = stringResource(R.string.voice_action_check_again)
            fitLabel = stringResource(R.string.voice_fit_phone_check_failed)
            speechBadge = stringResource(R.string.voice_badge_language_varies)
            supportingText = stringResource(R.string.voice_phone_check_failed)
        }

        AndroidOnDeviceLanguageSupportState.Closed -> {
            actionLabel = stringResource(R.string.voice_action_not_available)
            fitLabel = stringResource(R.string.voice_fit_unavailable_phone)
            speechBadge = stringResource(R.string.voice_badge_language_varies)
        }
    }

    when (val downloadState = snapshot.download) {
        AndroidOnDeviceLanguageDownloadState.Idle -> Unit
        is AndroidOnDeviceLanguageDownloadState.Starting -> {
            action = PhoneOfflineAction.NONE
            actionLabel = stringResource(R.string.voice_action_phone_downloading)
            supportingText = stringResource(R.string.voice_phone_download_starting)
            progress = 0.03f
        }
        is AndroidOnDeviceLanguageDownloadState.Progress -> {
            val overallPercent = overallAndroidLanguageDownloadPercent(
                processedLanguageCount = downloadState.processedLanguageCount,
                currentLanguagePercent = downloadState.completedPercent,
                totalLanguageCount = downloadState.totalLanguageCount,
            )
            action = PhoneOfflineAction.NONE
            actionLabel = stringResource(
                R.string.voice_action_downloading,
                overallPercent,
            )
            supportingText = stringResource(R.string.voice_phone_download_in_progress)
            progress = overallPercent / 100f
        }
        is AndroidOnDeviceLanguageDownloadState.Requested,
        is AndroidOnDeviceLanguageDownloadState.Scheduled,
        -> {
            action = PhoneOfflineAction.REFRESH
            actionLabel = stringResource(R.string.voice_action_check_again)
            supportingText = stringResource(R.string.voice_phone_download_requested)
        }
        is AndroidOnDeviceLanguageDownloadState.Complete -> {
            if (downloadState.scheduledLanguageTags.isNotEmpty()) {
                action = PhoneOfflineAction.REFRESH
                actionLabel = stringResource(R.string.voice_action_check_again)
                supportingText = stringResource(R.string.voice_phone_download_requested)
            } else {
                action = PhoneOfflineAction.NONE
                actionLabel = stringResource(R.string.voice_action_checking)
                supportingText = stringResource(R.string.voice_download_ready)
                progress = 1f
            }
        }
        is AndroidOnDeviceLanguageDownloadState.Error -> {
            when (action) {
                PhoneOfflineAction.DOWNLOAD ->
                    actionLabel = stringResource(R.string.voice_action_retry)
                PhoneOfflineAction.USE -> Unit
                PhoneOfflineAction.REFRESH,
                PhoneOfflineAction.NONE,
                -> {
                    action = PhoneOfflineAction.REFRESH
                    actionLabel = stringResource(R.string.voice_action_check_again)
                }
            }
            supportingText = stringResource(R.string.voice_phone_download_failed)
        }
        AndroidOnDeviceLanguageDownloadState.Closed -> action = PhoneOfflineAction.NONE
    }

    VoiceChoiceCard(
        model = VoiceChoiceUiModel(
            stableId = LocalSpeechModelCatalog.ANDROID_ON_DEVICE_ID,
            title = stringResource(R.string.voice_choice_phone_title),
            description = stringResource(R.string.voice_choice_phone_summary),
            location = VoiceChoiceLocation.ON_PHONE,
            badges = listOf(
                stringResource(R.string.voice_badge_works_offline),
                stringResource(R.string.voice_badge_audio_stays_phone),
                speechBadge,
            ),
            fitLabel = fitLabel,
            selected = selected,
            enabled = action != PhoneOfflineAction.NONE,
            actionEnabled = action != PhoneOfflineAction.NONE,
            selectionLocksAction = action == PhoneOfflineAction.USE,
            actionLabel = actionLabel,
            progress = progress,
            supportingText = supportingText,
            secondaryActionLabel = secondaryActionLabel,
            secondaryActionEnabled = snapshot.download is AndroidOnDeviceLanguageDownloadState.Idle,
        ),
        onAction = {
            when (action) {
                PhoneOfflineAction.USE -> onUse()
                PhoneOfflineAction.DOWNLOAD -> onDownload()
                PhoneOfflineAction.REFRESH -> onRefresh()
                PhoneOfflineAction.NONE -> Unit
            }
        },
        onSecondaryAction = secondaryAction,
    )
}

@Composable
private fun LocalModelChoice(
    repository: LocalModelRepository,
    descriptor: LocalSpeechModelDescriptor,
    modelType: ModelType,
    selectedPath: String,
    title: String,
    description: String,
    speechBadge: String,
    onChoose: (InstalledModelReference) -> Unit,
    onRepositoryChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showNetworkChoice by remember { mutableStateOf(false) }
    var showRemoveConfirmation by remember { mutableStateOf(false) }
    var requestedWorkId by rememberSaveable(descriptor.stableId) {
        mutableStateOf(repository.lastRequestedWorkId(descriptor.stableId))
    }
    val workInfos by remember(repository, descriptor.stableId) {
        repository.installWork(descriptor.stableId)
    }.observeLiveDataAsState(emptyList())
    val activeStates = setOf(
        WorkInfo.State.ENQUEUED,
        WorkInfo.State.BLOCKED,
        WorkInfo.State.RUNNING,
    )
    val latestWork = workInfos.firstOrNull { it.id.toString() == requestedWorkId }
        ?: workInfos.firstOrNull { it.state in activeStates }
        ?: workInfos.maxByOrNull { it.id.toString() }
    val installed = repository.installedModel(descriptor.stableId) != null
    val selected = selectedPath == descriptor.stableId
    val activeDownload = latestWork?.state in activeStates
    val resumableBytes = repository.resumableArchiveBytes(descriptor.stableId)
    val deviceFit = deviceFit(context, descriptor, resumableBytes)
    val phaseProgress = latestWork?.progress?.let { data ->
        val completed = data.getLong(LocalModelInstallWorker.KEY_COMPLETED_BYTES, 0L)
        val total = data.getLong(LocalModelInstallWorker.KEY_TOTAL_BYTES, 0L)
        if (total > 0L) completed.toFloat() / total.toFloat() else null
    }
    val phase = latestWork?.progress?.getString(LocalModelInstallWorker.KEY_PHASE)
        ?.let { runCatching { LocalModelInstallPhase.valueOf(it) }.getOrNull() }
    val progress = overallInstallProgress(descriptor, phase, phaseProgress)

    LaunchedEffect(latestWork?.state) {
        if (latestWork?.state == WorkInfo.State.SUCCEEDED) onRepositoryChanged()
    }

    val mebibyte = 1024L * 1024L
    val sizeMb = (((descriptor.artifact?.archiveBytes ?: 0L) + mebibyte / 2L) / mebibyte).toInt()
    val installedSizeMb = (((descriptor.artifact?.extractedBytes ?: 0L) + mebibyte / 2L) / mebibyte).toInt()
    val actionLabel = when {
        installed -> stringResource(R.string.voice_action_use)
        activeDownload -> stringResource(
            R.string.voice_action_downloading,
            ((progress ?: 0f) * 100).toInt().coerceIn(0, 100),
        )
        latestWork?.state == WorkInfo.State.FAILED -> stringResource(R.string.voice_action_retry)
        resumableBytes > 0L -> stringResource(R.string.voice_action_resume_download)
        else -> stringResource(R.string.voice_action_download_size, sizeMb)
    }
    val support = when {
        latestWork?.state == WorkInfo.State.BLOCKED -> stringResource(R.string.voice_download_waiting)
        activeDownload && phase != null -> installPhaseLabel(phase)
        latestWork?.state == WorkInfo.State.FAILED -> installFailureLabel(
            latestWork.outputData.getString(LocalModelInstallWorker.KEY_ERROR),
        )
        else -> null
    }

    VoiceChoiceCard(
        model = VoiceChoiceUiModel(
            stableId = descriptor.stableId,
            title = title,
            description = description,
            location = VoiceChoiceLocation.ON_PHONE,
            badges = listOf(
                stringResource(R.string.voice_badge_works_offline),
                stringResource(R.string.voice_badge_audio_stays_phone),
                speechBadge,
                stringResource(R.string.voice_badge_size_mb, sizeMb),
            ),
            fitLabel = if (installed) {
                stringResource(R.string.voice_download_ready)
            } else {
                deviceFit.label
            },
            selected = selected,
            enabled = installed || activeDownload || deviceFit.canInstall,
            actionEnabled = !activeDownload && (installed || deviceFit.canInstall),
            actionLabel = actionLabel,
            progress = if (activeDownload) progress ?: 0.03f else null,
            supportingText = support,
            secondaryActionLabel = when {
                activeDownload -> stringResource(R.string.voice_action_pause_download)
                installed && !selected -> stringResource(R.string.voice_action_remove_download)
                else -> null
            },
        ),
        onAction = {
            if (installed) {
                onChoose(
                    InstalledModelReference(
                        path = descriptor.stableId,
                        name = descriptor.displayName,
                        type = modelType,
                    ),
                )
            } else if (!activeDownload && deviceFit.canInstall) {
                showNetworkChoice = true
            }
        },
        onSecondaryAction = if (activeDownload) {
            {
                repository.pauseInstall(descriptor.stableId)
                onRepositoryChanged()
            }
        } else if (installed && !selected) {
            { showRemoveConfirmation = true }
        } else null,
    )

    if (showNetworkChoice) {
        AlertDialog(
            onDismissRequest = { showNetworkChoice = false },
            title = { Text(stringResource(R.string.voice_download_network_title)) },
            text = {
                Text(stringResource(R.string.voice_download_network_summary, sizeMb))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        requestedWorkId = repository.enqueueInstall(
                            descriptor.stableId,
                            allowMeteredNetwork = false,
                        ).toString()
                        showNetworkChoice = false
                    },
                ) {
                    Text(stringResource(R.string.voice_download_wifi_only))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        requestedWorkId = repository.enqueueInstall(
                            descriptor.stableId,
                            allowMeteredNetwork = true,
                        ).toString()
                        showNetworkChoice = false
                    },
                ) {
                    Text(stringResource(R.string.voice_download_any_network))
                }
            },
        )
    }

    if (showRemoveConfirmation) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirmation = false },
            title = { Text(stringResource(R.string.voice_remove_model_title)) },
            text = { Text(stringResource(R.string.voice_remove_model_summary, installedSizeMb)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveConfirmation = false
                        scope.launch {
                            repository.delete(descriptor.stableId)
                            onRepositoryChanged()
                        }
                    },
                ) {
                    Text(stringResource(R.string.voice_action_remove_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun OutputStylePicker(
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutputStyleCard(
            title = stringResource(R.string.voice_output_mixed_title),
            description = stringResource(R.string.voice_output_mixed_summary),
            selected = selected != OUTPUT_LATIN,
            onClick = { onSelected(OUTPUT_MIXED) },
        )
        OutputStyleCard(
            title = stringResource(R.string.voice_output_latin_title),
            description = stringResource(R.string.voice_output_latin_summary),
            selected = selected == OUTPUT_LATIN,
            onClick = { onSelected(OUTPUT_LATIN) },
        )
    }
}

@Composable
private fun OutputStyleCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VoiceSectionTitle(title: String, description: String) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TechnicalVoiceSettings(
    isSignedIn: Boolean,
    accountName: String?,
    accountEmail: String?,
    authBusy: Boolean,
    sarvamConfigured: Boolean,
    elevenLabsConfigured: Boolean,
    openaiConfigured: Boolean,
    onDialog: (VoiceServiceDialog) -> Unit,
    onAccountAction: () -> Unit,
) {
    val configured = stringResource(R.string.voice_settings_secret_set)
    val notSet = stringResource(R.string.voice_settings_not_set)
    PreferenceCategory(stringResource(R.string.voice_benchmark_title))
    Text(
        text = stringResource(R.string.voice_benchmark_summary),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
    PreferenceCategory(stringResource(R.string.voice_settings_api_category))
    Preference(
        name = stringResource(R.string.voice_settings_sarvam_api_key_title),
        description = if (sarvamConfigured) configured else notSet,
        onClick = { onDialog(VoiceServiceDialog.Sarvam(sarvamConfigured)) },
    )
    Preference(
        name = stringResource(R.string.voice_settings_elevenlabs_api_key_title),
        description = if (elevenLabsConfigured) configured else notSet,
        onClick = { onDialog(VoiceServiceDialog.ElevenLabs(elevenLabsConfigured)) },
    )
    Preference(
        name = stringResource(R.string.voice_settings_openai_api_key_title),
        description = stringResource(
            R.string.voice_settings_legacy_openai_summary,
            if (openaiConfigured) configured else notSet,
        ),
        onClick = { onDialog(VoiceServiceDialog.OpenAi(openaiConfigured)) },
    )
    PreferenceCategory(stringResource(R.string.voice_settings_account_category))
    Preference(
        name = if (isSignedIn) {
            stringResource(R.string.voice_settings_sign_out)
        } else {
            stringResource(R.string.voice_settings_sign_in)
        },
        description = if (isSignedIn) {
            listOfNotNull(accountName, accountEmail).joinToString(" · ")
        } else if (authBusy) {
            stringResource(R.string.voice_settings_account_working)
        } else {
            stringResource(R.string.voice_settings_account_signed_out_summary)
        },
        onClick = { if (!authBusy) onAccountAction() },
    )
}

@Composable
private fun installPhaseLabel(phase: LocalModelInstallPhase): String = when (phase) {
    LocalModelInstallPhase.CHECKING -> stringResource(R.string.voice_download_checking)
    LocalModelInstallPhase.DOWNLOADING -> stringResource(R.string.voice_download_downloading)
    LocalModelInstallPhase.VERIFYING -> stringResource(R.string.voice_download_verifying)
    LocalModelInstallPhase.EXTRACTING -> stringResource(R.string.voice_download_installing)
    LocalModelInstallPhase.INSTALLING -> stringResource(R.string.voice_download_installing)
    LocalModelInstallPhase.COMPLETE -> stringResource(R.string.voice_download_ready)
}

@Composable
private fun installFailureLabel(errorName: String?): String {
    val error = errorName?.let { runCatching { LocalModelInstallError.valueOf(it) }.getOrNull() }
    return when (error) {
        LocalModelInstallError.NOT_ENOUGH_SPACE ->
            stringResource(R.string.voice_download_failed_storage)
        LocalModelInstallError.NETWORK,
        LocalModelInstallError.HTTP,
        -> stringResource(R.string.voice_download_failed_network)
        LocalModelInstallError.INTEGRITY,
        LocalModelInstallError.UNSAFE_ARCHIVE,
        LocalModelInstallError.INVALID_MODEL_LAYOUT,
        -> stringResource(R.string.voice_download_failed_safety)
        LocalModelInstallError.FILESYSTEM ->
            stringResource(R.string.voice_download_failed_filesystem)
        LocalModelInstallError.UNKNOWN_MODEL,
        LocalModelInstallError.NOT_DOWNLOADABLE,
        null,
        -> stringResource(R.string.voice_download_failed)
    }
}

internal fun overallInstallProgress(
    descriptor: LocalSpeechModelDescriptor,
    phase: LocalModelInstallPhase?,
    phaseProgress: Float?,
): Float? {
    val artifact = descriptor.artifact ?: return null
    val total = artifact.archiveBytes + artifact.extractedBytes
    if (total <= 0L) return phaseProgress
    val downloadWeight = artifact.archiveBytes.toFloat() / total.toFloat()
    return when (phase) {
        LocalModelInstallPhase.CHECKING -> 0f
        LocalModelInstallPhase.DOWNLOADING -> downloadWeight * (phaseProgress ?: 0f)
        LocalModelInstallPhase.VERIFYING -> downloadWeight
        LocalModelInstallPhase.EXTRACTING ->
            downloadWeight + (1f - downloadWeight) * (phaseProgress ?: 0f)
        LocalModelInstallPhase.INSTALLING,
        LocalModelInstallPhase.COMPLETE,
        -> 1f
        null -> return null
    }.coerceIn(0f, 1f)
}

private data class LocalDeviceFit(
    val label: String,
    val canInstall: Boolean,
)

@Composable
private fun deviceFit(
    context: Context,
    descriptor: LocalSpeechModelDescriptor,
    resumableArchiveBytes: Long,
): LocalDeviceFit {
    val memoryClass = remember(context) {
        context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 0
    }
    val artifact = descriptor.artifact
    val required = requiredInstallBytes(descriptor, resumableArchiveBytes)
    // StatFs is intentionally read again on recomposition: downloads and deletions change it.
    val hasStorage = runCatching {
        StatFs(context.noBackupFilesDir.absolutePath).availableBytes >= required
    }.getOrDefault(false)
    return when {
        !hasStorage -> LocalDeviceFit(
            stringResource(R.string.voice_fit_needs_storage),
            canInstall = false,
        )
        memoryClass >= 384 -> LocalDeviceFit(
            stringResource(R.string.voice_fit_great),
            canInstall = true,
        )
        else -> LocalDeviceFit(
            stringResource(R.string.voice_fit_may_feel_slow),
            canInstall = true,
        )
    }
}

internal fun requiredInstallBytes(
    descriptor: LocalSpeechModelDescriptor,
    resumableArchiveBytes: Long,
): Long {
    val artifact = descriptor.artifact ?: return 0L
    val reusable = resumableArchiveBytes.coerceIn(0L, artifact.archiveBytes)
    return artifact.archiveBytes - reusable + artifact.extractedBytes + 64L * 1024L * 1024L
}

private fun availableRecognizerModels(context: Context): List<InstalledModelReference> =
    AndroidRecognizerProviders(
        context,
        AndroidPreferencesRepository(),
        AndroidAuthTokenProvider(),
    ).installedModels()

private fun syncRecognizerOrder(
    prefs: VoicePrefs,
    installedModels: List<InstalledModelReference>,
) {
    val currentOrder = prefs.modelsOrder.get()
    val installedByPath = installedModels.associateBy(InstalledModelReference::path)
    val updated = currentOrder.mapNotNull { installedByPath[it.path] }.toMutableList().apply {
        installedModels.forEach { model -> if (none { it.path == model.path }) add(model) }
    }
    if (updated != currentOrder) prefs.setModelsOrder(updated)
    if (updated.none { it.path == prefs.lastSelectedModelPath.get() }) {
        prefs.lastSelectedModelPath.set(updated.firstOrNull()?.path.orEmpty())
    }
}

private fun sarvamReference() = InstalledModelReference(
    path = SARVAM_PATH,
    name = "Strong for Hindi + English",
    type = ModelType.SarvamCloud,
)

private fun taraReference() = InstalledModelReference(
    path = AndroidRecognizerProviders.TARA_REALTIME_PATH,
    name = "Natural Hinglish",
    type = ModelType.ProxiedWhisperCloud,
)

private fun elevenLabsReference() = InstalledModelReference(
    path = AndroidRecognizerProviders.ELEVENLABS_REALTIME_PATH,
    name = "Detailed Hindi + English",
    type = ModelType.ElevenLabsCloud,
)

private fun proxiedSarvamReference() = InstalledModelReference(
    path = PROXIED_SARVAM_PATH,
    name = "SpeakKeys online",
    type = ModelType.ProxiedSarvamCloud,
)

private fun androidOnDeviceReference() = InstalledModelReference(
    path = LocalSpeechModelCatalog.ANDROID_ON_DEVICE_ID,
    name = "Phone's offline speech",
    type = ModelType.AndroidOnDevice,
)

private sealed class VoiceServiceDialog(
    val title: Int,
    val service: VoiceCredentialService,
    val configured: Boolean,
) {
    class Sarvam(configured: Boolean) : VoiceServiceDialog(
        R.string.voice_settings_sarvam_api_key_title,
        VoiceCredentialService.SARVAM,
        configured,
    )
    class ElevenLabs(configured: Boolean) : VoiceServiceDialog(
        R.string.voice_settings_elevenlabs_api_key_title,
        VoiceCredentialService.ELEVENLABS,
        configured,
    )
    class OpenAi(configured: Boolean) : VoiceServiceDialog(
        R.string.voice_settings_openai_api_key_title,
        VoiceCredentialService.OPENAI,
        configured,
    )
}

private const val SARVAM_PATH = "sarvam://cloud"
private const val PROXIED_SARVAM_PATH = "proxied://sarvam"
private const val OUTPUT_MIXED = "mixed"
private const val OUTPUT_LATIN = "latin"
