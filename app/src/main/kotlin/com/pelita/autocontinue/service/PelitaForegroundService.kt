package com.pelita.autocontinue.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.pelita.autocontinue.MainActivity
import com.pelita.autocontinue.R
import com.pelita.autocontinue.core.AutomationState

/**
 * Keeps the automation visible and cancellable while it runs.
 *
 * This service does not work around any Android background restriction: it
 * exists so the user always has a PAUSE and a STOP within reach, and so the
 * system does not silently reclaim the workflow while they are looking at
 * ChatGPT.
 */
class PelitaForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        AutomationController.onStateChanged = { state -> updateNotification(state.describe()) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> AutomationController.pause()
            ACTION_STOP -> {
                // STOP must cancel any pending delay and send nothing further.
                AutomationController.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val notification = buildNotification(AutomationController.state.label())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // From Android 14 the type must be declared at start time too.
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AutomationController.onStateChanged = null
        super.onDestroy()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(statusText: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action(R.string.action_pause, ACTION_PAUSE, 1))
            .addAction(action(R.string.action_stop, ACTION_STOP, 2))
            .build()
    }

    private fun action(labelRes: Int, actionName: String, requestCode: Int): Notification.Action {
        val intent = Intent(this, PelitaForegroundService::class.java).setAction(actionName)
        val pending = PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, getString(labelRes), pending).build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "pelita_automation"
        private const val NOTIFICATION_ID = 4711
        const val ACTION_PAUSE = "com.pelita.autocontinue.PAUSE"
        const val ACTION_STOP = "com.pelita.autocontinue.STOP"

        fun start(context: Context) {
            val intent = Intent(context, PelitaForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PelitaForegroundService::class.java))
        }
    }
}

private fun UiState.describe(): String = when (automationState) {
    AutomationState.POST_RESPONSE_DELAY -> "Mengirim dalam ${countdownSeconds}s"
    else -> automationState.label()
}

internal fun AutomationState.label(): String = when (this) {
    AutomationState.IDLE -> "Idle"
    AutomationState.WAITING_FOR_CHATGPT -> "Waiting for ChatGPT"
    AutomationState.CHATGPT_GENERATING -> "ChatGPT is generating"
    AutomationState.CHATGPT_FINISHED -> "Response finished"
    AutomationState.POST_RESPONSE_DELAY -> "Waiting before sending"
    AutomationState.FILLING_INPUT -> "Typing"
    AutomationState.SENDING -> "Sending"
    AutomationState.WAITING_FOR_NEXT_RESPONSE -> "Waiting for next response"
    AutomationState.PAUSED -> "Paused"
    AutomationState.STOPPED -> "Stopped"
    AutomationState.ERROR -> "Error"
}
