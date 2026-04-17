package com.elishaazaria.sayboard.utils

/**
 * Utility for converting Devanagari (Hindi) script to Roman script (Hinglish).
 * Uses character-level mapping with proper handling of:
 * - Consonants with inherent 'a' vowel
 * - Vowel marks (matras) that replace the inherent vowel
 * - Halant (virama) that suppresses the inherent vowel
 * - Schwa deletion at word-final positions
 */
object DevanagariTransliterator {

    private val charMap = mapOf(
        // Vowels (independent)
        '\u0905' to "a", '\u0906' to "aa", '\u0907' to "i", '\u0908' to "ee",
        '\u0909' to "u", '\u090A' to "oo", '\u090F' to "e", '\u0910' to "ai",
        '\u0913' to "o", '\u0914' to "au", '\u090B' to "ri",

        // Vowel marks (matras) - these REPLACE the inherent 'a'
        '\u093E' to "aa", '\u093F' to "i", '\u0940' to "ee", '\u0941' to "u",
        '\u0942' to "oo", '\u0947' to "e", '\u0948' to "ai", '\u094B' to "o",
        '\u094C' to "au", '\u0943' to "ri",

        // Consonants (base form without inherent vowel)
        '\u0915' to "k", '\u0916' to "kh", '\u0917' to "g", '\u0918' to "gh", '\u0919' to "ng",
        '\u091A' to "ch", '\u091B' to "chh", '\u091C' to "j", '\u091D' to "jh", '\u091E' to "n",
        '\u091F' to "t", '\u0920' to "th", '\u0921' to "d", '\u0922' to "dh", '\u0923' to "n",
        '\u0924' to "t", '\u0925' to "th", '\u0926' to "d", '\u0927' to "dh", '\u0928' to "n",
        '\u092A' to "p", '\u092B' to "ph", '\u092C' to "b", '\u092D' to "bh", '\u092E' to "m",
        '\u092F' to "y", '\u0930' to "r", '\u0932' to "l", '\u0935' to "v",
        '\u0936' to "sh", '\u0937' to "sh", '\u0938' to "s", '\u0939' to "h",

        // Special marks
        '\u0902' to "n",   // Anusvara
        '\u0903' to "h",   // Visarga
        '\u094D' to "",    // Halant (virama) - suppresses inherent vowel
        '\u0901' to "n",   // Chandrabindu
        '\u093C' to "",    // Nukta (combining mark) - ignore, base consonant handles it
    )

    private val vowelMarks = setOf(
        '\u093E', '\u093F', '\u0940', '\u0941', '\u0942',
        '\u0947', '\u0948', '\u094B', '\u094C', '\u0943'
    )
    private val consonants = setOf(
        '\u0915', '\u0916', '\u0917', '\u0918', '\u0919',
        '\u091A', '\u091B', '\u091C', '\u091D', '\u091E',
        '\u091F', '\u0920', '\u0921', '\u0922', '\u0923',
        '\u0924', '\u0925', '\u0926', '\u0927', '\u0928',
        '\u092A', '\u092B', '\u092C', '\u092D', '\u092E',
        '\u092F', '\u0930', '\u0932', '\u0935',
        '\u0936', '\u0937', '\u0938', '\u0939'
    )
    private const val HALANT = '\u094D'

    private fun isDevanagari(char: Char): Boolean {
        return char in '\u0900'..'\u097F'
    }

    private fun isConsonant(char: Char): Boolean {
        return char in consonants
    }

    fun transliterate(text: String): String {
        val result = StringBuilder()
        var i = 0

        while (i < text.length) {
            val char = text[i]
            val nextChar = text.getOrNull(i + 1)

            if (char in charMap) {
                result.append(charMap[char])

                // Add inherent 'a' for consonants not followed by halant or vowel mark
                if (isConsonant(char)) {
                    val hasHalant = nextChar == HALANT
                    val hasVowelMark = nextChar in vowelMarks

                    if (!hasHalant && !hasVowelMark) {
                        // Check for word-final position (apply schwa deletion)
                        val isWordFinal = nextChar == null ||
                                nextChar == ' ' ||
                                !isDevanagari(nextChar)
                        if (!isWordFinal) {
                            result.append("a")
                        }
                    }
                }
            } else {
                // Non-mapped character (English, numbers, punctuation) - keep as-is
                result.append(char)
            }

            i++
        }

        return result.toString()
    }
}
