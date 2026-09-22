package com.pelita.autocontinue.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Section 21 of the specification: the state machine.
 */
class AutomationEngineTest {

    private fun engine(
        clock: FakeClock = FakeClock(),
        config: AutomationConfig = AutomationConfig(),
        connected: Boolean = true,
    ): Pair<AutomationEngine, FakeClock> {
        val e = AutomationEngine(config = config, clock = clock)
        if (connected) e.dispatch(AutomationInput.AccessibilityConnected)
        return e to clock
    }

    /** Runs the engine from a cold start up to the point a send is emitted. */
    private fun runToSend(
        e: AutomationEngine,
        clock: FakeClock,
        config: AutomationConfig = AutomationConfig(),
    ): MutableList<AutomationEffect> {
        val effects = mutableListOf<AutomationEffect>()
        effects += e.dispatch(AutomationInput.Start)
        effects += e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        effects += e.feedStableFinished(clock)
        // Burn down the post-response countdown.
        repeat((config.postResponseDelayMs / 500L).toInt() + 2) {
            clock.advance(500L)
            effects += e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
            effects += e.dispatch(AutomationInput.Tick)
        }
        return effects
    }

    // 1 ---------------------------------------------------------------------

    @Test
    fun `1 - IDLE moves to WAITING_FOR_CHATGPT on start`() {
        val (e, _) = engine()
        assertEquals(AutomationState.IDLE, e.state)
        e.dispatch(AutomationInput.Start)
        assertEquals(AutomationState.WAITING_FOR_CHATGPT, e.state)
        assertTrue(e.isRunning)
    }

    // 2 ---------------------------------------------------------------------

