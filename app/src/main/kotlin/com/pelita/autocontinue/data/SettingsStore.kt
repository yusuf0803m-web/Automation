package com.pelita.autocontinue.data

import android.content.Context
import com.pelita.autocontinue.core.AutomationConfig
import com.pelita.autocontinue.core.AutomationMode

/**
 * Persists the handful of user-visible settings. SharedPreferences keeps the
 * dependency list minimal; there is nothing here worth a database.
 */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("pelita_settings", Context.MODE_PRIVATE)

    var delaySeconds: Int
        get() = prefs.getInt(KEY_DELAY, AutomationConfig.DEFAULT_POST_RESPONSE_DELAY_MS.toInt() / 1000)
            .coerceIn(AutomationConfig.MIN_DELAY_SECONDS, AutomationConfig.MAX_DELAY_SECONDS)
        set(value) = prefs.edit()
            .putInt(
                KEY_DELAY,
                value.coerceIn(AutomationConfig.MIN_DELAY_SECONDS, AutomationConfig.MAX_DELAY_SECONDS),
            )
            .apply()

    var message: String
        get() = prefs.getString(KEY_MESSAGE, AutomationConfig.DEFAULT_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?: AutomationConfig.DEFAULT_MESSAGE
        set(value) = prefs.edit().putString(KEY_MESSAGE, value).apply()

    var targetPackage: String
        get() = prefs.getString(KEY_TARGET, AutomationConfig.DEFAULT_TARGET_PACKAGE)
            ?.takeIf { it.isNotBlank() }
            ?: AutomationConfig.DEFAULT_TARGET_PACKAGE
        set(value) = prefs.edit().putString(KEY_TARGET, value).apply()

    var mode: AutomationMode
        get() = runCatching {
            AutomationMode.valueOf(prefs.getString(KEY_MODE, null) ?: MODE_DEFAULT)
        }.getOrDefault(AutomationMode.WAIT_FOR_RESPONSE)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var timedIntervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, AutomationConfig.DEFAULT_TIMED_INTERVAL_MS.toInt() / 60_000)
            .coerceIn(AutomationConfig.MIN_TIMED_MINUTES, AutomationConfig.MAX_TIMED_MINUTES)
        set(value) = prefs.edit()
            .putInt(
                KEY_INTERVAL,
                value.coerceIn(
                    AutomationConfig.MIN_TIMED_MINUTES,
                    AutomationConfig.MAX_TIMED_MINUTES,
                ),
            )
            .apply()

    var onboardingSeen: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    fun toConfig(): AutomationConfig = AutomationConfig(
        mode = mode,
        targetPackage = targetPackage,
        message = message,
        postResponseDelayMs = delaySeconds * 1000L,
        timedIntervalMs = timedIntervalMinutes * 60_000L,
    )

    private companion object {
        const val KEY_DELAY = "post_response_delay_seconds"
        const val KEY_MESSAGE = "message"
        const val KEY_TARGET = "target_package"
        const val KEY_ONBOARDING = "onboarding_seen"
        const val KEY_MODE = "mode"
        const val KEY_INTERVAL = "timed_interval_minutes"
        val MODE_DEFAULT: String = AutomationMode.WAIT_FOR_RESPONSE.name
    }
}
