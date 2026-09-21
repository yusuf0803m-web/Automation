package com.pelita.autocontinue.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityLogTest {

    @Test
    fun `the log is bounded`() {
        val log = ActivityLog(capacity = 3)
        repeat(10) { log.add(it.toLong(), "entry $it") }
        assertEquals(3, log.size)
    }

    @Test
    fun `the oldest entries are dropped and the newest come first`() {
        val log = ActivityLog(capacity = 3)
        repeat(5) { log.add(it.toLong(), "entry $it") }
        assertEquals(listOf("entry 4", "entry 3", "entry 2"), log.snapshot().map { it.message })
    }
}
