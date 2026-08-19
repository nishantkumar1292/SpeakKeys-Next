// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import android.os.Build
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidOnDeviceCapabilityStoreTest {
    @Test
    fun languageSwitchRequiresBothExactPacksOnAndroid14() {
        val installed = CachedAndroidOnDeviceHindiCapability.INSTALLED
        val missing = CachedAndroidOnDeviceHindiCapability.MISSING

        assertTrue(
            shouldEnableAndroidLanguageSwitch(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                requested = true,
                hindiCapability = installed,
                englishCapability = installed,
            ),
        )
        assertFalse(
            shouldEnableAndroidLanguageSwitch(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                requested = true,
                hindiCapability = installed,
                englishCapability = missing,
            ),
        )
        assertFalse(
            shouldEnableAndroidLanguageSwitch(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                requested = true,
                hindiCapability = installed,
                englishCapability = installed,
            ),
        )
        assertFalse(
            shouldEnableAndroidLanguageSwitch(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                requested = false,
                hindiCapability = installed,
                englishCapability = installed,
            ),
        )
    }

    @Test
    fun api33RequiresVerifiedInstalledHindi() {
        assertTrue(
            shouldRegisterAndroidOnDeviceRecognizer(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                serviceAvailable = true,
                cachedHindiCapability = CachedAndroidOnDeviceHindiCapability.INSTALLED,
                explicitlySelected = false,
            ),
        )
        assertFalse(
            shouldRegisterAndroidOnDeviceRecognizer(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                serviceAvailable = true,
                cachedHindiCapability = CachedAndroidOnDeviceHindiCapability.UNKNOWN,
                explicitlySelected = true,
            ),
        )
        assertFalse(
            shouldRegisterAndroidOnDeviceRecognizer(
                sdkInt = Build.VERSION_CODES.TIRAMISU,
                serviceAvailable = true,
                cachedHindiCapability = CachedAndroidOnDeviceHindiCapability.MISSING,
                explicitlySelected = true,
            ),
        )
    }

    @Test
    fun api31And32RequireExplicitSelectionBecausePacksCannotBeVerified() {
        assertFalse(
            shouldRegisterAndroidOnDeviceRecognizer(
                sdkInt = Build.VERSION_CODES.S,
                serviceAvailable = true,
                cachedHindiCapability = CachedAndroidOnDeviceHindiCapability.UNKNOWN,
                explicitlySelected = false,
            ),
        )
        assertTrue(
            shouldRegisterAndroidOnDeviceRecognizer(
                sdkInt = Build.VERSION_CODES.S_V2,
                serviceAvailable = true,
                cachedHindiCapability = CachedAndroidOnDeviceHindiCapability.UNKNOWN,
                explicitlySelected = true,
            ),
        )
    }

    @Test
    fun oldAndroidOrMissingServiceNeverRegisters() {
        assertFalse(
            shouldRegisterAndroidOnDeviceRecognizer(
                sdkInt = Build.VERSION_CODES.R,
                serviceAvailable = true,
                cachedHindiCapability = CachedAndroidOnDeviceHindiCapability.INSTALLED,
                explicitlySelected = true,
            ),
        )
        assertFalse(
            shouldRegisterAndroidOnDeviceRecognizer(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                serviceAvailable = false,
                cachedHindiCapability = CachedAndroidOnDeviceHindiCapability.INSTALLED,
                explicitlySelected = true,
            ),
        )
    }
}
