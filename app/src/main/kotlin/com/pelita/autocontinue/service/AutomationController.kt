package com.pelita.autocontinue.service

import com.pelita.autocontinue.core.ActivityLog
import com.pelita.autocontinue.core.AutomationConfig
import com.pelita.autocontinue.core.AutomationEffect
import com.pelita.autocontinue.core.AutomationEngine
import com.pelita.autocontinue.core.AutomationInput
import com.pelita.autocontinue.core.AutomationState
import com.pelita.autocontinue.core.GenerationState
import com.pelita.autocontinue.core.LogEntry
import com.pelita.autocontinue.core.UiSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the UI renders. */
data class UiState(
    val automationState: AutomationState = AutomationState.IDLE,
    val generationState: GenerationState = GenerationState.UNKNOWN,
    val countdownSeconds: Int = 0,
    val accessibilityConnected: Boolean = false,
    val config: AutomationConfig = AutomationConfig(),
    val lastSnapshot: UiSnapshot? = null,
    val log: List<LogEntry> = emptyList(),
)

/**
 * Process-wide bridge between the accessibility service and the UI.
 *
 * The accessibility service and the Activity live in the same process but have
 * independent lifecycles, so the engine is owned here rather than by either of
 * them. All engine access is synchronized: snapshots arrive on the
 * accessibility thread while button presses arrive on the main thread.
 */
object AutomationController {

    private val lock = Any()
    private val engine = AutomationEngine()
    private val activityLog = ActivityLog()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Set by [PelitaAccessibilityService] so effects can be executed. */
    @Volatile
    var ui: ChatGptUiDetector? = null

    /** Set by [PelitaForegroundService] so the notification can be refreshed. */
    @Volatile
    var onStateChanged: ((UiState) -> Unit)? = null

    val state: AutomationState
        get() = synchronized(lock) { engine.state }

    val isRunning: Boolean
        get() = synchronized(lock) { engine.isRunning }

    fun applyConfig(config: AutomationConfig) {
        synchronized(lock) {
            engine.config = config
            ui?.targetPackage = config.targetPackage
        }
        publish()
    }

    fun start() = submit(AutomationInput.Start)

    fun pause() = submit(AutomationInput.Pause)

    fun resume() = submit(AutomationInput.Resume)

    fun stop() = submit(AutomationInput.Stop)

    fun cancelCountdown() = submit(AutomationInput.CancelCountdown)

    fun onServiceConnected() = submit(AutomationInput.AccessibilityConnected)

    fun onServiceDisconnected() = submit(AutomationInput.AccessibilityDisconnected)

    fun onSnapshot(snapshot: UiSnapshot) = submit(AutomationInput.Snapshot(snapshot))

    fun tick() = submit(AutomationInput.Tick)

    /** Refreshes the debug screen without disturbing the workflow. */
    fun debugSnapshot(): UiSnapshot? {
        val snapshot = ui?.captureSnapshot() ?: return null
        onSnapshot(snapshot)
        return snapshot
    }

    fun clearLog() {
        synchronized(lock) { activityLog.clear() }
        publish()
    }

    /**
     * Feeds one input into the engine and performs whatever it asks for.
     *
     * Effects are executed outside the lock, and their results are fed back in
     * as new inputs, so the engine stays the single source of truth.
     */
    private fun submit(input: AutomationInput) {
        val effects = synchronized(lock) {
            val produced = engine.dispatch(input)
            produced.filterIsInstance<AutomationEffect.Log>().forEach {
                activityLog.add(System.currentTimeMillis(), it.message)
            }
            produced
        }
        publish()

        for (effect in effects) {
            when (effect) {
                is AutomationEffect.FillInput -> {
                    val ok = ui?.fillInput(effect.text) ?: false
                    submit(AutomationInput.InputFillResult(ok))
                }

                AutomationEffect.ClickSend -> {
                    val ok = ui?.sendMessage() ?: false
                    submit(AutomationInput.SendResult(ok))
                }

                is AutomationEffect.Log, is AutomationEffect.StateChanged -> Unit
            }
        }
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            UiState(
                automationState = engine.state,
                generationState = engine.lastGenerationState,
                countdownSeconds = engine.countdownSecondsRemaining,
                accessibilityConnected = engine.accessibilityConnected,
                config = engine.config,
                lastSnapshot = engine.lastSnapshot,
                log = activityLog.snapshot(),
            )
        }
        _uiState.value = snapshot
        onStateChanged?.invoke(snapshot)
    }
}
