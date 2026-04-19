// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

enum class OnboardingStep { Welcome, Permission, Enable, Switch }

/**
 * Drives the 4-screen onboarding flow with slide+fade transitions. When the user
 * reaches the end (all screens completed or skipped), [onFinish] is invoked so
 * the caller can forward to the legacy wizard or settings.
 */
@Composable
fun OnboardingNav(onFinish: () -> Unit) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }

    BackHandler(enabled = step != OnboardingStep.Welcome) {
        step = when (step) {
            OnboardingStep.Permission -> OnboardingStep.Welcome
            OnboardingStep.Enable -> OnboardingStep.Permission
            OnboardingStep.Switch -> OnboardingStep.Enable
            OnboardingStep.Welcome -> OnboardingStep.Welcome
        }
    }

    AnimatedContent(
        targetState = step,
        label = "onboarding-nav",
        transitionSpec = {
            val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
            val enter = slideInHorizontally(
                animationSpec = tween(durationMillis = 300),
                initialOffsetX = { full -> direction * full / 4 },
            ) + fadeIn(animationSpec = tween(durationMillis = 300))
            val exit = slideOutHorizontally(
                animationSpec = tween(durationMillis = 240),
                targetOffsetX = { full -> -direction * full / 4 },
            ) + fadeOut(animationSpec = tween(durationMillis = 240))
            enter togetherWith exit
        },
    ) { current ->
        when (current) {
            OnboardingStep.Welcome -> WelcomeScreen(
                onGetStarted = { step = OnboardingStep.Permission },
            )
            OnboardingStep.Permission -> PermissionScreen(
                onAllow = { step = OnboardingStep.Enable },
                onSkip = { step = OnboardingStep.Enable },
            )
            OnboardingStep.Enable -> EnableInSettingsScreen(
                onContinue = { step = OnboardingStep.Switch },
                onSkip = { step = OnboardingStep.Switch },
            )
            OnboardingStep.Switch -> SwitchInputMethodScreen(
                onContinue = onFinish,
                onSkip = onFinish,
            )
        }
    }
}
