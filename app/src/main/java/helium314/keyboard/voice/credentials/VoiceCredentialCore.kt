// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.credentials

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val MAXIMUM_CREDENTIAL_BYTES = 16 * 1024

private fun normalizeCredential(value: String): String = value.trim().also { normalized ->
    require(normalized.toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_CREDENTIAL_BYTES) {
        "Credential is too large"
    }
}

/** Credentials accepted by the Android voice-provider registry. */
enum class VoiceCredentialService(internal val wireId: Int) {
    OPENAI(1),
    SARVAM(2),
    ELEVENLABS(3),
    ;

    internal companion object {
        private val byWireId = entries.associateBy(VoiceCredentialService::wireId)

        fun fromWireId(wireId: Int): VoiceCredentialService? = byWireId[wireId]
    }
}

internal data class VoiceCredentialPayload(
    val legacyMigrationVersion: Int = 0,
    val credentials: Map<VoiceCredentialService, String> = emptyMap(),
) {
    init {
        require(legacyMigrationVersion >= 0) { "Credential migration version cannot be negative" }
        require(credentials.values.none(String::isBlank)) { "Blank credentials must not be persisted" }
        require(
            credentials.values.all {
                it.toByteArray(StandardCharsets.UTF_8).size <= MAXIMUM_CREDENTIAL_BYTES
            },
        ) { "Credential is too large" }
    }

    fun credential(service: VoiceCredentialService): String = credentials[service].orEmpty()

    fun withCredential(service: VoiceCredentialService, value: String): VoiceCredentialPayload {
        val updated = credentials.toMutableMap()
        normalizeCredential(value).takeIf(String::isNotEmpty)
            ?.let { updated[service] = it }
            ?: updated.remove(service)
        return copy(credentials = updated.toMap())
    }
}

/** Small, versioned plaintext payload which is always wrapped by [AesGcmCredentialEnvelope]. */
internal object VoiceCredentialPayloadCodec {
    private const val FORMAT_VERSION = 1
    private const val MAXIMUM_PAYLOAD_BYTES = 64 * 1024

    fun encode(payload: VoiceCredentialPayload): ByteArray {
        val outputBytes = ByteArrayOutputStream()
        DataOutputStream(outputBytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeInt(payload.legacyMigrationVersion)
            val entries = payload.credentials.entries.sortedBy { it.key.wireId }
            output.writeInt(entries.size)
            entries.forEach { (service, value) ->
                val encoded = value.toByteArray(StandardCharsets.UTF_8)
                require(encoded.isNotEmpty() && encoded.size <= MAXIMUM_CREDENTIAL_BYTES) {
                    "Credential has an invalid encoded length"
                }
                output.writeInt(service.wireId)
                output.writeInt(encoded.size)
                output.write(encoded)
            }
        }
        return outputBytes.toByteArray().also { encoded ->
            require(encoded.size <= MAXIMUM_PAYLOAD_BYTES) { "Credential payload is too large" }
        }
    }

    fun decode(encoded: ByteArray): VoiceCredentialPayload {
        require(encoded.isNotEmpty() && encoded.size <= MAXIMUM_PAYLOAD_BYTES) {
            "Credential payload has an invalid length"
        }
        return DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            require(input.readInt() == FORMAT_VERSION) { "Unsupported credential payload" }
            val migrationVersion = input.readInt()
            require(migrationVersion >= 0) { "Credential migration version cannot be negative" }
            val count = input.readInt()
            require(count in 0..VoiceCredentialService.entries.size) {
                "Credential payload has an invalid entry count"
            }
            val credentials = mutableMapOf<VoiceCredentialService, String>()
            repeat(count) {
                val service = requireNotNull(VoiceCredentialService.fromWireId(input.readInt())) {
                    "Credential payload contains an unknown service"
                }
                require(service !in credentials) { "Credential payload contains a duplicate service" }
                val length = input.readInt()
                require(length in 1..MAXIMUM_CREDENTIAL_BYTES && length <= input.available()) {
                    "Credential payload contains an invalid value length"
                }
                val value = String(ByteArray(length).also(input::readFully), StandardCharsets.UTF_8)
                require(value.isNotBlank()) { "Credential payload contains a blank value" }
                credentials[service] = value
            }
            require(input.available() == 0) { "Credential payload contains trailing data" }
            VoiceCredentialPayload(migrationVersion, credentials.toMap())
        }
    }
}

/** Authenticated, versioned AES-GCM envelope. No credential text is written outside this envelope. */
internal object AesGcmCredentialEnvelope {
    private const val MAGIC = 0x534B5643 // SKVC
    private const val FORMAT_VERSION = 1
    private const val GCM_TAG_BITS = 128
    private const val MINIMUM_GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val MAXIMUM_ENVELOPE_BYTES = 128 * 1024
    private val associatedData = "SpeakKeys voice credentials\u0000v1".toByteArray(StandardCharsets.UTF_8)

    fun seal(plaintext: ByteArray, secretKey: SecretKey): ByteArray {
        require(plaintext.isNotEmpty() && plaintext.size <= MAXIMUM_ENVELOPE_BYTES) {
            "Credential plaintext has an invalid length"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        cipher.updateAAD(associatedData)
        val iv = requireNotNull(cipher.iv)
        require(iv.size in 12..16) { "Credential cipher returned an invalid IV" }
        val ciphertext = cipher.doFinal(plaintext)

        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(FORMAT_VERSION)
            output.writeInt(iv.size)
            output.writeInt(ciphertext.size)
            output.write(iv)
            output.write(ciphertext)
        }
        return bytes.toByteArray().also { envelope ->
            require(envelope.size <= MAXIMUM_ENVELOPE_BYTES) { "Credential envelope is too large" }
        }
    }

