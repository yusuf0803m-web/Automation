package com.pelita.autocontinue.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Section 22: a fake accessibility node tree, so the whole detection path can
 * be exercised without ChatGPT installed on a real device.
 *
 * These trees are modelled on what a Compose-based chat app typically exposes:
 * an editable composer, an icon button carrying a contentDescription, and a
 * scrolling list of message texts.
 */
class FakeNodeTreeTest {

    private val composer = NodeDescriptor(
        className = "android.widget.EditText",
        viewId = "com.openai.chatgpt:id/prompt_input",
        isEditable = true,
        isClickable = true,
    )

    private val sendButton = NodeDescriptor(
        className = "android.widget.ImageView",
        contentDescription = "Send message",
        isClickable = true,
    )

    private val stopButton = NodeDescriptor(
        className = "android.widget.ImageView",
        contentDescription = "Stop generating",
        isClickable = true,
    )

    private val spinner = NodeDescriptor(className = "android.widget.ProgressBar")

    private fun conversation(vararg messages: String): List<NodeDescriptor> =
        messages.map { NodeDescriptor(className = "android.widget.TextView", text = it) }

    private fun snapshotOf(
        nodes: List<NodeDescriptor>,
        pkg: String = AutomationConfig.DEFAULT_TARGET_PACKAGE,
        target: Presence = Presence.FOUND,
        readable: Boolean = true,
    ): UiSnapshot = ChatGptNodeHeuristics.snapshotOf(
        foregroundPackage = pkg,
        targetForeground = target,
        nodes = nodes,
        capturedAtMs = 0L,
        treeReadable = readable,
    )

    // CASE A ----------------------------------------------------------------

