package com.pelita.autocontinue.core

/**
 * A platform-independent description of one accessibility node.
 *
 * The Android layer maps `AccessibilityNodeInfo` onto this, which lets the
 * matching rules below be unit-tested against a hand-built node tree instead of
 * a running copy of ChatGPT.
 */
data class NodeDescriptor(
    val className: String? = null,
    val viewId: String? = null,
    val contentDescription: String? = null,
    val text: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isVisible: Boolean = true,
    /** True when some ancestor within a few hops handles the click. */
    val hasClickableAncestor: Boolean = false,
)

/**
 * Everything this project knows about what ChatGPT's controls look like.
 *
 * This is the file to edit when ChatGPT's UI changes. Nothing here depends on
 * Android, so every rule is covered by unit tests.
 */
object ChatGptNodeHeuristics {

    // Matched case-insensitively against contentDescription, text and tooltip.
    // English and Indonesian are both covered: the ChatGPT app follows the
    // system locale, and many users run their phone in Indonesian.
    private val SEND_HINTS = listOf("send", "kirim", "submit")
    private val STOP_HINTS =
        listOf("stop", "berhenti", "hentikan", "cancel streaming", "stop streaming")
    private val GENERATING_HINTS =
        listOf("generating", "thinking", "loading", "memuat", "menghasilkan", "sedang menulis")

    private val INPUT_ID_HINTS = listOf("prompt", "composer", "input", "message", "text_field")
    private val SEND_ID_HINTS = listOf("send", "submit")
    private val STOP_ID_HINTS = listOf("stop", "abort")

    /** Text shorter than this is chrome, not a message. */
    private const val MIN_MESSAGE_LENGTH = 8

    fun isInputField(node: NodeDescriptor): Boolean {
        if (!node.isVisible) return false
        if (node.isEditable) return true
        if (node.className?.contains("EditText", ignoreCase = true) == true) return true
        return matchesId(node.viewId, INPUT_ID_HINTS)
    }

    fun isSendButton(node: NodeDescriptor): Boolean {
        if (!node.isVisible) return false
        if (!node.isClickable && !node.hasClickableAncestor) return false
        // "Stop generating" often sits in the same slot as send and can carry a
        // container id containing "send". Stop always wins.
        if (isStopGeneratingButton(node)) return false
        return matchesText(node, SEND_HINTS) || matchesId(node.viewId, SEND_ID_HINTS)
    }

    fun isStopGeneratingButton(node: NodeDescriptor): Boolean {
        if (!node.isVisible) return false
        if (!node.isClickable && !node.hasClickableAncestor) return false
        return matchesText(node, STOP_HINTS) || matchesId(node.viewId, STOP_ID_HINTS)
    }

    fun isGeneratingIndicator(node: NodeDescriptor): Boolean {
        if (!node.isVisible) return false
        if (node.className?.contains("ProgressBar", ignoreCase = true) == true) return true
        return matchesText(node, GENERATING_HINTS)
    }

    /**
     * Builds a [UiSnapshot] from a whole node tree.
     *
     * @param nodes the tree flattened in breadth-first order.
     * @param treeReadable false when the tree could not be walked at all, in
     *   which case every element stays [Presence.UNKNOWN] and the engine waits.
     */
    fun snapshotOf(
        foregroundPackage: String?,
        targetForeground: Presence,
        nodes: List<NodeDescriptor>,
        capturedAtMs: Long,
        treeReadable: Boolean = true,
    ): UiSnapshot {
        if (targetForeground != Presence.FOUND || !treeReadable) {
            return UiSnapshot(
                foregroundPackage = foregroundPackage,
                targetForeground = targetForeground,
                capturedAtMs = capturedAtMs,
            )
        }
        val input = nodes.firstOrNull(::isInputField)
        return UiSnapshot(
            foregroundPackage = foregroundPackage,
            targetForeground = Presence.FOUND,
            inputField = presenceOf(input != null),
            sendButton = presenceOf(nodes.any(::isSendButton)),
            stopGeneratingButton = presenceOf(nodes.any(::isStopGeneratingButton)),
            generatingIndicator = presenceOf(nodes.any(::isGeneratingIndicator)),
            inputHasText = when {
                input == null -> Presence.UNKNOWN
                input.text.isNullOrEmpty() -> Presence.NOT_FOUND
                else -> Presence.FOUND
            },
            lastMessageSignature = lastMessageSignature(nodes),
            capturedAtMs = capturedAtMs,
        )
    }

    /**
     * A digest of the newest assistant message, used only to tell one response
     * apart from the next.
     *
     * The message text is hashed here and discarded immediately; only the
     * digest is returned, so no conversation content is ever stored, logged or
     * displayed. The digest is not reversible into the message.
     */
    fun lastMessageSignature(nodes: List<NodeDescriptor>): String? {
        val last = nodes.asSequence()
            .filter { it.isVisible && !it.isEditable }
            .mapNotNull { it.text }
            .lastOrNull { it.length >= MIN_MESSAGE_LENGTH }
            ?: return null
        return "${last.length}:${last.hashCode()}"
    }

    private fun presenceOf(found: Boolean): Presence =
        if (found) Presence.FOUND else Presence.NOT_FOUND

    private fun matchesText(node: NodeDescriptor, hints: List<String>): Boolean {
        val haystack = buildString {
            node.contentDescription?.let { append(it).append(' ') }
            node.text?.let { append(it) }
        }
        if (haystack.isBlank()) return false
        return hints.any { haystack.contains(it, ignoreCase = true) }
    }

    private fun matchesId(viewId: String?, hints: List<String>): Boolean {
        if (viewId == null) return false
        return hints.any { viewId.contains(it, ignoreCase = true) }
    }
}
