package com.pelita.autocontinue.service

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.pelita.autocontinue.core.ChatGptNodeHeuristics
import com.pelita.autocontinue.core.ChatGptUiPort
import com.pelita.autocontinue.core.NodeDescriptor
import com.pelita.autocontinue.core.Presence
import com.pelita.autocontinue.core.UiSnapshot

/**
 * The one and only place that touches ChatGPT's accessibility tree.
 *
 * Everything is done through accessibility nodes - there is no coordinate
 * tapping anywhere in this project. Responsibilities are split deliberately:
 *
 *  - this class walks the tree and performs actions on real nodes;
 *  - [ChatGptNodeHeuristics] in `:core` decides *what each node is*, and is
 *    fully unit-tested.
 *
 * When ChatGPT's UI changes, the heuristics are usually what needs updating.
 *
 * The detector is conservative by design: when the tree cannot be read it
 * reports an all-UNKNOWN snapshot, which makes the engine wait instead of act.
 */
class ChatGptUiDetector(
    private val rootProvider: () -> AccessibilityNodeInfo?,
    private val foregroundPackageProvider: () -> String?,
    targetPackage: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChatGptUiPort {

    @Volatile
    var targetPackage: String = targetPackage

    override fun isChatGptForeground(): Boolean =
        foregroundPackageProvider() == targetPackage

    override fun captureSnapshot(): UiSnapshot {
        val now = clock()
        val pkg = foregroundPackageProvider()
        return when {
            // No readable application window. This happens routinely for a
            // frame or two while windows change, and it is NOT evidence that
            // the user left ChatGPT.
            pkg == null -> UiSnapshot.unreadable(foregroundPackage = null, capturedAtMs = now)

            pkg != targetPackage ->
                UiSnapshot.otherAppInFront(foregroundPackage = pkg, capturedAtMs = now)

            else -> {
                val tree = readTree()
                ChatGptNodeHeuristics.snapshotOf(
                    foregroundPackage = pkg,
                    targetForeground = Presence.FOUND,
                    nodes = tree?.map { it.descriptor }.orEmpty(),
                    capturedAtMs = now,
                    // Null means the window could not be read; that is UNKNOWN,
                    // not "nothing is there".
                    treeReadable = tree != null,
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Actions - node based, never coordinates
    // -----------------------------------------------------------------------

    fun findInputField(): AccessibilityNodeInfo? =
        firstMatching(ChatGptNodeHeuristics::isInputField)

    fun findSendButton(): AccessibilityNodeInfo? =
        firstMatching(ChatGptNodeHeuristics::isSendButton)

    fun findStopGeneratingButton(): AccessibilityNodeInfo? =
        firstMatching(ChatGptNodeHeuristics::isStopGeneratingButton)

    override fun fillInput(text: String): Boolean {
        if (!isChatGptForeground()) return false
        val field = findInputField() ?: return false
        return try {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Sends the composed message.
     *
     * Preference order, both node based:
     *  1. click the send control;
     *  2. ask the composer's IME to submit (ACTION_IME_ENTER).
     *
     * The fallback matters because the ChatGPT app only reveals a send control
     * once the composer holds text, and its label can change between releases.
     * There is still no coordinate tapping anywhere.
     */
    override fun sendMessage(): Boolean {
        if (!isChatGptForeground()) return false
        return try {
            val button = findSendButton()
            val clicked = button
                ?.let { clickableSelfOrAncestor(it) }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ?: false
            if (clicked) return true
            submitViaIme()
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun submitViaIme(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val field = findInputField() ?: return false
        return field.performAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id,
        )
    }

    // -----------------------------------------------------------------------
    // Tree walking
    // -----------------------------------------------------------------------

    private class Entry(val node: AccessibilityNodeInfo, val descriptor: NodeDescriptor)

    private fun firstMatching(predicate: (NodeDescriptor) -> Boolean): AccessibilityNodeInfo? =
        readTree()?.firstOrNull { predicate(it.descriptor) }?.node

    /**
     * Breadth-first walk, bounded in both breadth and depth so a pathological
     * tree can never stall the accessibility thread.
     *
     * @return null when the window is unreadable or a node threw mid-walk.
     */
    private fun readTree(): List<Entry>? {
        val root = rootProvider() ?: return null
        return try {
            val out = ArrayList<Entry>(64)
            val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            queue.addLast(root to 0)
            while (queue.isNotEmpty() && out.size < MAX_NODES) {
                val (node, depth) = queue.removeFirst()
                out += Entry(node, node.describe())
                if (depth >= MAX_DEPTH) continue
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
            out
        } catch (e: RuntimeException) {
            // A node can be recycled underneath us while a window changes.
            null
        }
    }

    private fun AccessibilityNodeInfo.describe(): NodeDescriptor = NodeDescriptor(
        className = className?.toString(),
        viewId = viewIdResourceName,
        contentDescription = contentDescription?.toString(),
        text = text?.toString(),
        isClickable = isClickable,
        isEditable = isEditable,
        isVisible = isVisibleToUser,
        hasClickableAncestor = clickableSelfOrAncestor(this) != null,
    )

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_CLICK_ANCESTOR_HOPS) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private companion object {
        const val MAX_NODES = 600
        const val MAX_DEPTH = 40
        const val MAX_CLICK_ANCESTOR_HOPS = 5
    }
}
