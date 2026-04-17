package com.elishaazaria.sayboard.recognition.text

/**
 * Pure text processing logic extracted from TextManager.
 * Contains auto-spacing and auto-capitalization rules
 * with no platform dependencies.
 */
object TextProcessor {
    private val sentenceTerminators = charArrayOf('.', '\n', '!', '?')

    fun capitalizeAfter(text: CharSequence): Boolean? {
        for (char in text.reversed()) {
            if (char.isLetterOrDigit()) {
                return false
            }
            if (char in sentenceTerminators) {
                return true
            }
        }
        return null
    }

    fun addSpaceAfter(char: Char): Boolean = when (char) {
        '"' -> false
        '*' -> false
        ' ' -> false
        '\n' -> false
        '\t' -> false
        else -> true
    }

    fun processText(
        text: String,
        shouldCapitalize: Boolean,
        shouldAddSpace: Boolean,
        autoCapitalizeEnabled: Boolean,
        recognizerAddsSpaces: Boolean
    ): String {
        var result = text
        if (autoCapitalizeEnabled && shouldCapitalize) {
            result = result[0].uppercase() + result.substring(1)
        }
        if (recognizerAddsSpaces && shouldAddSpace) {
            result = " $result"
        }
        return result
    }
}
