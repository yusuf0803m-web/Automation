package com.pelita.autocontinue.core

/**
 * Explicit states of the MODE B ("wait for response") workflow.
 *
 * Normal flow:
 *
 * ```
 * IDLE
 *  -> WAITING_FOR_CHATGPT
 *  -> CHATGPT_GENERATING
 *  -> CHATGPT_FINISHED
 *  -> POST_RESPONSE_DELAY
 *  -> FILLING_INPUT
 *  -> SENDING
 *  -> WAITING_FOR_NEXT_RESPONSE
 *  -> CHATGPT_GENERATING
 *  -> ...
 * ```
 *
 * [PAUSED] and [STOPPED] are reachable from any state; [ERROR] is entered on a
 * failure and recovers back to [WAITING_FOR_CHATGPT] after a cooldown.
 */
enum class AutomationState {
    IDLE,
    WAITING_FOR_CHATGPT,
    CHATGPT_GENERATING,
    CHATGPT_FINISHED,
    POST_RESPONSE_DELAY,
    FILLING_INPUT,
    SENDING,
    WAITING_FOR_NEXT_RESPONSE,
    PAUSED,
    STOPPED,
    ERROR,
    ;

    /** States in which the engine must never type, tap or send anything. */
    val isInert: Boolean
        get() = this == IDLE || this == PAUSED || this == STOPPED
}
