// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsControllerCompat
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.settings.SettingsActivity

/**
 * Launcher activity. Shows the branded 4-step onboarding flow when the IME has
 * not been fully set up yet; once it's enabled and current, forwards straight
 * to SettingsActivity so returning users bypass the welcome flow.
 */
class OnboardingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (imeFullySetUp()) {
            forwardToSettings()
            return
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContent {
            OnboardingNav(onFinish = ::forwardToSettings)
        }
    }

    private fun imeFullySetUp(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return UncachedInputMethodManagerUtils.isThisImeEnabled(this, imm) &&
            UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm)
    }

    private fun forwardToSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
    }
}
