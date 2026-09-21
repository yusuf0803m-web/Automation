package com.pelita.autocontinue.core

/** Controllable clock so every timing rule can be tested without sleeping. */
class FakeClock(var now: Long = 1_000L) : Clock {
    override fun nowMs(): Long = now

    fun advance(ms: Long): FakeClock {
        now += ms
        return this
    }
}

/**
 * A hand-built accessibility "tree" expressed as presences. This is the fake
 * that lets the whole detection layer be exercised without ChatGPT installed.
 */
object FakeUi {

    const val CHATGPT = AutomationConfig.DEFAULT_TARGET_PACKAGE

    /** ChatGPT is streaming a response: stop button visible, send hidden. */
    fun generating(now: Long = 0L, signature: String? = "sig-0"): UiSnapshot = UiSnapshot(
        foregroundPackage = CHATGPT,
        targetForeground = Presence.FOUND,
        inputField = Presence.FOUND,
        sendButton = Presence.NOT_FOUND,
        stopGeneratingButton = Presence.FOUND,
        generatingIndicator = Presence.FOUND,
        inputHasText = Presence.NOT_FOUND,
        lastMessageSignature = signature,
        capturedAtMs = now,
    )

    /** ChatGPT is idle: composer and send available, nothing generating. */
    fun finished(now: Long = 0L, signature: String? = "sig-1"): UiSnapshot = UiSnapshot(
        foregroundPackage = CHATGPT,
        targetForeground = Presence.FOUND,
        inputField = Presence.FOUND,
        sendButton = Presence.FOUND,
        stopGeneratingButton = Presence.NOT_FOUND,
        generatingIndicator = Presence.NOT_FOUND,
        inputHasText = Presence.NOT_FOUND,
        lastMessageSignature = signature,
        capturedAtMs = now,
    )

    /**
     * ChatGPT is in front but the signals that would rule out an in-flight
     * response cannot be read, so nothing may be concluded.
     */
    fun ambiguous(now: Long = 0L): UiSnapshot = UiSnapshot(
        foregroundPackage = CHATGPT,
        targetForeground = Presence.FOUND,
        inputField = Presence.FOUND,
        sendButton = Presence.UNKNOWN,
        stopGeneratingButton = Presence.UNKNOWN,
        generatingIndicator = Presence.UNKNOWN,
        capturedAtMs = now,
    )

    /** Some other app is confirmed to own the screen. */
    fun otherApp(now: Long = 0L, pkg: String = "com.android.launcher"): UiSnapshot =
        UiSnapshot.otherAppInFront(foregroundPackage = pkg, capturedAtMs = now)

    /** The window could not be read at all - a transient, common condition. */
    fun unreadable(now: Long = 0L): UiSnapshot = UiSnapshot.unreadable(capturedAtMs = now)
}

/** Drives an engine through a full "ChatGPT finished a response" sequence. */
fun AutomationEngine.feedStableFinished(
    clock: FakeClock,
    stepMs: Long = 500L,
    steps: Int = 6,
    signature: String? = "sig-1",
): List<AutomationEffect> {
    val all = mutableListOf<AutomationEffect>()
    repeat(steps) {
        all += dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now, signature)))
        clock.advance(stepMs)
    }
    all += dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now, signature)))
    return all
}

fun List<AutomationEffect>.logs(): List<String> =
    filterIsInstance<AutomationEffect.Log>().map { it.message }

fun List<AutomationEffect>.hasFillInput(): Boolean =
    any { it is AutomationEffect.FillInput }

fun List<AutomationEffect>.hasClickSend(): Boolean =
    any { it is AutomationEffect.ClickSend }
