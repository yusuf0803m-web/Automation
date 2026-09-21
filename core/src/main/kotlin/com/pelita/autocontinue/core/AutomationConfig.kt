package com.pelita.autocontinue.core

/**
 * Tunables for the MODE B workflow. Every timer here is a *guard* - a delay, a
 * timeout or a debounce window. None of them is a trigger: the only thing that
 * starts a send is an observed, stable end of generation.
 */
data class AutomationConfig(
    /** Package of the app to automate. Configurable because it can change. */
    val targetPackage: String = DEFAULT_TARGET_PACKAGE,
    /** Text typed into the composer. */
    val message: String = DEFAULT_MESSAGE,
    /** Grace period after a response completes, before typing. 1..60 s. */
    val postResponseDelayMs: Long = DEFAULT_POST_RESPONSE_DELAY_MS,
    /** How long FINISHED must hold before it is believed. */
    val finishedDebounceMs: Long = 1_500L,
    /** A snapshot older than this is treated as UNKNOWN. */
    val snapshotStaleMs: Long = 3_000L,
    /** Give up looking for the composer after this long. */
    val inputTimeoutMs: Long = 10_000L,
    /** Give up looking for / clicking send after this long. */
    val sendTimeoutMs: Long = 10_000L,
    /** Stop waiting for the next generation after this long and re-arm safely. */
    val waitForNextResponseTimeoutMs: Long = 120_000L,
    /** Log a hint if the target app has not appeared after this long. */
    val chatGptSearchTimeoutMs: Long = 30_000L,
    /** Attempts per action (fill, send) before entering ERROR. */
    val maxRetries: Int = 3,
    /**
     * How long another app must hold the foreground before the workflow is
     * re-armed. Without this, a status bar or keyboard window appearing for a
     * single frame would cancel a running countdown.
     */
    val foregroundLostDebounceMs: Long = 1_500L,
    /**
     * Wait between attempts at the same action. Retrying instantly is useless:
     * the composer and the send button need a moment to appear.
     */
    val retryDelayMs: Long = 600L,
    /** How long ERROR is held before retrying from WAITING_FOR_CHATGPT. */
    val errorCooldownMs: Long = 5_000L,
    /**
     * Allow a changed [UiSnapshot.lastMessageSignature] to count as the start
     * of a new response cycle. This recovers the loop when a response was so
     * short that GENERATING was never observed.
     */
    val allowSignatureCycleAdvance: Boolean = true,
) {
    init {
        require(message.isNotBlank()) { "message must not be blank" }
        require(targetPackage.isNotBlank()) { "targetPackage must not be blank" }
        require(postResponseDelayMs in MIN_DELAY_MS..MAX_DELAY_MS) {
            "postResponseDelayMs must be between $MIN_DELAY_MS and $MAX_DELAY_MS"
        }
        require(maxRetries >= 1) { "maxRetries must be at least 1" }
    }

    val postResponseDelaySeconds: Int
        get() = (postResponseDelayMs / 1000L).toInt()

    fun withDelaySeconds(seconds: Int): AutomationConfig =
        copy(postResponseDelayMs = seconds.coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS) * 1000L)

    companion object {
        /**
         * Default target package for the official ChatGPT Android app.
         *
         * Treat this as a *default*, not a certainty: it is user-configurable,
         * and the Accessibility Debug screen shows the real foreground package
         * so it can be corrected without rebuilding.
         */
        const val DEFAULT_TARGET_PACKAGE = "com.openai.chatgpt"
        const val DEFAULT_MESSAGE = "Lanjut"

        const val MIN_DELAY_SECONDS = 1
        const val MAX_DELAY_SECONDS = 60
        const val MIN_DELAY_MS = MIN_DELAY_SECONDS * 1000L
        const val MAX_DELAY_MS = MAX_DELAY_SECONDS * 1000L
        const val DEFAULT_POST_RESPONSE_DELAY_MS = 10_000L
    }
}
