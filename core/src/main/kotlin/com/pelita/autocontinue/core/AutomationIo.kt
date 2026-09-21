package com.pelita.autocontinue.core

/** Everything that can drive the state machine. */
sealed interface AutomationInput {

    /** User pressed MULAI. */
    data object Start : AutomationInput

    /** User pressed PAUSE (from the app or the notification). */
    data object Pause : AutomationInput

    /** User pressed RESUME. */
    data object Resume : AutomationInput

    /** User pressed STOP (from the app or the notification). Emergency stop. */
    data object Stop : AutomationInput

    /** User cancelled the post-response countdown. */
    data object CancelCountdown : AutomationInput

    /** AccessibilityService attached. */
    data object AccessibilityConnected : AutomationInput

    /** AccessibilityService detached, interrupted or killed. */
    data object AccessibilityDisconnected : AutomationInput

    /** A fresh reading of the UI. */
    data class Snapshot(val snapshot: UiSnapshot) : AutomationInput

    /** Time passed. Drives countdowns and timeouts only. */
    data object Tick : AutomationInput

    /** Result of an [AutomationEffect.FillInput]. */
    data class InputFillResult(val success: Boolean) : AutomationInput

    /** Result of an [AutomationEffect.ClickSend]. */
    data class SendResult(val success: Boolean) : AutomationInput
}

/** Side effects the platform layer must perform on the engine's behalf. */
sealed interface AutomationEffect {

    /** Type [text] into the composer, then report [AutomationInput.InputFillResult]. */
    data class FillInput(val text: String) : AutomationEffect

    /** Click send, then report [AutomationInput.SendResult]. */
    data object ClickSend : AutomationEffect

    /** Append a line to the activity log / notification. */
    data class Log(val message: String) : AutomationEffect

    /** The state machine moved. */
    data class StateChanged(
        val from: AutomationState,
        val to: AutomationState,
    ) : AutomationEffect
}
