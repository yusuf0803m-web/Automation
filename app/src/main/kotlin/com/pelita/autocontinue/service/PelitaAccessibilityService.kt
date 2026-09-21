package com.pelita.autocontinue.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import com.pelita.autocontinue.data.SettingsStore

/**
 * Reads ChatGPT's UI and performs the "Lanjut" input on the user's behalf.
 *
 * The service is event-driven: it reacts to the four accessibility event types
 * declared in `accessibility_service_config.xml` rather than polling. A slow
 * fallback poll exists only because ChatGPT does not always emit a content
 * change when streaming stops, and it is deliberately conservative.
 *
 * The service reads the node tree only. It never stores, logs or transmits any
 * conversation text.
 */
class PelitaAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: SettingsStore
    private var detector: ChatGptUiDetector? = null

    private var lastSnapshotAtMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            // Drives countdowns and timeouts. Also takes a fallback reading if
            // no accessibility event has arrived recently.
            if (System.currentTimeMillis() - lastSnapshotAtMs >= FALLBACK_POLL_MS) {
                captureAndSubmit()
            }
            AutomationController.tick()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = SettingsStore(this)

        detector = ChatGptUiDetector(
            rootProvider = { rootInActiveWindow },
            foregroundPackageProvider = { rootInActiveWindow?.packageName?.toString() },
            targetPackage = settings.targetPackage,
        )
        AutomationController.ui = detector
        AutomationController.applyConfig(settings.toConfig())
        AutomationController.onServiceConnected()

        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_INTERVAL_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!AutomationController.isRunning) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            -> captureAndSubmit()

            else -> Unit
        }
    }

    override fun onInterrupt() {
        AutomationController.onServiceDisconnected()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(ticker)
        AutomationController.ui = null
        AutomationController.onServiceDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        AutomationController.ui = null
        AutomationController.onServiceDisconnected()
        super.onDestroy()
    }

    /**
     * Takes one reading and hands it to the state machine.
     *
     * Readings are throttled so a burst of content-change events during
     * streaming does not walk the node tree dozens of times per second.
     */
    private fun captureAndSubmit() {
        val now = System.currentTimeMillis()
        if (now - lastSnapshotAtMs < MIN_SNAPSHOT_INTERVAL_MS) return
        lastSnapshotAtMs = now
        val snapshot = detector?.captureSnapshot() ?: return
        AutomationController.onSnapshot(snapshot)
    }

    companion object {
        /** Ticks drive the countdown display and the timeouts. */
        private const val TICK_INTERVAL_MS = 500L

        /** Upper bound on how often the node tree is walked. */
        private const val MIN_SNAPSHOT_INTERVAL_MS = 400L

        /**
         * Conservative fallback. Only used when ChatGPT has gone quiet without
         * telling us, which happens when a stream ends without a final content
         * change event.
         */
        private const val FALLBACK_POLL_MS = 1_500L

        /**
         * Whether the user has enabled this service in system settings.
         *
         * There is no API to enable it programmatically, and this app does not
         * try: the user must grant it themselves.
         */
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${PelitaAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}
