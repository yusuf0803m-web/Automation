package com.pelita.autocontinue.core

/**
 * Suppresses momentary UI flicker.
 *
 * ChatGPT's composer briefly looks "idle" between streamed chunks, so a single
 * FINISHED reading is not enough. A value is only reported once the *same*
 * value has held continuously for [requiredStableMs].
 *
 * ```
 * GENERATING -> FINISHED -> GENERATING   (within the window)
 * ```
 * never yields a stable FINISHED.
 */
class StabilityDebouncer(private val requiredStableMs: Long) {

    private var candidate: GenerationState? = null
    private var candidateSinceMs: Long = 0L

    /** The value currently being observed, regardless of whether it is stable. */
    val pendingCandidate: GenerationState?
        get() = candidate

    /**
     * Feeds an observation.
     *
     * @return [state] once it has been stable for long enough, otherwise null.
     */
    fun update(state: GenerationState, nowMs: Long): GenerationState? {
        if (state != candidate) {
            candidate = state
            candidateSinceMs = nowMs
        }
        return if (nowMs - candidateSinceMs >= requiredStableMs) state else null
    }

    /** How long the current candidate has been held. */
    fun heldForMs(nowMs: Long): Long =
        if (candidate == null) 0L else (nowMs - candidateSinceMs).coerceAtLeast(0L)

    fun reset() {
        candidate = null
        candidateSinceMs = 0L
    }
}
