package com.pelita.autocontinue.core

/**
 * An immutable, privacy-safe reading of the target app's accessibility tree.
 *
 * Deliberately contains **no conversation text**. [lastMessageSignature] is an
 * opaque digest supplied by the platform layer; it is only ever compared for
 * equality so a new response can be told apart from the previous one.
 */
data class UiSnapshot(
    /** Package of the app currently in the foreground, or null if unreadable. */
    val foregroundPackage: String? = null,
    /**
     * Whether the target app owns the foreground.
     *
     * FOUND     - the target package is confirmed in front.
     * NOT_FOUND - a different app is confirmed in front.
     * UNKNOWN   - it could not be determined: the window was unreadable, or
     *             only a system overlay (status bar, keyboard) could be seen.
     *
     * The UNKNOWN case must not be treated as "the user left ChatGPT": a
     * transient null window would otherwise cancel a running countdown.
     */
    val targetForeground: Presence = Presence.UNKNOWN,
    /** The message composer / prompt field. */
    val inputField: Presence = Presence.UNKNOWN,
    /** The send / submit button. */
    val sendButton: Presence = Presence.UNKNOWN,
    /** The "stop generating" / "stop streaming" button. */
    val stopGeneratingButton: Presence = Presence.UNKNOWN,
    /** Any spinner, progress bar or "generating"/"thinking" indicator. */
    val generatingIndicator: Presence = Presence.UNKNOWN,
    /** Whether the composer currently holds text. Used to confirm a send. */
    val inputHasText: Presence = Presence.UNKNOWN,
    /** Opaque digest of the last assistant message. Never the message itself. */
    val lastMessageSignature: String? = null,
    /** Wall-clock time the snapshot was taken. */
    val capturedAtMs: Long = 0L,
) {
    /** Convenience for the common "may I act?" check. */
    val isTargetForeground: Boolean
        get() = targetForeground == Presence.FOUND

    companion object {
        /**
         * Nothing could be read - including which app is in front. Every field
         * stays UNKNOWN, so the engine holds its position and waits.
         */
        fun unreadable(foregroundPackage: String? = null, capturedAtMs: Long = 0L): UiSnapshot =
            UiSnapshot(
                foregroundPackage = foregroundPackage,
                targetForeground = Presence.UNKNOWN,
                capturedAtMs = capturedAtMs,
            )

        /** A different app is confirmed to own the screen. */
        fun otherAppInFront(foregroundPackage: String?, capturedAtMs: Long = 0L): UiSnapshot =
            UiSnapshot(
                foregroundPackage = foregroundPackage,
                targetForeground = Presence.NOT_FOUND,
                capturedAtMs = capturedAtMs,
            )
    }
}
