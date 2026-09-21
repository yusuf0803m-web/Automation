package com.pelita.autocontinue.core

/**
 * Turns a [UiSnapshot] into a [GenerationState] by combining several UI
 * signals. It never looks at elapsed time - time alone is not evidence.
 *
 * Rules, in order:
 *
 *  1. Target app not in the foreground -> UNKNOWN (nothing can be concluded).
 *  2. A "stop generating" button is present -> GENERATING.
 *  3. A spinner / generating indicator is present -> GENERATING.
 *  4. Either of those two signals is UNKNOWN -> UNKNOWN. We refuse to call a
 *     response finished while we cannot rule out that it is still running.
 *  5. Both are absent **and** the send button and the input field are present
 *     -> FINISHED.
 *  6. Anything else -> UNKNOWN.
 *
 * Rule 4 and rule 6 are what implement "better to wait too long than to send
 * too early".
 */
fun interface GenerationDetector {
    fun detect(snapshot: UiSnapshot): GenerationState
}

object GenerationStateDetector : GenerationDetector {

    override fun detect(snapshot: UiSnapshot): GenerationState {
        if (!snapshot.isTargetForeground) return GenerationState.UNKNOWN

        if (snapshot.stopGeneratingButton == Presence.FOUND) return GenerationState.GENERATING
        if (snapshot.generatingIndicator == Presence.FOUND) return GenerationState.GENERATING

        if (snapshot.stopGeneratingButton == Presence.UNKNOWN) return GenerationState.UNKNOWN
        if (snapshot.generatingIndicator == Presence.UNKNOWN) return GenerationState.UNKNOWN

        // The composer being present is the signal that the turn is over.
        //
        // The send button is deliberately NOT required: the ChatGPT app hides
        // it while the composer is empty and shows a microphone / voice button
        // instead, so requiring it made FINISHED unreachable between responses.
        // The two direct signals above (no stop button, no spinner), plus the
        // stability debounce, are what rule out an in-flight response.
        return if (snapshot.inputField == Presence.FOUND) {
            GenerationState.FINISHED
        } else {
            GenerationState.UNKNOWN
        }
    }
}
