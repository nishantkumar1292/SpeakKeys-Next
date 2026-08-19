// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Last exact language-pack result returned by Android's API 33+ recognition-support check. */
internal enum class CachedAndroidOnDeviceHindiCapability {
    UNKNOWN,
    INSTALLED,
    MISSING,
}

/**
 * Small synchronous bridge between Android's asynchronous support API and the recognizer registry.
 * The device fingerprint prevents a restored backup or system update from carrying a stale result
 * onto a materially different speech stack. Every selected API 33+ source is checked again while
 * it initializes, so this cache only controls discovery and never replaces runtime validation.
 */
internal class AndroidOnDeviceCapabilityStore(
    context: Context,
    private val deviceFingerprint: String = Build.FINGERPRINT,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun hindiCapability(): CachedAndroidOnDeviceHindiCapability = capability(KEY_HINDI_CAPABILITY)

    fun englishCapability(): CachedAndroidOnDeviceHindiCapability = capability(KEY_ENGLISH_CAPABILITY)

    private fun capability(key: String): CachedAndroidOnDeviceHindiCapability {
        if (preferences.getString(KEY_DEVICE_FINGERPRINT, null) != deviceFingerprint) {
            return CachedAndroidOnDeviceHindiCapability.UNKNOWN
        }
        return preferences.getString(key, null)
            ?.let { runCatching { CachedAndroidOnDeviceHindiCapability.valueOf(it) }.getOrNull() }
            ?: CachedAndroidOnDeviceHindiCapability.UNKNOWN
    }

    fun recordHindiCapability(capability: CachedAndroidOnDeviceHindiCapability): Boolean =
        recordCapability(KEY_HINDI_CAPABILITY, capability)

    fun recordEnglishCapability(capability: CachedAndroidOnDeviceHindiCapability): Boolean =
        recordCapability(KEY_ENGLISH_CAPABILITY, capability)

    private fun recordCapability(
        key: String,
        capability: CachedAndroidOnDeviceHindiCapability,
    ): Boolean {
        if (capability(key) == capability) return false
        val sameDevice = preferences.getString(KEY_DEVICE_FINGERPRINT, null) == deviceFingerprint
        preferences.edit {
            if (!sameDevice) {
                remove(KEY_HINDI_CAPABILITY)
                remove(KEY_ENGLISH_CAPABILITY)
            }
            putString(KEY_DEVICE_FINGERPRINT, deviceFingerprint)
            putString(key, capability.name)
        }
        return true
    }

    /** Records only authoritative platform results; transient check failures leave the cache alone. */
    fun recordSupportState(state: AndroidOnDeviceLanguageSupportState): Boolean {
        val support = when (state) {
            is AndroidOnDeviceLanguageSupportState.Verified -> {
                state.support
            }

            is AndroidOnDeviceLanguageSupportState.Unavailable -> null

            AndroidOnDeviceLanguageSupportState.Idle,
            AndroidOnDeviceLanguageSupportState.Checking,
            is AndroidOnDeviceLanguageSupportState.Unverified,
            is AndroidOnDeviceLanguageSupportState.Error,
            AndroidOnDeviceLanguageSupportState.Closed,
            -> return false
        }
        val hindi = support?.installedCapability(HINDI_LANGUAGE_TAG)
            ?: CachedAndroidOnDeviceHindiCapability.MISSING
        val english = support?.installedCapability(ENGLISH_LANGUAGE_TAG)
            ?: CachedAndroidOnDeviceHindiCapability.MISSING
        // Do not short-circuit: both values must be recorded from the same authoritative snapshot.
        val hindiChanged = recordHindiCapability(hindi)
        val englishChanged = recordEnglishCapability(english)
        return hindiChanged || englishChanged
    }

    private companion object {
        const val PREFERENCES_NAME = "android-on-device-speech-capability"
        const val KEY_DEVICE_FINGERPRINT = "device-fingerprint"
        const val KEY_HINDI_CAPABILITY = "hi-in-capability"
        const val KEY_ENGLISH_CAPABILITY = "en-in-capability"
    }
}

