// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.credentials

import android.content.Context
import android.os.Build
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import helium314.keyboard.voice.speakKeysPreferenceModel
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

data class VoiceCredentialConfiguration(
    val storageAvailable: Boolean,
    val configuredServices: Set<VoiceCredentialService> = emptySet(),
    val storageError: Boolean = false,
) {
    fun isConfigured(service: VoiceCredentialService): Boolean = service in configuredServices

    companion object {
        val UNAVAILABLE = VoiceCredentialConfiguration(storageAvailable = false)
    }
}

/**
 * Process-wide credential facade backed by authenticated no-backup ciphertext and an
 * Android-Keystore AES key. Calls made during Direct Boot return unavailable without consulting
 * legacy preferences, vault files, or the Keystore.
 */
class VoiceCredentialVault private constructor(
    private val coordinator: VoiceCredentialCoordinator,
) {
    fun configuration(): VoiceCredentialConfiguration = when (val access = coordinator.access()) {
        is VoiceCredentialAccess.Available -> VoiceCredentialConfiguration(
            storageAvailable = true,
            configuredServices = access.payload.credentials.keys.toSet(),
        )
        VoiceCredentialAccess.Unavailable -> VoiceCredentialConfiguration.UNAVAILABLE
        VoiceCredentialAccess.Error -> VoiceCredentialConfiguration(
            storageAvailable = true,
            storageError = true,
        )
    }

    /** Returns an empty value when locked, corrupt, unavailable, or not configured. */
    internal fun credential(service: VoiceCredentialService): String =
        (coordinator.access() as? VoiceCredentialAccess.Available)
            ?.payload
            ?.credential(service)
            .orEmpty()

    /** Replaces a credential; a blank value explicitly clears it. */
    fun set(service: VoiceCredentialService, value: String): Boolean = coordinator.set(service, value)

    fun clear(service: VoiceCredentialService): Boolean = set(service, "")

    /** Deletes every saved voice-service key after explicit user confirmation. */
    fun reset(): Boolean = coordinator.reset()

    /** Best-effort preload/migration hook suitable for a background startup task. */
    fun prepare() {
        coordinator.access()
    }

    companion object {
        @Volatile
        private var instance: VoiceCredentialVault? = null

        fun get(context: Context): VoiceCredentialVault = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): VoiceCredentialVault = VoiceCredentialVault(
            VoiceCredentialCoordinator(
                secureStore = AndroidKeystoreVoiceCredentialStore(context),
                legacyStore = JetPrefLegacyVoiceCredentialStore(),
            ),
        )
    }
}

private class JetPrefLegacyVoiceCredentialStore : LegacyVoiceCredentialStore {
    private val prefs by speakKeysPreferenceModel()

    override fun read(): Map<VoiceCredentialService, String> = buildMap {
        prefs.openaiApiKey.get().takeIf(String::isNotBlank)?.let {
            put(VoiceCredentialService.OPENAI, it)
        }
        prefs.sarvamApiKey.get().takeIf(String::isNotBlank)?.let {
            put(VoiceCredentialService.SARVAM, it)
        }
        prefs.elevenLabsApiKey.get().takeIf(String::isNotBlank)?.let {
            put(VoiceCredentialService.ELEVENLABS, it)
        }
    }

    override fun clear() {
        if (prefs.openaiApiKey.get().isNotEmpty()) prefs.openaiApiKey.set("")
        if (prefs.sarvamApiKey.get().isNotEmpty()) prefs.sarvamApiKey.set("")
        if (prefs.elevenLabsApiKey.get().isNotEmpty()) prefs.elevenLabsApiKey.set("")
    }
}

private class AndroidKeystoreVoiceCredentialStore(
    private val applicationContext: Context,
) : VoiceCredentialSecureStore {
    override fun isAvailable(): Boolean =
        applicationContext.getSystemService(UserManager::class.java)?.isUserUnlocked == true

    override fun read(): VoiceCredentialPayload? {
        check(isAvailable()) { "Voice credential storage is locked" }
        val file = vaultFile()
        if (!file.isFile) return null
        require(file.length() in 1..MAXIMUM_VAULT_FILE_BYTES) { "Voice credential vault is invalid" }
        val envelope = file.inputStream().buffered().use { it.readBytes() }
        val plaintext = AesGcmCredentialEnvelope.open(envelope, existingKey())
        return VoiceCredentialPayloadCodec.decode(plaintext)
    }

    override fun write(payload: VoiceCredentialPayload) {
        check(isAvailable()) { "Voice credential storage is locked" }
        val plaintext = VoiceCredentialPayloadCodec.encode(payload)
        val envelope = AesGcmCredentialEnvelope.seal(plaintext, existingOrCreateKey())
        val file = vaultFile()
        val directory = requireNotNull(file.parentFile)
        check(directory.mkdirs() || directory.isDirectory) { "Could not create credential storage" }

        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(envelope)
            atomicFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    override fun reset() {
        check(isAvailable()) { "Voice credential storage is locked" }
        AtomicFile(vaultFile()).delete()
        keyStore().run {
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }

    private fun vaultFile(): File {
        // This app deliberately defaults to device-protected storage so the IME can start during
        // Direct Boot, and Android exposes no public API that switches such a Context back to CE.
        // Keep only authenticated ciphertext here; isAvailable() prevents reads before unlock,
        // and API 28+ additionally makes the Keystore key itself unavailable while locked.
        return File(applicationContext.noBackupFilesDir, "$VAULT_DIRECTORY/$VAULT_FILE_NAME")
    }

    private fun existingKey(): SecretKey {
        val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        return requireNotNull(key) { "Voice credential encryption key is missing" }
    }

    private fun existingOrCreateKey(): SecretKey =
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(AES_KEY_BITS)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            setUnlockedDeviceRequired(true)
                        }
                    }
                    .build(),
            )
            generateKey()
        }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "com.speakkeys.keyboard.voice.credentials.aes-gcm.v1"
        private const val AES_KEY_BITS = 256
        private const val VAULT_DIRECTORY = "voice-credentials"
        private const val VAULT_FILE_NAME = "credentials-v1.bin"
        private const val MAXIMUM_VAULT_FILE_BYTES = 128L * 1024L
    }
}
