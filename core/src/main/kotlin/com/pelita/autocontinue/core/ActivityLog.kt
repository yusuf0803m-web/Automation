package com.pelita.autocontinue.core

/** One line of the visible activity log. */
data class LogEntry(val timestampMs: Long, val message: String)

/**
 * Bounded, in-memory activity log.
 *
 * Holds at most [capacity] entries and stores only automation events - never
 * any part of the ChatGPT conversation.
 */
class ActivityLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<LogEntry>(capacity)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    val size: Int
        get() = entries.size

    fun add(timestampMs: Long, message: String) {
        if (entries.size >= capacity) {
            entries.removeFirst()
        }
        entries.addLast(LogEntry(timestampMs, message))
    }

    /** Newest first, which is how the UI renders it. */
    fun snapshot(): List<LogEntry> = entries.reversed()

    fun clear() = entries.clear()

    companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
