package com.annotation.recorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.annotation.recorder.R
import com.annotation.recorder.capture.ScreenCaptureManager
import com.annotation.recorder.capture.SessionRecorder
import com.annotation.recorder.ui.MainActivity

class SessionForegroundService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SessionRecorder.stopSessionAsync(this) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                createNotificationChannel()
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val type = if (ScreenCaptureManager.hasPermission()) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    }
                    try {
                        startForeground(NOTIFICATION_ID, notification, type)
                    } catch (_: SecurityException) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                        )
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                return START_STICKY
            }

            else -> return START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            101,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SessionForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text_recording))
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "annotation_recorder_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.annotation.recorder.action.START_FOREGROUND"
        private const val ACTION_STOP = "com.annotation.recorder.action.STOP_FOREGROUND"

        fun start(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }
    }
}