    @Test
    fun `2 - a generating snapshot moves to CHATGPT_GENERATING`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        assertEquals(AutomationState.CHATGPT_GENERATING, e.state)
        assertEquals(GenerationState.GENERATING, e.lastGenerationState)
    }

    // 3 ---------------------------------------------------------------------

    @Test
    fun `3 - a stable finished snapshot moves through CHATGPT_FINISHED into the delay`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)
    }

    @Test
    fun `3b - a single finished frame is not enough`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
        assertEquals(AutomationState.CHATGPT_GENERATING, e.state)
    }

    @Test
    fun `3c - flicker back to generating aborts the pending finish`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
        clock.advance(1_000L)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(1_000L)
        e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
        assertEquals(AutomationState.CHATGPT_GENERATING, e.state)
    }

    // 4 & 5 -----------------------------------------------------------------

    @Test
    fun `4 - an ambiguous snapshot is reported as UNKNOWN`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.ambiguous(clock.now)))
        assertEquals(GenerationState.UNKNOWN, e.lastGenerationState)
    }

    @Test
    fun `5 - UNKNOWN never triggers a send however long it lasts`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        val effects = mutableListOf<AutomationEffect>()
        repeat(200) {
            clock.advance(500L)
            effects += e.dispatch(AutomationInput.Snapshot(FakeUi.ambiguous(clock.now)))
            effects += e.dispatch(AutomationInput.Tick)
        }
        assertFalse(effects.hasFillInput(), "UNKNOWN must never produce a fill")
        assertFalse(effects.hasClickSend(), "UNKNOWN must never produce a send")
        assertEquals(AutomationState.CHATGPT_GENERATING, e.state)
    }

    @Test
    fun `5b - an UNKNOWN reading freezes an already running countdown`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)

        val effects = mutableListOf<AutomationEffect>()
        repeat(60) {
            clock.advance(500L)
            effects += e.dispatch(AutomationInput.Snapshot(FakeUi.ambiguous(clock.now)))
            effects += e.dispatch(AutomationInput.Tick)
        }
        assertFalse(effects.hasFillInput())
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)
    }

    // 6 ---------------------------------------------------------------------

    @Test
    fun `6 - the post-response delay is honoured before typing`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)

        // Feed a stable finish and note exactly when the countdown started.
        var countdownStartedAt = -1L
        repeat(5) {
            val out = e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
            val entered = out.filterIsInstance<AutomationEffect.StateChanged>()
                .any { it.to == AutomationState.POST_RESPONSE_DELAY }
            if (entered) countdownStartedAt = clock.now
            clock.advance(500L)
        }
        assertTrue(countdownStartedAt >= 0L, "the countdown must have started")
        assertEquals(10, e.countdownSecondsRemaining)

        var typedAt = -1L
        repeat(60) {
            clock.advance(500L)
            e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
            val out = e.dispatch(AutomationInput.Tick)
            if (typedAt < 0L && out.hasFillInput()) typedAt = clock.now
        }
        assertTrue(typedAt >= 0L, "must eventually type")

        val waited = typedAt - countdownStartedAt
        assertTrue(
            waited >= config.postResponseDelayMs,
            "typed after ${waited}ms, expected at least ${config.postResponseDelayMs}ms",
        )
        assertTrue(
            waited < config.postResponseDelayMs + 1_000L,
            "typed after ${waited}ms, which overshoots the configured delay",
        )
    }

    @Test
    fun `6b - the countdown is configurable and can be cancelled`() {
        val config = AutomationConfig().withDelaySeconds(3)
        val (e, clock) = engine(config = config)
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)
        assertEquals(3, e.countdownSecondsRemaining)

        e.dispatch(AutomationInput.CancelCountdown)
        assertEquals(AutomationState.WAITING_FOR_CHATGPT, e.state)
    }

    @Test
    fun `6c - the delay is clamped to the 1 to 60 second range`() {
        assertEquals(1, AutomationConfig().withDelaySeconds(0).postResponseDelaySeconds)
        assertEquals(60, AutomationConfig().withDelaySeconds(999).postResponseDelaySeconds)
    }

    // 7 & 8 -----------------------------------------------------------------

    @Test
    fun `7 - pause stops all interaction`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)
        e.dispatch(AutomationInput.Pause)
        assertEquals(AutomationState.PAUSED, e.state)
        assertEquals(0, e.countdownSecondsRemaining)

        val effects = mutableListOf<AutomationEffect>()
        repeat(100) {
            clock.advance(500L)
            effects += e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
            effects += e.dispatch(AutomationInput.Tick)
        }
        assertFalse(effects.hasFillInput(), "a paused engine must not type")
        assertFalse(effects.hasClickSend(), "a paused engine must not send")
        assertEquals(AutomationState.PAUSED, e.state)
    }

    @Test
    fun `8 - resume returns to a safe waiting state and keeps working`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        e.dispatch(AutomationInput.Pause)
        e.dispatch(AutomationInput.Resume)
        assertEquals(AutomationState.WAITING_FOR_CHATGPT, e.state)

        clock.advance(2_000L)
        e.feedStableFinished(clock)
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)
    }

    // 9 ---------------------------------------------------------------------

    @Test
    fun `9 - stop wins from any state and cancels a pending countdown`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)

        e.dispatch(AutomationInput.Stop)
        assertEquals(AutomationState.STOPPED, e.state)
        assertFalse(e.isRunning)

        val effects = mutableListOf<AutomationEffect>()
        repeat(100) {
            clock.advance(500L)
            effects += e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
            effects += e.dispatch(AutomationInput.Tick)
        }
        assertFalse(effects.hasFillInput())
        assertFalse(effects.hasClickSend())
    }

    // 10 --------------------------------------------------------------------

    @Test
    fun `10 - CASE D - a response that was already handled is never sent to twice`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        val effects = runToSend(e, clock, config)
        assertTrue(effects.hasFillInput())

        e.completeSend(clock)
        assertEquals(AutomationState.WAITING_FOR_NEXT_RESPONSE, e.state)
        assertTrue(e.messageSentForCurrentResponse)

        // ChatGPT stays idle with the very same last message for a long time.
        val after = mutableListOf<AutomationEffect>()
        repeat(200) {
            clock.advance(500L)
            after += e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now, "sig-1")))
            after += e.dispatch(AutomationInput.Tick)
        }
        assertFalse(after.hasFillInput(), "must not type \"Lanjut\" twice for one response")
        assertFalse(after.hasClickSend(), "must not send twice for one response")
    }

    @Test
    fun `10b - the duplicate flag clears only when a new generation starts`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        e.completeSend(clock)
        val firstCycle = e.responseCycleId
        assertTrue(e.messageSentForCurrentResponse)

        clock.advance(1_000L)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        assertEquals(AutomationState.CHATGPT_GENERATING, e.state)
        assertNotEquals(firstCycle, e.responseCycleId)
        assertFalse(e.messageSentForCurrentResponse, "a new generation re-arms the engine")
    }

    @Test
    fun `10c - a full second cycle sends exactly once more`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        e.completeSend(clock)

        clock.advance(1_000L)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock, signature = "sig-2")

        val second = mutableListOf<AutomationEffect>()
        repeat(26) {
            clock.advance(500L)
            second += e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now, "sig-2")))
            second += e.dispatch(AutomationInput.Tick)
        }
        assertEquals(
            1,
            second.count { it is AutomationEffect.FillInput },
            "exactly one send per response cycle",
        )
    }

    // 11 --------------------------------------------------------------------

    @Test
    fun `11 - a missing input field times out into ERROR instead of retrying forever`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        assertEquals(AutomationState.FILLING_INPUT, e.state)

        clock.advance(config.inputTimeoutMs + 100L)
        e.dispatch(AutomationInput.Tick)
        assertEquals(AutomationState.ERROR, e.state)
    }

    @Test
    fun `11b - repeated fill failures end in ERROR, not in spam`() {
        val config = AutomationConfig(maxRetries = 3)
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)

        var fills = 0
        repeat(5) {
            val failed = e.dispatch(AutomationInput.InputFillResult(success = false))
            fills += failed.count { it is AutomationEffect.FillInput }
            clock.advance(config.retryDelayMs + 50L)
            fills += e.dispatch(AutomationInput.Tick).count { it is AutomationEffect.FillInput }
        }
        assertEquals(AutomationState.ERROR, e.state)
        assertTrue(fills <= config.maxRetries, "retries must be bounded, got $fills")
    }

    @Test
    fun `11e - a failed attempt is not retried instantly`() {
        // Retrying in the same millisecond burns every attempt before the UI
        // has had any chance to change.
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)

        val immediate = e.dispatch(AutomationInput.InputFillResult(success = false))
        assertFalse(immediate.hasFillInput(), "must not retry in the same instant")

        val tooSoon = e.dispatch(AutomationInput.Tick)
        assertFalse(tooSoon.hasFillInput(), "must not retry before the retry delay")

        clock.advance(config.retryDelayMs + 50L)
        val later = e.dispatch(AutomationInput.Tick)
        assertTrue(later.hasFillInput(), "must retry once the delay has passed")
    }

    @Test
    fun `11c - the engine recovers from ERROR after the cooldown`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        clock.advance(config.inputTimeoutMs + 100L)
        e.dispatch(AutomationInput.Tick)
        assertEquals(AutomationState.ERROR, e.state)

        clock.advance(config.errorCooldownMs + 100L)
        e.dispatch(AutomationInput.Tick)
        assertEquals(AutomationState.WAITING_FOR_CHATGPT, e.state)
    }

    @Test
    fun `11d - waiting for the next response eventually re-arms without sending again`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        e.completeSend(clock)

        clock.advance(config.waitForNextResponseTimeoutMs + 1_000L)
        val out = e.dispatch(AutomationInput.Tick)
        assertEquals(AutomationState.WAITING_FOR_CHATGPT, e.state)
        assertFalse(out.hasFillInput())
        assertTrue(e.messageSentForCurrentResponse, "the cycle is still marked as handled")
    }

    // 12 & 13 ---------------------------------------------------------------

    @Test
    fun `12 - the target package is what identifies ChatGPT`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        assertEquals(AutomationState.CHATGPT_GENERATING, e.state)
        assertEquals(FakeUi.CHATGPT, e.lastSnapshot?.foregroundPackage)
    }

    @Test
    fun `13 - CASE E - ChatGPT leaving the foreground returns to WAITING_FOR_CHATGPT`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)

        // The user really did switch away: another app holds the foreground
        // for longer than the debounce window.
        repeat(8) {
            clock.advance(500L)
            e.dispatch(AutomationInput.Snapshot(FakeUi.otherApp(clock.now)))
            e.dispatch(AutomationInput.Tick)
        }
        assertEquals(AutomationState.WAITING_FOR_CHATGPT, e.state)
        assertEquals(0, e.countdownSecondsRemaining)
    }

    @Test
    fun `13c - one stray system-UI frame does not cancel a running countdown`() {
        // The status bar, notification shade or keyboard taking focus for a
        // frame is not the user leaving ChatGPT.
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)

        clock.advance(500L)
        e.dispatch(AutomationInput.Snapshot(FakeUi.otherApp(clock.now, "com.android.systemui")))
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)

        // ChatGPT is back on the very next reading, so the workflow continues.
        clock.advance(500L)
        e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)
    }

    @Test
    fun `13d - an unreadable window never counts as leaving ChatGPT`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)

        // A null window root, however long it lasts, only ever means "wait".
        repeat(40) {
            clock.advance(500L)
            e.dispatch(AutomationInput.Snapshot(FakeUi.unreadable(clock.now)))
            e.dispatch(AutomationInput.Tick)
        }
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)

        // And the countdown resumes from where it froze, rather than restarting.
        val resumed = mutableListOf<AutomationEffect>()
        repeat(26) {
            clock.advance(500L)
            resumed += e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
            resumed += e.dispatch(AutomationInput.Tick)
        }
        assertTrue(resumed.hasFillInput(), "the workflow must continue after the UI is readable")
    }

    @Test
    fun `13e - a full generate then finish cycle survives system-UI interruptions`() {
        // Reproduces the device log: status bar and unreadable frames arriving
        // in the middle of a generation used to reset the cycle repeatedly.
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)

        repeat(10) {
            clock.advance(500L)
            e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
            if (it % 3 == 0) {
                clock.advance(200L)
                e.dispatch(AutomationInput.Snapshot(FakeUi.unreadable(clock.now)))
            }
            e.dispatch(AutomationInput.Tick)
        }
        val cycleAfterGenerating = e.responseCycleId
        assertEquals(1L, cycleAfterGenerating, "interruptions must not invent new cycles")

        val effects = mutableListOf<AutomationEffect>()
        repeat(40) {
            clock.advance(500L)
            effects += e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
            effects += e.dispatch(AutomationInput.Tick)
        }
        assertTrue(effects.hasFillInput(), "must type once the response really finished")
        assertEquals(1, effects.count { it is AutomationEffect.FillInput })
    }

    @Test
    fun `13b - nothing is typed while another app is in the foreground`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        val effects = mutableListOf<AutomationEffect>()
        repeat(100) {
            clock.advance(500L)
            effects += e.dispatch(AutomationInput.Snapshot(FakeUi.otherApp(clock.now)))
            effects += e.dispatch(AutomationInput.Tick)
        }
        assertFalse(effects.hasFillInput())
        assertFalse(effects.hasClickSend())
        assertEquals(AutomationState.WAITING_FOR_CHATGPT, e.state)
    }

    // 14 --------------------------------------------------------------------

    @Test
    fun `14 - losing the accessibility service moves to ERROR and cancels the countdown`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)

        e.dispatch(AutomationInput.AccessibilityDisconnected)
        assertEquals(AutomationState.ERROR, e.state)
        assertFalse(e.accessibilityConnected)
        assertEquals(0, e.countdownSecondsRemaining)

        val effects = mutableListOf<AutomationEffect>()
        repeat(40) {
            clock.advance(500L)
            effects += e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now)))
        }
        assertFalse(effects.hasFillInput())
    }

    @Test
    fun `14b - reconnecting resumes the workflow`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.AccessibilityDisconnected)
        assertEquals(AutomationState.ERROR, e.state)

        clock.advance(1_000L)
        e.dispatch(AutomationInput.AccessibilityConnected)
        assertEquals(AutomationState.WAITING_FOR_CHATGPT, e.state)
    }

    // 15 --------------------------------------------------------------------

    @Test
    fun `15 - a new assistant message advances the cycle even if generating was missed`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        e.completeSend(clock)
        val firstCycle = e.responseCycleId

        // The next answer arrived so fast that no GENERATING frame was seen,
        // but the last-message signature changed.
        clock.advance(1_000L)
        e.feedStableFinished(clock, signature = "sig-brand-new")
        assertNotEquals(firstCycle, e.responseCycleId)
        assertEquals(AutomationState.POST_RESPONSE_DELAY, e.state)
    }

    @Test
    fun `15b - an unchanged signature does not advance the cycle`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        e.completeSend(clock)
        val cycle = e.responseCycleId

        clock.advance(1_000L)
        e.feedStableFinished(clock, signature = "sig-1")
        assertEquals(cycle, e.responseCycleId)
        assertEquals(AutomationState.WAITING_FOR_NEXT_RESPONSE, e.state)
    }

    // Send path -------------------------------------------------------------

    @Test
    fun `the send button is not clicked in the same instant as typing`() {
        // The ChatGPT app only reveals its send control once the composer holds
        // text, so an immediate click lands on nothing.
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)

        val out = e.dispatch(AutomationInput.InputFillResult(success = true))
        assertEquals(AutomationState.SENDING, e.state)
        assertFalse(out.hasClickSend(), "must wait for the send control to appear")

        var clicks = 0
        repeat(6) {
            clock.advance(500L)
            e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now).withComposerText()))
            val out = e.dispatch(AutomationInput.Tick)
            if (out.hasClickSend()) {
                clicks++
                e.dispatch(AutomationInput.SendResult(success = true))
            }
        }
        assertEquals(1, clicks, "exactly one click while the send is unconfirmed")
    }

    @Test
    fun `a dispatched click is not treated as proof the message was sent`() {
        // Reproduces the device bug: the log said "Message sent" while "Lanjut"
        // was still sitting in the composer.
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        e.dispatch(AutomationInput.InputFillResult(success = true))

        repeat(4) {
            clock.advance(500L)
            e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now).withComposerText()))
            e.dispatch(AutomationInput.Tick)
        }
        val out = e.dispatch(AutomationInput.SendResult(success = true))
        assertFalse(
            out.logs().any { it.contains("Message sent") },
            "a click that returned true is not proof",
        )
        assertEquals(AutomationState.SENDING, e.state)

        // The composer still holds the text, so the send is retried.
        val retried = mutableListOf<AutomationEffect>()
        repeat(8) {
            clock.advance(500L)
            retried += e.dispatch(
                AutomationInput.Snapshot(FakeUi.finished(clock.now).withComposerText()),
            )
            retried += e.dispatch(AutomationInput.Tick)
        }
        assertTrue(retried.hasClickSend(), "an unconfirmed send must be retried")
    }

    @Test
    fun `a send is confirmed once the composer is observed to be empty`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        val out = e.completeSend(clock)

        assertTrue(out.logs().any { it.contains("Message sent") })
        assertEquals(AutomationState.WAITING_FOR_NEXT_RESPONSE, e.state)
    }

    @Test
    fun `a send that never leaves the composer ends in ERROR, not a false success`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        e.dispatch(AutomationInput.InputFillResult(success = true))

        val effects = mutableListOf<AutomationEffect>()
        var reachedError = false
        repeat(60) {
            if (reachedError) return@repeat
            clock.advance(500L)
            effects += e.dispatch(
                AutomationInput.Snapshot(FakeUi.finished(clock.now).withComposerText()),
            )
            val out = e.dispatch(AutomationInput.Tick)
            effects += out
            if (out.hasClickSend()) e.dispatch(AutomationInput.SendResult(success = true))
            if (e.state == AutomationState.ERROR) reachedError = true
        }
        assertFalse(
            effects.logs().any { it.contains("Message sent") },
            "a message still in the composer was never sent",
        )
        assertTrue(reachedError, "an unconfirmed send must end in ERROR")
    }

    @Test
    fun `a failed send is retried a bounded number of times and then errors`() {
        val config = AutomationConfig(maxRetries = 2)
        val (e, clock) = engine(config = config)
        runToSend(e, clock, config)
        e.dispatch(AutomationInput.InputFillResult(success = true))

        var clicks = 0
        repeat(30) {
            if (e.state != AutomationState.SENDING) return@repeat
            clock.advance(500L)
            e.dispatch(AutomationInput.Snapshot(FakeUi.finished(clock.now).withComposerText()))
            if (e.dispatch(AutomationInput.Tick).hasClickSend()) {
                clicks++
                e.dispatch(AutomationInput.SendResult(success = false))
            }
        }
        assertEquals(AutomationState.ERROR, e.state)
        assertTrue(clicks <= config.maxRetries, "clicks must be bounded, got $clicks")
    }

    @Test
    fun `the send gate refuses a stale UI reading`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        e.dispatch(AutomationInput.Start)
        e.dispatch(AutomationInput.Snapshot(FakeUi.generating(clock.now)))
        clock.advance(2_000L)
        e.feedStableFinished(clock)

        // Snapshots stop arriving; only ticks keep coming.
        val effects = mutableListOf<AutomationEffect>()
        repeat(60) {
            clock.advance(1_000L)
            effects += e.dispatch(AutomationInput.Tick)
        }
        assertFalse(effects.hasFillInput(), "a frozen UI feed must not produce a send")
    }

    @Test
    fun `logs describe the workflow without any conversation content`() {
        val config = AutomationConfig()
        val (e, clock) = engine(config = config)
        val effects = runToSend(e, clock, config)
        val messages = effects.logs()
        assertTrue(messages.any { it.contains("Generation detected") })
        assertTrue(messages.any { it.contains("Generation finished") })
        assertTrue(messages.any { it.contains("Starting 10s delay") })
        assertTrue(messages.any { it.contains("typing \"Lanjut\"") })
    }
}
