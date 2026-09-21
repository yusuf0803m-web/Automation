package com.pelita.autocontinue.core

/**
 * Result of [GenerationStateDetector].
 *
 * [UNKNOWN] is not a failure - it means the UI could not be read confidently.
 * The automation must **wait** on UNKNOWN and never send a message.
 */
enum class GenerationState {
    GENERATING,
    FINISHED,
    UNKNOWN,
}

/**
 * Tri-state presence of a UI element.
 *
 * The distinction between [NOT_FOUND] ("we looked and it is definitely absent")
 * and [UNKNOWN] ("we could not look - no window root, transitional window,
 * service not attached") is what keeps the automation from sending too early.
 */
enum class Presence {
    FOUND,
    NOT_FOUND,
    UNKNOWN,
}