private fun AndroidOnDeviceLanguageSupport.installedCapability(
    languageTag: String,
): CachedAndroidOnDeviceHindiCapability =
    if (statusFor(languageTag)?.availability == AndroidOnDeviceLanguageAvailability.INSTALLED) {
        CachedAndroidOnDeviceHindiCapability.INSTALLED
    } else {
        CachedAndroidOnDeviceHindiCapability.MISSING
    }

internal fun shouldRegisterAndroidOnDeviceRecognizer(
    sdkInt: Int,
    serviceAvailable: Boolean,
    cachedHindiCapability: CachedAndroidOnDeviceHindiCapability,
    explicitlySelected: Boolean,
): Boolean {
    if (!serviceAvailable || sdkInt < Build.VERSION_CODES.S) return false
    return if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        cachedHindiCapability == CachedAndroidOnDeviceHindiCapability.INSTALLED
    } else {
        // Android 12–12L cannot report language packs. Do not add this engine to the automatic
        // catalog, but preserve it after a user explicitly accepts the picker's warning.
        explicitlySelected
    }
}

/** Android 14 language switching is safe only when both exact requested packs are installed. */
internal fun shouldEnableAndroidLanguageSwitch(
    sdkInt: Int,
    requested: Boolean,
    hindiCapability: CachedAndroidOnDeviceHindiCapability,
    englishCapability: CachedAndroidOnDeviceHindiCapability,
): Boolean = requested &&
    sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
    hindiCapability == CachedAndroidOnDeviceHindiCapability.INSTALLED &&
    englishCapability == CachedAndroidOnDeviceHindiCapability.INSTALLED

/** Performs the exact hi-IN check again before an API 33+ recognizer becomes ready. */
internal suspend fun verifyAndroidOnDeviceHindiCapability(
    context: Context,
): CachedAndroidOnDeviceHindiCapability {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return CachedAndroidOnDeviceHindiCapability.UNKNOWN
    }
    return withTimeoutOrNull(SUPPORT_CHECK_TIMEOUT_MILLIS) {
        suspendCancellableCoroutine { continuation ->
            var manager: AndroidOnDeviceLanguagePackManager? = null
            manager = AndroidOnDeviceLanguagePackManager(
                context = context,
                requestedLanguageTags = listOf(HINDI_LANGUAGE_TAG),
            ) { snapshot ->
                val result = when (val state = snapshot.support) {
                    is AndroidOnDeviceLanguageSupportState.Verified -> {
                        if (state.support.statusFor(HINDI_LANGUAGE_TAG)?.availability ==
                            AndroidOnDeviceLanguageAvailability.INSTALLED
                        ) {
                            CachedAndroidOnDeviceHindiCapability.INSTALLED
                        } else {
                            CachedAndroidOnDeviceHindiCapability.MISSING
                        }
                    }

                    is AndroidOnDeviceLanguageSupportState.Unavailable ->
                        CachedAndroidOnDeviceHindiCapability.MISSING

                    is AndroidOnDeviceLanguageSupportState.Error ->
                        CachedAndroidOnDeviceHindiCapability.UNKNOWN

                    AndroidOnDeviceLanguageSupportState.Idle,
                    AndroidOnDeviceLanguageSupportState.Checking,
                    is AndroidOnDeviceLanguageSupportState.Unverified,
                    AndroidOnDeviceLanguageSupportState.Closed,
                    -> null
                }
                if (result != null && continuation.isActive) {
                    manager?.close()
                    continuation.resume(result)
                }
            }
            continuation.invokeOnCancellation { manager?.close() }
            manager?.refreshSupport()
        }
    } ?: CachedAndroidOnDeviceHindiCapability.UNKNOWN
}

internal const val HINDI_LANGUAGE_TAG = "hi-IN"
internal const val ENGLISH_LANGUAGE_TAG = "en-IN"
private const val SUPPORT_CHECK_TIMEOUT_MILLIS = 5_000L
