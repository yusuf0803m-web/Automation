package com.pelita.autocontinue.core

/**
 * The single seam between the automation core and the real ChatGPT UI.
 *
 * Every piece of node-finding knowledge lives behind this interface (on
 * Android: `ChatGptUiDetector`), so when ChatGPT's UI changes there is exactly
 * one place to fix. The core never sees an `AccessibilityNodeInfo`.
 */
interface ChatGptUiPort {

    /** True when the configured target package owns the active window. */
    fun isChatGptForeground(): Boolean

    /** Reads the current UI. Must return all-UNKNOWN rather than guessing. */
    fun captureSnapshot(): UiSnapshot

    /**
     * Types [text] into the composer.
     * @return false if the composer could not be found or did not accept text.
     */
    fun fillInput(text: String): Boolean

    /**
     * Activates the send control, preferring a real node click.
     * @return false if no send control could be found or the click failed.
     */
    fun sendMessage(): Boolean
}
