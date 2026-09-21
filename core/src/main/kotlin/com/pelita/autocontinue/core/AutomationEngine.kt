package com.pelita.autocontinue.core

/**
 * MODE B - WAIT FOR RESPONSE.
 *
 * A deterministic, event-driven state machine. It owns no threads, no Android
 * types and no timers of its own: the platform layer feeds it
 * [AutomationInput]s and performs the returned [AutomationEffect]s. That is
 * what makes every rule in here unit-testable without a device.
 *
 * Safety invariants, all enforced in [beginSendIfAllowed]:
 *  - never act unless the target app is in the foreground;
 *  - never act on [GenerationState.UNKNOWN];
 *  - never send twice for the same response cycle;
 *  - never act while PAUSED, STOPPED or IDLE.
 */
class AutomationEngine(
    config: AutomationConfig = AutomationConfig(),
    private val clock: Clock = SystemClock,
    private val detector: GenerationDetector = GenerationStateDetector,
) {

    var config: AutomationConfig = config
        set(value) {
            field = value
            finishedDebouncer = StabilityDebouncer(value.finishedDebounceMs)
        }

    var state: AutomationState = AutomationState.IDLE
        private set

    /** True between MULAI and STOP, independent of PAUSED/ERROR excursions. */
    var isRunning: Boolean = false
        private set

    var accessibilityConnected: Boolean = false
        private set

    /** Increments once per observed response cycle. Basis of duplicate prevention. */
    var responseCycleId: Long = 0L
        private set

    /** The cycle a message was already sent for, or null. */
    var sentForCycleId: Long? = null
        private set

    /** Last raw detector reading, for the debug screen. */
    var lastGenerationState: GenerationState = GenerationState.UNKNOWN
        private set

    var lastSnapshot: UiSnapshot? = null
        private set

    private var finishedDebouncer = StabilityDebouncer(config.finishedDebounceMs)

    private var countdownRemainingMs: Long = 0L
    private var lastCountdownTickAtMs: Long = 0L
    private var stateEnteredAtMs: Long = 0L
    private var attempts: Int = 0
    private var signatureAtSend: String? = null
    private var searchHintLogged: Boolean = false
    private var foregroundLostSinceMs: Long? = null
    private var lastAttemptAtMs: Long = 0L
    private var pausedFrom: AutomationState = AutomationState.WAITING_FOR_CHATGPT

    /** True once a message has been sent for the current cycle. */
    val messageSentForCurrentResponse: Boolean
        get() = sentForCycleId == responseCycleId

    /** Remaining countdown in whole seconds, for "Mengirim \"Lanjut\" dalam: N". */
    val countdownSecondsRemaining: Int
        get() = if (state == AutomationState.POST_RESPONSE_DELAY) {
            ((countdownRemainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)
        } else {
            0
        }

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    fun dispatch(input: AutomationInput): List<AutomationEffect> {
        val effects = mutableListOf<AutomationEffect>()
        val now = clock.nowMs()
        when (input) {
            AutomationInput.Start -> onStart(now, effects)
            AutomationInput.Stop -> onStop(now, effects)
            AutomationInput.Pause -> onPause(now, effects)
            AutomationInput.Resume -> onResume(now, effects)
            AutomationInput.CancelCountdown -> onCancelCountdown(now, effects)
            AutomationInput.AccessibilityConnected -> onAccessibilityConnected(now, effects)
            AutomationInput.AccessibilityDisconnected -> onAccessibilityDisconnected(now, effects)
            is AutomationInput.Snapshot -> onSnapshot(input.snapshot, now, effects)
            AutomationInput.Tick -> onTick(now, effects)
            is AutomationInput.InputFillResult -> onInputFilled(input.success, now, effects)
            is AutomationInput.SendResult -> onSendResult(input.success, now, effects)
        }
        return effects
    }

    // -----------------------------------------------------------------------
    // User / lifecycle commands
    // -----------------------------------------------------------------------

    private fun onStart(now: Long, effects: MutableList<AutomationEffect>) {
        if (isRunning && !state.isInert) return
        isRunning = true
        attempts = 0
        sentForCycleId = null
        searchHintLogged = false
        foregroundLostSinceMs = null
        lastAttemptAtMs = 0L
        finishedDebouncer.reset()
        cancelCountdown()
        log(effects, "Automation started (MODE B - wait for response)")
        transition(AutomationState.WAITING_FOR_CHATGPT, now, effects)
    }

    private fun onStop(now: Long, effects: MutableList<AutomationEffect>) {
        isRunning = false
        cancelCountdown()
        finishedDebouncer.reset()
        attempts = 0
        log(effects, "Automation stopped")
        transition(AutomationState.STOPPED, now, effects)
    }

    private fun onPause(now: Long, effects: MutableList<AutomationEffect>) {
        if (state == AutomationState.PAUSED || state == AutomationState.STOPPED) return
        pausedFrom = state
        cancelCountdown()
        finishedDebouncer.reset()
        log(effects, "Paused")
        transition(AutomationState.PAUSED, now, effects)
    }

    private fun onResume(now: Long, effects: MutableList<AutomationEffect>) {
        if (state != AutomationState.PAUSED) return
        if (!isRunning) {
            transition(AutomationState.STOPPED, now, effects)
            return
        }
        finishedDebouncer.reset()
        log(effects, "Resumed - re-checking ChatGPT state")
        // Deliberately resume into WAITING_FOR_CHATGPT rather than back into
        // pausedFrom: the UI may have changed completely while paused, and the
        // next snapshot re-derives the truth. Duplicate prevention is retained
        // because sentForCycleId is untouched.
        transition(AutomationState.WAITING_FOR_CHATGPT, now, effects)
    }

    private fun onCancelCountdown(now: Long, effects: MutableList<AutomationEffect>) {
        if (state != AutomationState.POST_RESPONSE_DELAY) return
        cancelCountdown()
        finishedDebouncer.reset()
        log(effects, "Countdown cancelled by user")
        transition(AutomationState.WAITING_FOR_CHATGPT, now, effects)
    }

    private fun onAccessibilityConnected(now: Long, effects: MutableList<AutomationEffect>) {
        accessibilityConnected = true
        log(effects, "Service connected")
        if (isRunning && state == AutomationState.ERROR) {
            transition(AutomationState.WAITING_FOR_CHATGPT, now, effects)
        }
    }

    private fun onAccessibilityDisconnected(now: Long, effects: MutableList<AutomationEffect>) {
        accessibilityConnected = false
        cancelCountdown()
        finishedDebouncer.reset()
        log(effects, "Accessibility service disconnected")
        if (isRunning) {
            transition(AutomationState.ERROR, now, effects)
        }
    }

    // -----------------------------------------------------------------------
    // Snapshots
    // -----------------------------------------------------------------------

    private fun onSnapshot(
        snapshot: UiSnapshot,
        now: Long,
        effects: MutableList<AutomationEffect>,
    ) {
        lastSnapshot = snapshot
        if (!isRunning || state.isInert || state == AutomationState.ERROR) {
            // Still record the reading for the debug screen, but do not act.
            lastGenerationState = detector.detect(snapshot)
            return
        }
        if (!accessibilityConnected) return

        when (snapshot.targetForeground) {
            // The window could not be read, or only a system overlay (status
            // bar, keyboard) was visible. That is not evidence that the user
            // left ChatGPT, so hold position. Reporting UNKNOWN freezes any
            // running countdown without cancelling it.
            Presence.UNKNOWN -> {
                lastGenerationState = GenerationState.UNKNOWN
                finishedDebouncer.reset()
                return
            }

            // CASE E: another app is confirmed in front. Debounced, because a
            // single frame is not enough to abandon the workflow.
            Presence.NOT_FOUND -> {
                lastGenerationState = GenerationState.UNKNOWN
                finishedDebouncer.reset()
                val since = foregroundLostSinceMs ?: now.also { foregroundLostSinceMs = it }
                if (now - since < config.foregroundLostDebounceMs) return
                cancelCountdown()
                if (state != AutomationState.WAITING_FOR_CHATGPT) {
                    val pkg = snapshot.foregroundPackage ?: "unknown"
                    log(effects, "ChatGPT not in foreground ($pkg)")
                    transition(AutomationState.WAITING_FOR_CHATGPT, now, effects)
                }
                return
            }

            Presence.FOUND -> foregroundLostSinceMs = null
        }

        val raw = detector.detect(snapshot)
        lastGenerationState = raw

        when (raw) {
            GenerationState.GENERATING -> onGenerating(now, effects)
            GenerationState.UNKNOWN -> finishedDebouncer.reset()
            GenerationState.FINISHED -> {
                val stable = finishedDebouncer.update(GenerationState.FINISHED, now)
                if (stable != null) {
                    onStableFinished(snapshot, now, effects)
                }
            }
        }
    }

    private fun onGenerating(now: Long, effects: MutableList<AutomationEffect>) {
        finishedDebouncer.reset()
        if (state == AutomationState.CHATGPT_GENERATING) return
        // A new generation is the one event that clears duplicate prevention.
        beginNewResponseCycle()
        cancelCountdown()
        log(effects, "Generation detected (cycle #$responseCycleId)")
        transition(AutomationState.CHATGPT_GENERATING, now, effects)
    }

    private fun onStableFinished(
        snapshot: UiSnapshot,
        now: Long,
        effects: MutableList<AutomationEffect>,
    ) {
        when (state) {
            AutomationState.CHATGPT_GENERATING -> {
                log(effects, "Generation finished")
                enterFinished(now, effects)
            }

            AutomationState.WAITING_FOR_CHATGPT -> {
                // Attached to an idle ChatGPT - treat it as a completed response.
                log(effects, "ChatGPT idle and ready")
                enterFinished(now, effects)
            }

            AutomationState.WAITING_FOR_NEXT_RESPONSE -> {
                // We already sent for this cycle. Only a *new* assistant message
                // may advance the cycle, and only if the platform layer supplies
                // a signature we can compare.
                val signature = snapshot.lastMessageSignature
                if (config.allowSignatureCycleAdvance &&
                    signature != null &&
                    signature != signatureAtSend
                ) {
                    beginNewResponseCycle()
                    log(effects, "New response detected (cycle #$responseCycleId)")
                    enterFinished(now, effects)
                }
            }

            else -> Unit
        }
    }

    /**
     * CHATGPT_FINISHED is a decision point, not a resting place: either start
     * the countdown, or refuse because this cycle was already handled.
     */
    private fun enterFinished(now: Long, effects: MutableList<AutomationEffect>) {
        transition(AutomationState.CHATGPT_FINISHED, now, effects)
        if (messageSentForCurrentResponse) {
            log(effects, "Already sent for this response - waiting for the next generation")
            transition(AutomationState.WAITING_FOR_NEXT_RESPONSE, now, effects)
            return
        }
        countdownRemainingMs = config.postResponseDelayMs
        lastCountdownTickAtMs = now
        log(effects, "Starting ${config.postResponseDelaySeconds}s delay")
        transition(AutomationState.POST_RESPONSE_DELAY, now, effects)
    }

    // -----------------------------------------------------------------------
    // Time: countdown and timeouts only. Never a trigger on its own.
    // -----------------------------------------------------------------------

    private fun onTick(now: Long, effects: MutableList<AutomationEffect>) {
        if (!isRunning || state.isInert) return

        when (state) {
            AutomationState.POST_RESPONSE_DELAY -> tickCountdown(now, effects)

            AutomationState.FILLING_INPUT ->
                if (now - stateEnteredAtMs >= config.inputTimeoutMs) {
                    fail(now, effects, "Timed out looking for the input field")
                } else {
                    retryAfterDelay(now, effects, "input") {
                        AutomationEffect.FillInput(config.message)
                    }
                }

            AutomationState.SENDING ->
                if (now - stateEnteredAtMs >= config.sendTimeoutMs) {
                    fail(now, effects, "Timed out looking for the send button")
                } else {
                    retryAfterDelay(now, effects, "send") { AutomationEffect.ClickSend }
                }

            AutomationState.WAITING_FOR_NEXT_RESPONSE ->
                if (now - stateEnteredAtMs >= config.waitForNextResponseTimeoutMs) {
                    // Safe re-arm: the cycle id is unchanged, so duplicate
                    // prevention still blocks a second send for this response.
                    log(effects, "No new generation seen - re-arming")
                    transition(AutomationState.WAITING_FOR_CHATGPT, now, effects)
                }

            AutomationState.WAITING_FOR_CHATGPT ->
                if (!searchHintLogged && now - stateEnteredAtMs >= config.chatGptSearchTimeoutMs) {
                    searchHintLogged = true
                    log(effects, "ChatGPT still not detected - open it in the foreground")
                }

            AutomationState.ERROR ->
                if (now - stateEnteredAtMs >= config.errorCooldownMs) {
                    attempts = 0
                    log(effects, "Recovering from error")
                    transition(AutomationState.WAITING_FOR_CHATGPT, now, effects)
                }

            else -> Unit
        }
    }

    /**
     * The countdown only advances while the last reading was a confirmed,
     * fresh FINISHED. If the UI becomes unreadable the countdown freezes
     * instead of expiring blindly.
     */
    private fun tickCountdown(now: Long, effects: MutableList<AutomationEffect>) {
        val snapshot = lastSnapshot
        val snapshotIsFresh =
            snapshot != null && now - snapshot.capturedAtMs <= config.snapshotStaleMs
        val confirmedFinished =
            snapshotIsFresh && lastGenerationState == GenerationState.FINISHED

        if (!confirmedFinished) {
            lastCountdownTickAtMs = now
            return
        }

        countdownRemainingMs -= (now - lastCountdownTickAtMs)
        lastCountdownTickAtMs = now
        if (countdownRemainingMs > 0L) return

        cancelCountdown()
        beginSendIfAllowed(now, effects)
    }

    // -----------------------------------------------------------------------
    // Sending
    // -----------------------------------------------------------------------

    /** The single gate every send must pass through. */
    private fun beginSendIfAllowed(now: Long, effects: MutableList<AutomationEffect>) {
        val snapshot = lastSnapshot
        val reason = when {
            !isRunning -> "automation is not running"
            state.isInert -> "automation is ${state.name.lowercase()}"
            !accessibilityConnected -> "accessibility service is not connected"
            snapshot == null -> "no UI reading available"
            !snapshot.isTargetForeground -> "ChatGPT is not in the foreground"
            now - snapshot.capturedAtMs > config.snapshotStaleMs -> "UI reading is stale"
            lastGenerationState != GenerationState.FINISHED ->
                "generation state is ${lastGenerationState.name}"
            messageSentForCurrentResponse -> "already sent for this response"
            else -> null
        }
        if (reason != null) {
            log(effects, "Not sending: $reason")
            transition(AutomationState.WAITING_FOR_CHATGPT, now, effects)
            return
        }

        attempts = 0
        lastAttemptAtMs = now
        log(effects, "Input found - typing \"${config.message}\"")
        transition(AutomationState.FILLING_INPUT, now, effects)
        effects += AutomationEffect.FillInput(config.message)
    }

    private fun onInputFilled(
        success: Boolean,
        now: Long,
        effects: MutableList<AutomationEffect>,
    ) {
        if (state != AutomationState.FILLING_INPUT) return
        if (success) {
            attempts = 0
            lastAttemptAtMs = now
            log(effects, "Send clicked")
            transition(AutomationState.SENDING, now, effects)
            effects += AutomationEffect.ClickSend
            return
        }
        attempts++
        lastAttemptAtMs = now
        if (attempts >= config.maxRetries) {
            fail(now, effects, "Could not fill the input after ${config.maxRetries} attempts")
        }
        // Otherwise stay put: the retry is issued from onTick once
        // config.retryDelayMs has passed, so the UI gets time to settle.
    }

    private fun onSendResult(
        success: Boolean,
        now: Long,
        effects: MutableList<AutomationEffect>,
    ) {
        if (state != AutomationState.SENDING) return
        if (success) {
            attempts = 0
            sentForCycleId = responseCycleId
            signatureAtSend = lastSnapshot?.lastMessageSignature
            log(effects, "Message sent")
            log(effects, "Waiting for next generation")
            transition(AutomationState.WAITING_FOR_NEXT_RESPONSE, now, effects)
            return
        }
        attempts++
        lastAttemptAtMs = now
        if (attempts >= config.maxRetries) {
            fail(now, effects, "Could not click send after ${config.maxRetries} attempts")
        }
        // Otherwise wait for onTick to retry, as above.
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Issues the next attempt once [AutomationConfig.retryDelayMs] has elapsed.
     * Retrying straight away is pointless - the UI has not changed yet.
     */
    private fun retryAfterDelay(
        now: Long,
        effects: MutableList<AutomationEffect>,
        label: String,
        effect: () -> AutomationEffect,
    ) {
        if (attempts == 0) return
        if (attempts >= config.maxRetries) return
        if (now - lastAttemptAtMs < config.retryDelayMs) return
        lastAttemptAtMs = now
        log(effects, "Retrying $label (${attempts + 1}/${config.maxRetries})")
        effects += effect()
    }

    private fun fail(now: Long, effects: MutableList<AutomationEffect>, message: String) {
        cancelCountdown()
        finishedDebouncer.reset()
        log(effects, "Error: $message")
        transition(AutomationState.ERROR, now, effects)
    }

    private fun beginNewResponseCycle() {
        responseCycleId++
        signatureAtSend = null
    }

    private fun cancelCountdown() {
        countdownRemainingMs = 0L
        lastCountdownTickAtMs = 0L
    }

    private fun transition(
        target: AutomationState,
        now: Long,
        effects: MutableList<AutomationEffect>,
    ) {
        val previous = state
        state = target
        stateEnteredAtMs = now
        if (target == AutomationState.WAITING_FOR_CHATGPT && previous != target) {
            searchHintLogged = false
        }
        effects += AutomationEffect.StateChanged(previous, target)
    }

    private fun log(effects: MutableList<AutomationEffect>, message: String) {
        effects += AutomationEffect.Log(message)
    }
}
