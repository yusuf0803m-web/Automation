package com.pelita.autocontinue.core

/** Injectable time source so the state machine can be tested deterministically. */
fun interface Clock {
    fun nowMs(): Long
}

/** Wall-clock implementation used in production. */
object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
