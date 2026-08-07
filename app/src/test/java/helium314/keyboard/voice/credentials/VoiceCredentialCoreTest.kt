// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.credentials

import java.security.GeneralSecurityException
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class VoiceCredentialCoreTest {
    @Test
    fun payloadCodecRoundTripsEveryCredentialWithoutPlaintextEnvelopeMetadata() {
        val payload = VoiceCredentialPayload(
            legacyMigrationVersion = 1,
            credentials = mapOf(
                VoiceCredentialService.OPENAI to "openai-secret",
                VoiceCredentialService.SARVAM to "sarvam-secret",
                VoiceCredentialService.ELEVENLABS to "eleven-secret",
            ),
        )

        assertEquals(payload, VoiceCredentialPayloadCodec.decode(VoiceCredentialPayloadCodec.encode(payload)))
    }

    @Test
    fun aesGcmEnvelopeAuthenticatesCiphertextAndAssociatedData() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val plaintext = VoiceCredentialPayloadCodec.encode(
            VoiceCredentialPayload(credentials = mapOf(VoiceCredentialService.SARVAM to "secret")),
        )
        val envelope = AesGcmCredentialEnvelope.seal(plaintext, key)

        assertFalse(envelope.toString(Charsets.ISO_8859_1).contains("secret"))
        assertTrue(plaintext.contentEquals(AesGcmCredentialEnvelope.open(envelope, key)))

        val tampered = envelope.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertFailsWith<GeneralSecurityException> {
            AesGcmCredentialEnvelope.open(tampered, key)
        }
        assertFailsWith<GeneralSecurityException> {
            AesGcmCredentialEnvelope.open(envelope, SecretKeySpec(ByteArray(32) { 7 }, "AES"))
        }
    }

    @Test
    fun firstUnlockedAccessImportsLegacyKeysThenClearsPlaintext() {
        val secure = FakeSecureStore()
        val legacy = FakeLegacyStore(
            mutableMapOf(
                VoiceCredentialService.SARVAM to " sarvam-secret ",
                VoiceCredentialService.ELEVENLABS to "eleven-secret",
            ),
        )

        val access = VoiceCredentialCoordinator(secure, legacy).access()

        val payload = assertIs<VoiceCredentialAccess.Available>(access).payload
        assertEquals(1, payload.legacyMigrationVersion)
        assertEquals("sarvam-secret", payload.credential(VoiceCredentialService.SARVAM))
        assertEquals("eleven-secret", payload.credential(VoiceCredentialService.ELEVENLABS))
        assertEquals(payload, secure.writes.single())
        assertTrue(legacy.values.isEmpty())
        assertEquals(1, legacy.clearCalls)
    }

    @Test
    fun existingVaultValueWinsDuringLegacyMigration() {
        val secure = FakeSecureStore(
            payload = VoiceCredentialPayload(
                credentials = mapOf(VoiceCredentialService.SARVAM to "new-secret"),
            ),
        )
        val legacy = FakeLegacyStore(
            mutableMapOf(VoiceCredentialService.SARVAM to "stale-secret"),
        )

        val payload = assertIs<VoiceCredentialAccess.Available>(
            VoiceCredentialCoordinator(secure, legacy).access(),
        ).payload

        assertEquals("new-secret", payload.credential(VoiceCredentialService.SARVAM))
        assertEquals(1, payload.legacyMigrationVersion)
        assertTrue(legacy.values.isEmpty())
    }

    @Test
    fun directBootAccessDoesNotTouchEitherStore() {
        val secure = FakeSecureStore(available = false)
        val legacy = FakeLegacyStore(
            mutableMapOf(VoiceCredentialService.OPENAI to "legacy-secret"),
        )
        val coordinator = VoiceCredentialCoordinator(secure, legacy)

        assertIs<VoiceCredentialAccess.Unavailable>(coordinator.access())
        assertFalse(coordinator.set(VoiceCredentialService.OPENAI, "replacement"))
        assertEquals(0, secure.readCalls)
        assertTrue(secure.writes.isEmpty())
        assertEquals(0, legacy.readCalls)
        assertEquals(0, legacy.clearCalls)
        assertEquals("legacy-secret", legacy.values[VoiceCredentialService.OPENAI])
    }

    @Test
    fun migratedLegacyKeyCannotBeResurrectedAfterUserClearsIt() {
        val secure = FakeSecureStore()
        val legacy = FakeLegacyStore(
            mutableMapOf(VoiceCredentialService.SARVAM to "legacy-secret"),
            clearSucceeds = false,
        )
        val firstProcess = VoiceCredentialCoordinator(secure, legacy)
        assertIs<VoiceCredentialAccess.Available>(firstProcess.access())
        assertTrue(firstProcess.set(VoiceCredentialService.SARVAM, ""))
        assertEquals("legacy-secret", legacy.values[VoiceCredentialService.SARVAM])

        legacy.clearSucceeds = true
        val secondProcess = VoiceCredentialCoordinator(secure, legacy)
        val reloaded = assertIs<VoiceCredentialAccess.Available>(secondProcess.access()).payload

        assertEquals("", reloaded.credential(VoiceCredentialService.SARVAM))
        assertTrue(legacy.values.isEmpty())
        assertEquals(1, secure.payload?.legacyMigrationVersion)
    }

    @Test
    fun failedLegacyClearIsRetriedWithoutReimportingOrRewritingVault() {
        val secure = FakeSecureStore()
        val legacy = FakeLegacyStore(
            mutableMapOf(VoiceCredentialService.OPENAI to "legacy-secret"),
            clearSucceeds = false,
        )
        val coordinator = VoiceCredentialCoordinator(secure, legacy)
        assertIs<VoiceCredentialAccess.Available>(coordinator.access())
        assertEquals(1, secure.writes.size)

        legacy.clearSucceeds = true
        val secondAccess = assertIs<VoiceCredentialAccess.Available>(coordinator.access()).payload

        assertEquals("legacy-secret", secondAccess.credential(VoiceCredentialService.OPENAI))
        assertTrue(legacy.values.isEmpty())
        assertEquals(2, legacy.clearCalls)
        assertEquals(1, secure.writes.size)
    }

    @Test
    fun malformedOversizedLegacyValueIsDroppedInsteadOfBlockingMigration() {
        val secure = FakeSecureStore()
        val legacy = FakeLegacyStore(
            mutableMapOf(VoiceCredentialService.SARVAM to "x".repeat(17 * 1024)),
        )

        val migrated = assertIs<VoiceCredentialAccess.Available>(
            VoiceCredentialCoordinator(secure, legacy).access(),
        ).payload

        assertEquals(1, migrated.legacyMigrationVersion)
        assertEquals("", migrated.credential(VoiceCredentialService.SARVAM))
        assertTrue(legacy.values.isEmpty())
    }

    @Test
    fun confirmedResetRecoversUnreadableVaultWithoutResurrectingLegacyKeys() {
        val secure = FakeSecureStore(readFails = true)
        val legacy = FakeLegacyStore(
            mutableMapOf(VoiceCredentialService.SARVAM to "stale-secret"),
        )
        val coordinator = VoiceCredentialCoordinator(secure, legacy)
        assertIs<VoiceCredentialAccess.Error>(coordinator.access())

        assertTrue(coordinator.reset())

        assertEquals(1, secure.resetCalls)
        assertTrue(legacy.values.isEmpty())
        val recovered = assertIs<VoiceCredentialAccess.Available>(coordinator.access()).payload
        assertEquals(1, recovered.legacyMigrationVersion)
        assertTrue(recovered.credentials.isEmpty())
    }

    @Test
    fun resetWhileLockedDoesNotTouchVaultOrLegacyStorage() {
        val secure = FakeSecureStore(available = false)
        val legacy = FakeLegacyStore(
            mutableMapOf(VoiceCredentialService.OPENAI to "legacy-secret"),
        )

        assertFalse(VoiceCredentialCoordinator(secure, legacy).reset())

        assertEquals(0, secure.resetCalls)
        assertEquals(0, legacy.clearCalls)
        assertEquals("legacy-secret", legacy.values[VoiceCredentialService.OPENAI])
    }

    @Test
    fun resetDoesNotDeleteVaultWhenLegacyKeysCannotBeCleared() {
        val secure = FakeSecureStore(readFails = true)
        val legacy = FakeLegacyStore(
            mutableMapOf(VoiceCredentialService.OPENAI to "legacy-secret"),
            clearSucceeds = false,
        )

        assertFalse(VoiceCredentialCoordinator(secure, legacy).reset())

        assertEquals(0, secure.resetCalls)
        assertEquals("legacy-secret", legacy.values[VoiceCredentialService.OPENAI])
    }

    private class FakeSecureStore(
        var available: Boolean = true,
        var payload: VoiceCredentialPayload? = null,
        var readFails: Boolean = false,
    ) : VoiceCredentialSecureStore {
        var readCalls = 0
        var resetCalls = 0
        val writes = mutableListOf<VoiceCredentialPayload>()

        override fun isAvailable(): Boolean = available

        override fun read(): VoiceCredentialPayload? {
            readCalls++
            if (readFails) error("simulated unreadable vault")
            return payload
        }

        override fun write(payload: VoiceCredentialPayload) {
            this.payload = payload
            writes += payload
        }

        override fun reset() {
            resetCalls++
            payload = null
            readFails = false
        }
    }

    private class FakeLegacyStore(
        val values: MutableMap<VoiceCredentialService, String> = mutableMapOf(),
        var clearSucceeds: Boolean = true,
    ) : LegacyVoiceCredentialStore {
        var readCalls = 0
        var clearCalls = 0

        override fun read(): Map<VoiceCredentialService, String> {
            readCalls++
            return values.toMap()
        }

        override fun clear() {
            clearCalls++
            if (!clearSucceeds) error("simulated clear failure")
            values.clear()
        }
    }
}