    @Test
    fun `CASE A - a tree containing a stop button is detected as GENERATING`() {
        val tree = conversation("Ini adalah jawaban yang sedang ditulis") +
            listOf(composer, stopButton, spinner)
        val snapshot = snapshotOf(tree)

        assertEquals(Presence.FOUND, snapshot.stopGeneratingButton)
        assertEquals(GenerationState.GENERATING, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `CASE A - a stop button is never mistaken for a send button`() {
        assertFalse(ChatGptNodeHeuristics.isSendButton(stopButton))
        assertTrue(ChatGptNodeHeuristics.isStopGeneratingButton(stopButton))
    }

    @Test
    fun `an Indonesian stop label is recognised`() {
        val berhenti = stopButton.copy(contentDescription = "Berhenti membuat")
        assertTrue(ChatGptNodeHeuristics.isStopGeneratingButton(berhenti))
        assertFalse(ChatGptNodeHeuristics.isSendButton(berhenti))
    }

    @Test
    fun `an Indonesian send label is recognised`() {
        val kirim = sendButton.copy(contentDescription = "Kirim pesan")
        assertTrue(ChatGptNodeHeuristics.isSendButton(kirim))
    }

    // CASE B ----------------------------------------------------------------

    @Test
    fun `CASE B - composer and send present, nothing generating, means FINISHED`() {
        val tree = conversation("Jawaban lengkap dari ChatGPT") + listOf(composer, sendButton)
        val snapshot = snapshotOf(tree)

        assertEquals(Presence.FOUND, snapshot.inputField)
        assertEquals(Presence.FOUND, snapshot.sendButton)
        assertEquals(Presence.NOT_FOUND, snapshot.stopGeneratingButton)
        assertEquals(GenerationState.FINISHED, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `a send button identified only by its view id still counts`() {
        val idOnly = NodeDescriptor(
            className = "android.view.View",
            viewId = "com.openai.chatgpt:id/send_button",
            isClickable = true,
        )
        val snapshot = snapshotOf(listOf(composer, idOnly))
        assertEquals(GenerationState.FINISHED, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `a send icon whose click is handled by its parent still counts`() {
        val icon = NodeDescriptor(
            className = "android.widget.ImageView",
            contentDescription = "Send",
            isClickable = false,
            hasClickableAncestor = true,
        )
        assertTrue(ChatGptNodeHeuristics.isSendButton(icon))
    }

    // CASE C ----------------------------------------------------------------

    @Test
    fun `CASE C - an unreadable tree is UNKNOWN even with ChatGPT in front`() {
        val snapshot = snapshotOf(emptyList(), readable = false)

        assertEquals(Presence.UNKNOWN, snapshot.stopGeneratingButton)
        assertEquals(GenerationState.UNKNOWN, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `a composer with no recognisable send button is still FINISHED`() {
        // ChatGPT shows a microphone instead of a send arrow while the composer
        // is empty, so the send button must not gate the end of a response.
        val mysteryButton = NodeDescriptor(
            className = "android.view.View",
            viewId = "com.openai.chatgpt:id/unknown_widget_42",
            isClickable = true,
        )
        val snapshot = snapshotOf(conversation("Jawaban") + listOf(composer, mysteryButton))

        assertEquals(Presence.FOUND, snapshot.inputField)
        assertEquals(Presence.NOT_FOUND, snapshot.sendButton)
        assertEquals(GenerationState.FINISHED, GenerationStateDetector.detect(snapshot))
    }

    @Test
    fun `CASE C - automation waits instead of sending when the tree is unreadable`() {
        val clock = FakeClock()
        val engine = AutomationEngine(clock = clock)
        engine.dispatch(AutomationInput.AccessibilityConnected)
        engine.dispatch(AutomationInput.Start)

        val effects = mutableListOf<AutomationEffect>()
        repeat(120) {
            clock.advance(500L)
            val snapshot = ChatGptNodeHeuristics.snapshotOf(
                foregroundPackage = AutomationConfig.DEFAULT_TARGET_PACKAGE,
                targetForeground = Presence.FOUND,
                nodes = emptyList(),
                capturedAtMs = clock.now,
                treeReadable = false,
            )
            effects += engine.dispatch(AutomationInput.Snapshot(snapshot))
            effects += engine.dispatch(AutomationInput.Tick)
        }
        assertFalse(effects.hasFillInput(), "an unrecognised UI must never be typed into")
        assertFalse(effects.hasClickSend())
    }

    @Test
    fun `an unreadable window yields an all-UNKNOWN snapshot`() {
        val snapshot = snapshotOf(emptyList(), readable = false)
        assertEquals(Presence.UNKNOWN, snapshot.inputField)
        assertEquals(Presence.UNKNOWN, snapshot.sendButton)
        assertEquals(Presence.UNKNOWN, snapshot.stopGeneratingButton)
        assertEquals(GenerationState.UNKNOWN, GenerationStateDetector.detect(snapshot))
    }

    // CASE E ----------------------------------------------------------------

    @Test
    fun `CASE E - another app in the foreground is never inspected`() {
        val snapshot = snapshotOf(
            listOf(composer, sendButton),
            pkg = "com.android.launcher",
            target = Presence.NOT_FOUND,
        )
        assertEquals(Presence.UNKNOWN, snapshot.sendButton)
        assertEquals(GenerationState.UNKNOWN, GenerationStateDetector.detect(snapshot))
    }

    // Invisible nodes -------------------------------------------------------

    @Test
    fun `nodes that are not visible to the user are ignored`() {
        val hiddenStop = stopButton.copy(isVisible = false)
        val snapshot = snapshotOf(listOf(composer, sendButton, hiddenStop))
        assertEquals(Presence.NOT_FOUND, snapshot.stopGeneratingButton)
        assertEquals(GenerationState.FINISHED, GenerationStateDetector.detect(snapshot))
    }

    // Message signature -----------------------------------------------------

    @Test
    fun `the signature changes when a new assistant message arrives`() {
        val first = ChatGptNodeHeuristics.lastMessageSignature(
            conversation("Gambar pertama sudah selesai dibuat"),
        )
        val second = ChatGptNodeHeuristics.lastMessageSignature(
            conversation("Gambar pertama sudah selesai dibuat", "Gambar kedua sudah selesai"),
        )
        assertNotEquals(first, second)
    }

    @Test
    fun `the signature is stable while nothing changes`() {
        val nodes = conversation("Gambar pertama sudah selesai dibuat")
        assertEquals(
            ChatGptNodeHeuristics.lastMessageSignature(nodes),
            ChatGptNodeHeuristics.lastMessageSignature(nodes),
        )
    }

    @Test
    fun `the signature never contains the message text`() {
        val secret = "Rahasia yang tidak boleh bocor ke mana pun"
        val signature = ChatGptNodeHeuristics.lastMessageSignature(conversation(secret))
        assertTrue(signature != null)
        assertFalse(signature.contains(secret), "the signature must not embed the message")
        // Only a length and a hash, nothing that can be read back.
        assertTrue(Regex("""^\d+:-?\d+$""").matches(signature))
    }

    @Test
    fun `short chrome labels are not treated as messages`() {
        val chrome = listOf(
            NodeDescriptor(className = "android.widget.TextView", text = "OK"),
            NodeDescriptor(className = "android.widget.TextView", text = "New"),
        )
        assertNull(ChatGptNodeHeuristics.lastMessageSignature(chrome))
    }

    @Test
    fun `the composer's own draft text is not treated as a message`() {
        val nodes = listOf(composer.copy(text = "Lanjut sekali lagi ya"))
        assertNull(ChatGptNodeHeuristics.lastMessageSignature(nodes))
    }

    // Composer state --------------------------------------------------------

    @Test
    fun `a composer holding text is reported`() {
        val snapshot = snapshotOf(listOf(composer.copy(text = "Lanjut"), sendButton))
        assertEquals(Presence.FOUND, snapshot.inputHasText)
    }

    @Test
    fun `an empty composer is reported as empty, not unknown`() {
        val snapshot = snapshotOf(listOf(composer, sendButton))
        assertEquals(Presence.NOT_FOUND, snapshot.inputHasText)
    }

    // A full round trip over fake trees --------------------------------------

    @Test
    fun `a full generate-then-finish cycle over fake trees sends exactly one message`() {
        val clock = FakeClock()
        val engine = AutomationEngine(clock = clock)
        engine.dispatch(AutomationInput.AccessibilityConnected)
        engine.dispatch(AutomationInput.Start)

        fun feed(nodes: List<NodeDescriptor>): List<AutomationEffect> {
            val snapshot = ChatGptNodeHeuristics.snapshotOf(
                foregroundPackage = AutomationConfig.DEFAULT_TARGET_PACKAGE,
                targetForeground = Presence.FOUND,
                nodes = nodes,
                capturedAtMs = clock.now,
            )
            return engine.dispatch(AutomationInput.Snapshot(snapshot)) +
                engine.dispatch(AutomationInput.Tick)
        }

        val generating = conversation("Sedang membuat gambar") + listOf(composer, stopButton)
        val finished = conversation("Gambar selesai dibuat dengan baik") +
            listOf(composer, sendButton)

        val effects = mutableListOf<AutomationEffect>()
        repeat(6) {
            clock.advance(500L)
            effects += feed(generating)
        }
        assertEquals(AutomationState.CHATGPT_GENERATING, engine.state)

        repeat(40) {
            clock.advance(500L)
            effects += feed(finished)
        }

        assertEquals(
            1,
            effects.count { it is AutomationEffect.FillInput },
            "exactly one \"Lanjut\" per completed response",
        )
        assertEquals(AutomationState.FILLING_INPUT, engine.state)
    }
}