    @Throws(GeneralSecurityException::class)
    fun open(envelope: ByteArray, secretKey: SecretKey): ByteArray {
        if (envelope.isEmpty() || envelope.size > MAXIMUM_ENVELOPE_BYTES) {
            throw GeneralSecurityException("Credential envelope has an invalid length")
        }
        val parsed = try {
            DataInputStream(ByteArrayInputStream(envelope)).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                    throw GeneralSecurityException("Credential envelope has an unsupported header")
                }
                val ivLength = input.readInt()
                val ciphertextLength = input.readInt()
                if (ivLength !in 12..16 || ciphertextLength < MINIMUM_GCM_TAG_BYTES ||
                    ivLength + ciphertextLength != input.available()
                ) {
                    throw GeneralSecurityException("Credential envelope has invalid framing")
                }
                val iv = ByteArray(ivLength).also(input::readFully)
                val ciphertext = ByteArray(ciphertextLength).also(input::readFully)
                if (input.available() != 0) {
                    throw GeneralSecurityException("Credential envelope contains trailing data")
                }
                iv to ciphertext
            }
        } catch (failure: GeneralSecurityException) {
            throw failure
        } catch (failure: Exception) {
            throw GeneralSecurityException("Credential envelope could not be parsed", failure)
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, parsed.first))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(parsed.second)
    }
}

internal interface VoiceCredentialSecureStore {
    /** False during Direct Boot. Implementations must not touch protected storage then. */
    fun isAvailable(): Boolean

    fun read(): VoiceCredentialPayload?

    fun write(payload: VoiceCredentialPayload)

    /** Irreversibly removes this vault's ciphertext and encryption key. */
    fun reset()
}

internal interface LegacyVoiceCredentialStore {
    fun read(): Map<VoiceCredentialService, String>

    fun clear()
}

internal sealed interface VoiceCredentialAccess {
    data class Available(val payload: VoiceCredentialPayload) : VoiceCredentialAccess
    data object Unavailable : VoiceCredentialAccess
    data object Error : VoiceCredentialAccess
}

/**
 * Transaction boundary for one-time legacy import and subsequent vault updates.
 *
 * The migration marker is committed in the encrypted payload before legacy values are cleared. If
 * the process dies between those operations, stale legacy text can be cleared on the next access
 * without ever resurrecting a key the user later removed from the vault.
 */
internal class VoiceCredentialCoordinator(
    private val secureStore: VoiceCredentialSecureStore,
    private val legacyStore: LegacyVoiceCredentialStore,
) {
    private var cachedPayload: VoiceCredentialPayload? = null
    private var legacyClearPending = false

    @Synchronized
    fun access(): VoiceCredentialAccess {
        val available = try {
            secureStore.isAvailable()
        } catch (_: Exception) {
            false
        }
        if (!available) return VoiceCredentialAccess.Unavailable

        return try {
            VoiceCredentialAccess.Available(loadAndMigrate())
        } catch (_: Exception) {
            VoiceCredentialAccess.Error
        }
    }

    @Synchronized
    fun set(service: VoiceCredentialService, value: String): Boolean {
        val access = access() as? VoiceCredentialAccess.Available ?: return false
        return try {
            val updated = access.payload.withCredential(service, value)
            secureStore.write(updated)
            cachedPayload = updated
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Explicit recovery for a vault that can no longer be opened.
     *
     * Legacy plaintext is cleared first so a failed reinitialization can never resurrect an old
     * service key. Callers must obtain confirmation from the user before invoking this method.
     */
    @Synchronized
    fun reset(): Boolean {
        val available = try {
            secureStore.isAvailable()
        } catch (_: Exception) {
            false
        }
        if (!available) return false

        return try {
            // Unlike best-effort post-migration cleanup, reset must not proceed if legacy keys
            // cannot be cleared: otherwise a failed vault write could import them again later.
            legacyStore.clear()
            secureStore.reset()
            val emptyPayload = VoiceCredentialPayload(
                legacyMigrationVersion = LEGACY_MIGRATION_VERSION,
            )
            secureStore.write(emptyPayload)
            cachedPayload = emptyPayload
            legacyClearPending = false
            true
        } catch (_: Exception) {
            cachedPayload = null
            false
        }
    }

    private fun loadAndMigrate(): VoiceCredentialPayload {
        cachedPayload?.let { payload ->
            if (legacyClearPending) clearLegacyBestEffort()
            return payload
        }
        var payload = secureStore.read() ?: VoiceCredentialPayload()
        if (payload.legacyMigrationVersion < LEGACY_MIGRATION_VERSION) {
            val merged = payload.credentials.toMutableMap()
            legacyStore.read().forEach { (service, value) ->
                runCatching { normalizeCredential(value) }
                    .getOrNull()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { merged.putIfAbsent(service, it) }
            }
            payload = VoiceCredentialPayload(
                legacyMigrationVersion = LEGACY_MIGRATION_VERSION,
                credentials = merged.toMap(),
            )
            // Commit the marker and imported keys atomically before touching legacy storage.
            secureStore.write(payload)
        }
        // Clearing is idempotent. A failure here must not make the successfully migrated vault
        // unavailable; the encrypted migration marker prevents a later stale-key re-import.
        clearLegacyBestEffort()
        cachedPayload = payload
        return payload
    }

    private fun clearLegacyBestEffort() {
        legacyClearPending = runCatching(legacyStore::clear).isFailure
    }

    companion object {
        internal const val LEGACY_MIGRATION_VERSION = 1
    }
}
