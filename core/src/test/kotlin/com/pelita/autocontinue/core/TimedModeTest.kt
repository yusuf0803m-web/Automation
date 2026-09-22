package com.pelita.autocontinue.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MODE A - the fixed-interval fallback.
 *
 * This mode exists for when ChatGPT's UI changes enough that the end of a
 * response can no longer be detected. It is deliberately less clever than
 * MODE B, but it still refuses to cut off a response that is visibly running.
 */
class TimedModeTest {

    private val config = AutomationConfig(
        mode = AutomationMode.TIMED,
        timedIntervalMs = 3 * 60_000L,
    )

    private fun engine(): Pair<AutomationEngine, FakeClock> {
        val clock = FakeClock()
        val e = AutomationEngine(config = config, clock = clock)
        e.dispatch(AutomationInput.AccessibilityConnected)
        e.dispatch(AutomationInput.Start)
        return e to clock
    }

    /** Feeds snapshots and ticks for [seconds], returning everything produced. */
    private fun AutomationEngine.run(
        clock: FakeClock,
        seconds: Int,
        snapshot: (Long) -> UiSnapshot = { FakeUi.finished(it) },
    ): List<AutomationEffect> {
        val all = mutableListOf<AutomationEffect>()
        repeat(seconds * 2) {
            clock.advance(500L)
            all += dispatch(AutomationInput.Snapshot(snapshot(clock.now)))
            all += dispatch(AutomationInput.Tick)
        }
        return all
    }

    @Test
    fun `nothing is sent before the interval elapses`() {
        val (e, clock) = engine()
        val effects = e.run(clock, seconds = 170)
        assertFalse(effects.hasFillInput(), "must not send before the interval")
    }

    @Test
    fun `a message is sent once the interval elapses`() {
        val (e, clock) = engine()
        val effects = e.run(clock, seconds = 190)
        assertTrue(effects.hasFillInput(), "must send once the interval elapsed")
        assertEquals(AutomationState.FILLING_INPUT, e.state)
    }

    @Test
    fun `a visibly running response postpones the round`() {
        val (e, clock) = engine()
        // Well past the interval, but ChatGPT is still streaming throughout.
        val effects = e.run(clock, seconds = 240) { FakeUi.generating(it) }
        assertFalse(effects.hasFillInput(), "must not interrupt a running response")
        assertEquals(AutomationState.CHATGPT_GENERATING, e.state)
    }

    @Test
    fun `the round resumes as soon as the response finishes`() {
        val (e, clock) = engine()
        e.run(clock, seconds = 240) { FakeUi.generating(it) }
        assertFalse(e.state == AutomationState.FILLING_INPUT)

        val effects = e.run(clock, seconds = 5)
        assertTrue(effects.hasFillInput(), "must send once the response is over")
    }

    @Test
    fun `nothing happens while another app is in the foreground`() {
        val (e, clock) = engine()
        val effects = e.run(clock, seconds = 240) { FakeUi.otherApp(it) }
        assertFalse(effects.hasFillInput())
        assertEquals(AutomationState.WAITING_FOR_CHATGPT, e.state)
    }

    @Test
    fun `an unreadable window never triggers a blind send`() {
        val (e, clock) = engine()
        val effects = e.run(clock, seconds = 240) { FakeUi.unreadable(it) }
        assertFalse(effects.hasFillInput(), "no composer means nothing to type into")
    }

    @Test
    fun `pause and stop still hold in timed mode`() {
        val (e, clock) = engine()
        e.dispatch(AutomationInput.Pause)
        val paused = e.run(clock, seconds = 240)
        assertFalse(paused.hasFillInput(), "a paused engine must not send on a timer")

        e.dispatch(AutomationInput.Resume)
        e.dispatch(AutomationInput.Stop)
        val stopped = e.run(clock, seconds = 240)
        assertFalse(stopped.hasFillInput(), "a stopped engine must not send on a timer")
    }

    @Test
    fun `the interval restarts after a confirmed send`() {
        val (e, clock) = engine()
        e.run(clock, seconds = 190)
        assertEquals(AutomationState.FILLING_INPUT, e.state)
        e.completeSend(clock)
        assertEquals(AutomationState.WAITING_FOR_NEXT_RESPONSE, e.state)

        val soon = e.run(clock, seconds = 100)
        assertFalse(soon.hasFillInput(), "the next round waits a full interval")

        val later = e.run(clock, seconds = 100)
        assertTrue(later.hasFillInput(), "and then sends again")
    }

    @Test
    fun `the interval is configurable within its bounds`() {
        assertEquals(1, AutomationConfig().withTimedIntervalMinutes(0).timedIntervalMinutes)
        assertEquals(30, AutomationConfig().withTimedIntervalMinutes(999).timedIntervalMinutes)
        assertEquals(5, AutomationConfig().withTimedIntervalMinutes(5).timedIntervalMinutes)
    }

    @Test
    fun `wait-for-response remains the default mode`() {
        assertEquals(AutomationMode.WAIT_FOR_RESPONSE, AutomationConfig().mode)
    }
}
