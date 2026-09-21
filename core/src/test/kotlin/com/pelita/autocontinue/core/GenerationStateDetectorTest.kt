package com.pelita.autocontinue.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Section 22 of the specification: the fake-node-tree cases.
 */
class GenerationStateDetectorTest {

    @Test
    fun `CASE A - stop generating found means GENERATING`() {
        val snapshot = UiSnapshot(
            isTargetForeground = true,
            inputField = Presence.FOUND,
            sendButton = Presence.NOT_FOUND,
            stopGeneratingButton = Presence.FOUND,
            generatingIndicator = Presence.NOT_FOUND,
        )
        assertEquals(GenerationState.GENERATING, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `CASE A2 - stop button missing but spinner present still means GENERATING`() {
        val snapshot = UiSnapshot(
            isTargetForeground = true,
            inputField = Presence.FOUND,
            sendButton = Presence.FOUND,
            stopGeneratingButton = Presence.NOT_FOUND,
            generatingIndicator = Presence.FOUND,
        )
        assertEquals(GenerationState.GENERATING, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `CASE B - no stop button, send and input present means FINISHED`() {
        val snapshot = UiSnapshot(
            isTargetForeground = true,
            inputField = Presence.FOUND,
            sendButton = Presence.FOUND,
            stopGeneratingButton = Presence.NOT_FOUND,
            generatingIndicator = Presence.NOT_FOUND,
        )
        assertEquals(GenerationState.FINISHED, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `CASE C - send button unreadable means UNKNOWN`() {
        assertEquals(GenerationState.UNKNOWN, GenerationStateDetector.detect(FakeUi.ambiguous()))
    }

    @Test
    fun `an unreadable stop button is never reported as FINISHED`() {
        val snapshot = UiSnapshot(
            isTargetForeground = true,
            inputField = Presence.FOUND,
            sendButton = Presence.FOUND,
            stopGeneratingButton = Presence.UNKNOWN,
            generatingIndicator = Presence.NOT_FOUND,
        )
        assertEquals(GenerationState.UNKNOWN, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `an unreadable spinner is never reported as FINISHED`() {
        val snapshot = UiSnapshot(
            isTargetForeground = true,
            inputField = Presence.FOUND,
            sendButton = Presence.FOUND,
            stopGeneratingButton = Presence.NOT_FOUND,
            generatingIndicator = Presence.UNKNOWN,
        )
        assertEquals(GenerationState.UNKNOWN, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `missing input field means UNKNOWN, not FINISHED`() {
        val snapshot = UiSnapshot(
            isTargetForeground = true,
            inputField = Presence.NOT_FOUND,
            sendButton = Presence.FOUND,
            stopGeneratingButton = Presence.NOT_FOUND,
            generatingIndicator = Presence.NOT_FOUND,
        )
        assertEquals(GenerationState.UNKNOWN, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `nothing can be concluded while another app is in the foreground`() {
        assertEquals(GenerationState.UNKNOWN, GenerationStateDetector.detect(FakeUi.otherApp()))
    }

    @Test
    fun `a completely unreadable tree yields UNKNOWN`() {
        assertEquals(
            GenerationState.UNKNOWN,
            GenerationStateDetector.detect(UiSnapshot(isTargetForeground = true)),
        )
    }
}
