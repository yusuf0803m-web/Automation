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
    /** True only when [foregroundPackage] matches the configured target package. */
    val isTargetForeground: Boolean = false,
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
    companion object {
        /**
         * The snapshot to report when the accessibility tree cannot be read at
         * all - everything UNKNOWN, so the engine waits.
         */
        fun unreadable(foregroundPackage: String? = null, capturedAtMs: Long = 0L): UiSnapshot =
            UiSnapshot(
                foregroundPackage = foregroundPackage,
                isTargetForeground = false,
                capturedAtMs = capturedAtMs,
            )
    }
}
