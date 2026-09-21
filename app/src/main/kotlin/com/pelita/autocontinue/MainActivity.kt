package com.pelita.autocontinue

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pelita.autocontinue.data.SettingsStore
import com.pelita.autocontinue.service.AutomationController
import com.pelita.autocontinue.service.PelitaAccessibilityService
import com.pelita.autocontinue.service.PelitaForegroundService
import com.pelita.autocontinue.ui.AppScreen
import com.pelita.autocontinue.ui.PelitaTheme

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        settings = SettingsStore(this)
        AutomationController.applyConfig(settings.toConfig())
        requestNotificationPermissionIfNeeded()

        val activity = this
        setContent {
            PelitaTheme {
                val uiState by AutomationController.uiState.collectAsStateWithLifecycle()
                var showOnboarding by remember { mutableStateOf(!settings.onboardingSeen) }
                var accessibilityEnabled by remember {
                    mutableStateOf(PelitaAccessibilityService.isEnabled(activity))
                }

                AppScreen(
                    uiState = uiState,
                    accessibilityEnabled = accessibilityEnabled,
                    showOnboarding = showOnboarding,
                    onDismissOnboarding = {
                        settings.onboardingSeen = true
                        showOnboarding = false
                    },
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onRefreshAccessibility = {
                        accessibilityEnabled = PelitaAccessibilityService.isEnabled(activity)
                    },
                    onDelayChange = { seconds ->
                        settings.delaySeconds = seconds
                        AutomationController.applyConfig(settings.toConfig())
                    },
                    onMessageChange = { message ->
                        settings.message = message
                        AutomationController.applyConfig(settings.toConfig())
                    },
                    onTargetPackageChange = { pkg ->
                        settings.targetPackage = pkg
                        AutomationController.applyConfig(settings.toConfig())
                    },
                    onStart = {
                        PelitaForegroundService.start(activity)
                        AutomationController.start()
                    },
                    onPause = AutomationController::pause,
                    onResume = AutomationController::resume,
                    onStop = {
                        AutomationController.stop()
                        PelitaForegroundService.stop(activity)
                    },
                    onCancelCountdown = AutomationController::cancelCountdown,
                    onRefreshDebug = { AutomationController.debugSnapshot() },
                    onClearLog = AutomationController::clearLog,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AutomationController.applyConfig(settings.toConfig())
    }

    /**
     * The notification carries PAUSE and STOP, so it needs to be visible.
     * Declining only costs the notification, not the automation.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            .launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Opens the system Accessibility settings. The service is never enabled
     * programmatically - only the user can grant it.
     */
    private fun openAccessibilitySettings() {
        startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
