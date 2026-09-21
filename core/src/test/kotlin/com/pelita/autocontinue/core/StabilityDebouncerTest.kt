package com.pelita.autocontinue.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StabilityDebouncerTest {

    @Test
    fun `a value is not reported before the window elapses`() {
        val debouncer = StabilityDebouncer(1_500L)
        assertNull(debouncer.update(GenerationState.FINISHED, 0L))
        assertNull(debouncer.update(GenerationState.FINISHED, 1_400L))
    }

    @Test
    fun `a value is reported once it has held long enough`() {
        val debouncer = StabilityDebouncer(1_500L)
        debouncer.update(GenerationState.FINISHED, 0L)
        assertEquals(GenerationState.FINISHED, debouncer.update(GenerationState.FINISHED, 1_500L))
    }

    @Test
    fun `flicker back to GENERATING restarts the window`() {
        val debouncer = StabilityDebouncer(1_500L)
        debouncer.update(GenerationState.FINISHED, 0L)
        debouncer.update(GenerationState.FINISHED, 1_000L)
        // The composer momentarily looked idle between streamed chunks.
        debouncer.update(GenerationState.GENERATING, 1_100L)
        assertNull(debouncer.update(GenerationState.FINISHED, 1_600L))
        assertNull(debouncer.update(GenerationState.FINISHED, 2_500L))
        assertEquals(GenerationState.FINISHED, debouncer.update(GenerationState.FINISHED, 3_100L))
    }

    @Test
    fun `an UNKNOWN reading also restarts the window`() {
        val debouncer = StabilityDebouncer(1_000L)
        debouncer.update(GenerationState.FINISHED, 0L)
        debouncer.update(GenerationState.UNKNOWN, 500L)
        assertNull(debouncer.update(GenerationState.FINISHED, 900L))
    }

    @Test
    fun `reset clears the candidate`() {
        val debouncer = StabilityDebouncer(1_000L)
        debouncer.update(GenerationState.FINISHED, 0L)
        debouncer.reset()
        assertNull(debouncer.pendingCandidate)
        assertEquals(0L, debouncer.heldForMs(5_000L))
    }
}
