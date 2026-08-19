// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.common.Constants.ImeOption.NO_MICROPHONE
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InputAttributesVoicePolicyTest {
    @Test
    fun ordinaryTextAllowsBuiltInVoiceWithoutConsultingExternalVoiceIme() {
        val editor = editor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)

        assertTrue(InputAttributes.shouldAllowSpeakKeysVoiceInput(editor, IME_PACKAGE))
        assertTrue(InputAttributes(editor, false, IME_PACKAGE).mShouldAllowSpeakKeysVoiceInput)
    }

    @Test
    fun passwordAndVisiblePasswordFieldsRejectBuiltInVoice() {
        val password = editor(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val visiblePassword = editor(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )

        assertFalse(InputAttributes.shouldAllowSpeakKeysVoiceInput(password, IME_PACKAGE))
        assertFalse(InputAttributes.shouldAllowSpeakKeysVoiceInput(visiblePassword, IME_PACKAGE))
    }

    @Test
    fun emailAndNonTextFieldsRejectBuiltInVoice() {
        val email = editor(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        )
        val phone = editor(InputType.TYPE_CLASS_PHONE)

        assertFalse(InputAttributes.shouldAllowSpeakKeysVoiceInput(email, IME_PACKAGE))
        assertFalse(InputAttributes.shouldAllowSpeakKeysVoiceInput(phone, IME_PACKAGE))
        assertFalse(InputAttributes.shouldAllowSpeakKeysVoiceInput(null, IME_PACKAGE))
    }

    @Test
    fun appNoMicrophoneOptionRejectsBuiltInVoice() {
        val editor = editor(InputType.TYPE_CLASS_TEXT).apply {
            privateImeOptions = "$IME_PACKAGE.$NO_MICROPHONE"
        }

        assertFalse(InputAttributes.shouldAllowSpeakKeysVoiceInput(editor, IME_PACKAGE))
    }

    @Test
    fun changingOnlyNoMicrophoneOptionInvalidatesCachedInputAttributes() {
        val initial = editor(InputType.TYPE_CLASS_TEXT)
        val attributes = InputAttributes(initial, false, IME_PACKAGE)
        val protected = editor(InputType.TYPE_CLASS_TEXT).apply {
            privateImeOptions = "$IME_PACKAGE.$NO_MICROPHONE"
        }

        assertFalse(attributes.isSameInputType(protected))
    }

    private fun editor(inputType: Int) = EditorInfo().apply {
        this.inputType = inputType
        packageName = TARGET_PACKAGE
    }

    private companion object {
        const val IME_PACKAGE = "com.speakkeys.keyboard"
        const val TARGET_PACKAGE = "example.editor"
    }
}
