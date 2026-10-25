package me.ayuilos.miffan.data.onboarding

import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.ayuilos.miffan.data.model.isImMode

/** Where the first-run conversation starts, or [NONE] when the app opens normally. */
enum class OnboardingStage { NONE, CONNECT, PICK_PARTNER }

/**
 * Nothing to chat with starts at connecting a service. Easy chat that is connected but never picked
 * a first partner (the app was left halfway) resumes at that choice.
 */
fun Settings.onboardingStage(): OnboardingStage = when {
    isNotConfigured() -> OnboardingStage.CONNECT
    isImMode && !onboardingDone -> OnboardingStage.PICK_PARTNER
    else -> OnboardingStage.NONE
}

/** Called once when the flag is first created: installations already in use never see the partner choice. */
fun initialOnboardingDone(launchCount: Int, hasSavedProviders: Boolean): Boolean =
    launchCount > 0 || hasSavedProviders
