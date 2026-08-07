// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class AndroidOnDeviceLanguagePacksTest {
    @Test
    fun normalizesCommonBcp47FormattingWithoutBroadeningTheLocale() {
        assertEquals("hi-IN", normalizeAndroidLanguageTag(" HI_in "))
        assertEquals("en-IN", normalizeAndroidLanguageTag("en-in"))
        assertEquals("zh-Hant-TW", normalizeAndroidLanguageTag("zh-hant-tw"))
        assertNull(normalizeAndroidLanguageTag(""))
        assertNull(normalizeAndroidLanguageTag("und"))
        assertNull(normalizeAndroidLanguageTag("not a tag"))
    }

    @Test
    fun derivesEveryRequestedStatusWithDocumentedPrecedence() {
        val result = deriveAndroidOnDeviceLanguageSupport(
            requestedLanguageTags = listOf("hi-IN", "en-IN", "ta-IN", "bn-IN"),
            installedLanguageTags = listOf("HI_in"),
            pendingLanguageTags = listOf("hi-IN", "en-in"),
            supportedLanguageTags = listOf("hi-IN", "en-IN", "ta-in"),
        )

        assertEquals(
            listOf(
                AndroidOnDeviceLanguageStatus(
                    "hi-IN",
                    AndroidOnDeviceLanguageAvailability.INSTALLED,
                ),
                AndroidOnDeviceLanguageStatus(
                    "en-IN",
                    AndroidOnDeviceLanguageAvailability.PENDING,
                ),
                AndroidOnDeviceLanguageStatus(
                    "ta-IN",
                    AndroidOnDeviceLanguageAvailability.DOWNLOADABLE,
                ),
                AndroidOnDeviceLanguageStatus(
                    "bn-IN",
                    AndroidOnDeviceLanguageAvailability.UNSUPPORTED,
                ),
            ),
            result.languages,
        )
        assertEquals(listOf("hi-IN"), result.installedLanguageTags)
        assertEquals(listOf("en-IN"), result.pendingLanguageTags)
        assertEquals(listOf("ta-IN"), result.downloadableLanguageTags)
        assertEquals(listOf("bn-IN"), result.unsupportedLanguageTags)
    }

    @Test
    fun usesExactLocaleMatchingInsteadOfBaseLanguageFallback() {
        val result = deriveAndroidOnDeviceLanguageSupport(
            requestedLanguageTags = listOf("hi-IN", "en-IN"),
            installedLanguageTags = listOf("hi", "en-US"),
            pendingLanguageTags = emptyList(),
            supportedLanguageTags = listOf("en-GB"),
        )

        assertEquals(
            listOf(
                AndroidOnDeviceLanguageAvailability.UNSUPPORTED,
                AndroidOnDeviceLanguageAvailability.UNSUPPORTED,
            ),
            result.languages.map(AndroidOnDeviceLanguageStatus::availability),
        )
    }

    @Test
    fun matchingIsCanonicalCaseInsensitiveAndIgnoresUnknownPlatformTags() {
        val result = deriveAndroidOnDeviceLanguageSupport(
            requestedLanguageTags = listOf("HI-in", "en_in"),
            installedLanguageTags = listOf("not a tag", "EN-IN"),
            pendingLanguageTags = emptyList(),
            supportedLanguageTags = listOf("hi_in"),
        )

        assertEquals(
            AndroidOnDeviceLanguageAvailability.DOWNLOADABLE,
            result.statusFor("hi-IN")?.availability,
        )
        assertEquals(
            AndroidOnDeviceLanguageAvailability.INSTALLED,
            result.statusFor("EN_in")?.availability,
        )
        assertNull(result.statusFor("en-US"))
    }

    @Test
    fun requestedTagsAreCanonicalizedAndDeduplicatedInStableOrder() {
        val result = deriveAndroidOnDeviceLanguageSupport(
            requestedLanguageTags = listOf("hi_in", "HI-IN", "en-in"),
            installedLanguageTags = emptyList(),
            pendingLanguageTags = emptyList(),
            supportedLanguageTags = emptyList(),
        )

        assertEquals(listOf("hi-IN", "en-IN"), result.languages.map { it.languageTag })
    }

    @Test
    fun invalidOrEmptyRequestedTagsFailFast() {
        assertFailsWith<IllegalArgumentException> {
            deriveAndroidOnDeviceLanguageSupport(
                requestedLanguageTags = emptyList(),
                installedLanguageTags = emptyList(),
                pendingLanguageTags = emptyList(),
                supportedLanguageTags = emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            deriveAndroidOnDeviceLanguageSupport(
                requestedLanguageTags = listOf("und"),
                installedLanguageTags = emptyList(),
                pendingLanguageTags = emptyList(),
                supportedLanguageTags = emptyList(),
            )
        }
    }

    @Test
    fun scheduledDownloadsRemainScheduledAtTheEndOfTheQueue() {
        val state = terminalAndroidOnDeviceLanguageDownloadState(
            completedLanguageTags = listOf("hi-IN"),
            scheduledLanguageTags = listOf("en-IN"),
        )

        val scheduled = assertIs<AndroidOnDeviceLanguageDownloadState.Scheduled>(state)
        assertEquals(listOf("hi-IN"), scheduled.completedLanguageTags)
        assertEquals(listOf("en-IN"), scheduled.scheduledLanguageTags)
    }

    @Test
    fun onlyCompletedDownloadsBecomeReady() {
        val state = terminalAndroidOnDeviceLanguageDownloadState(
            completedLanguageTags = listOf("hi-IN", "en-IN"),
            scheduledLanguageTags = emptyList(),
        )

        val complete = assertIs<AndroidOnDeviceLanguageDownloadState.Complete>(state)
        assertEquals(listOf("hi-IN", "en-IN"), complete.completedLanguageTags)
        assertEquals(emptyList(), complete.scheduledLanguageTags)
    }

    @Test
    fun multiLanguageProgressNeverResetsWhenTheQueueAdvances() {
        assertEquals(25, overallAndroidLanguageDownloadPercent(0, 50, 2))
        assertEquals(50, overallAndroidLanguageDownloadPercent(1, 0, 2))
        assertEquals(75, overallAndroidLanguageDownloadPercent(1, 50, 2))
        assertEquals(100, overallAndroidLanguageDownloadPercent(1, 100, 2))
    }

    @Test
    fun overallDownloadProgressClampsUntrustedOemValues() {
        assertEquals(0, overallAndroidLanguageDownloadPercent(-1, -20, 2))
        assertEquals(100, overallAndroidLanguageDownloadPercent(3, 140, 2))
        assertEquals(0, overallAndroidLanguageDownloadPercent(0, 50, 0))
    }

    @Test
    fun silentOemDownloadBecomesCheckAgainWithoutClaimingTheRestOfTheQueue() {
        val state = silentAndroidLanguageDownloadTimeoutState(
            languageTag = "en-IN",
            completedLanguageTags = listOf("hi-IN"),
            scheduledLanguageTags = emptyList(),
        )

        assertEquals(listOf("hi-IN"), state.completedLanguageTags)
        assertEquals(listOf("en-IN"), state.scheduledLanguageTags)
    }
}
