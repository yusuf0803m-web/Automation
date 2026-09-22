package com.pelita.autocontinue.core

/** How a send is triggered. */
enum class AutomationMode {
    /**
     * MODE B. Waits until the current response is observably finished, then
     * sends after the post-response delay. The default, and the safe one.
     */
    WAIT_FOR_RESPONSE,

    /**
     * MODE A. Sends on a fixed interval instead of waiting for a response.
     *
     * A fallback for when ChatGPT's UI changes enough that the end of a
     * response can no longer be detected. It is less safe by design: the timer,
     * not the UI, decides when to send. The only guard kept is that a visible
     * "stop generating" button postpones the round, so an in-flight response is
     * not interrupted.
     */
    TIMED,
}
