// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

object SpeakKeysColors {
    val Bg = Color(0xFF0B1220)
    val BgElev = Color(0xFF121A2B)
    val BgElev2 = Color(0xFF1A2338)
    val Border = Color(0xFF283656)
    val Hairline = Color(0xFF1F2A44)
    val Fg = Color(0xFFF1F5FB)
    val FgDim = Color(0xFFA8B3CF)
    val FgMute = Color(0xFF6B7896)
    val Brand = Color(0xFF64B5F6)
    val BrandGlow = Color(0xFF90CAF9)
    val BrandDeep = Color(0xFF1565C0)
    val BrandSoft = Color(0xFF1E3A5F)
    val Active = Color(0xFFFF8A65)
    val Success = Color(0xFF5EE3A1)
    val CtaTextOnBrand = Color(0xFF0A1528)
    val StepRailTrack = Color(0xFF1E2945)
    val ToggleTrackOn = Color(0xFF3C4763)
    val ToggleTrackOff = Color(0xFF2A3550)
}

// TODO(onboarding): swap to Inter once the font is wired up (Google Fonts provider or shipped TTF).
// Letter-spacing values are kept exact per the spec so the hero does not reflow when Inter lands.
val SpeakKeysFont: FontFamily = FontFamily.Default

// TODO(onboarding): swap to Caveat. Placeholder uses the platform cursive family; the
// handwritten asides ("flip this on", "pick me!") will visually differ until the real
// font is bundled or loaded via the Google Fonts provider.
val SpeakKeysHandwritten: FontFamily = FontFamily.Cursive

object SpeakKeysType {
    val Hero = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W700,
        fontSize = 36.sp,
        lineHeight = 37.8.sp,
        letterSpacing = (-1.2).sp,
    )
    val HeroSmall = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W700,
        fontSize = 30.sp,
        lineHeight = 33.sp,
        letterSpacing = (-0.8).sp,
    )
    val Body = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W400,
        fontSize = 15.sp,
        lineHeight = 22.5.sp,
        color = SpeakKeysColors.FgDim,
    )
    val Pill = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    )
    val Kicker = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp,
    )
    val Button = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 16.sp,
        letterSpacing = (-0.2).sp,
    )
    val SubLabel = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W500,
        fontSize = 12.sp,
    )
    val Wordmark = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W700,
        fontSize = 15.sp,
        letterSpacing = (-0.2).sp,
    )
    val PreviewLabel = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 10.sp,
        letterSpacing = 0.6.sp,
    )
    val StripStatus = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
    )
    val StripText = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W500,
        fontSize = 12.sp,
    )
    val Chip = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
    )
    val Key = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W500,
        fontSize = 11.sp,
        color = SpeakKeysColors.FgDim,
    )
    val StepRailLabel = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        color = SpeakKeysColors.FgMute,
    )
    val GhostLinkLabel = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W500,
        fontSize = 13.sp,
        color = SpeakKeysColors.FgDim,
        textAlign = TextAlign.Center,
    )
    val Handwritten = TextStyle(
        fontFamily = SpeakKeysHandwritten,
        fontWeight = FontWeight.W400,
        fontSize = 18.sp,
        lineHeight = 18.sp,
        color = SpeakKeysColors.BrandGlow,
    )
    val TrustText = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 16.8.sp,
        color = SpeakKeysColors.FgDim,
    )
    val TrustTextEmphasis = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 12.sp,
        lineHeight = 16.8.sp,
        color = SpeakKeysColors.Fg,
    )
    val SettingsHeader = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 12.sp,
        color = SpeakKeysColors.FgDim,
    )
    val SettingsRowName = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 14.sp,
        color = SpeakKeysColors.Fg,
    )
    val SettingsRowDesc = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W400,
        fontSize = 11.sp,
        color = SpeakKeysColors.FgMute,
    )
    val InputMethodSheetHeader = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
        letterSpacing = 0.5.sp,
        color = SpeakKeysColors.FgMute,
    )
    val InputMethodRowName = TextStyle(
        fontFamily = SpeakKeysFont,
        fontWeight = FontWeight.W500,
        fontSize = 15.sp,
    )
}
