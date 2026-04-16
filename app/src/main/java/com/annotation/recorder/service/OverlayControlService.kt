package com.annotation.recorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.annotation.recorder.R
import com.annotation.recorder.accessibility.RecorderAccessibilityService
import com.annotation.recorder.capture.SessionObserver
import com.annotation.recorder.capture.SessionRecorder
import com.annotation.recorder.capture.SessionState
import com.annotation.recorder.domain.RecordingMode
import com.annotation.recorder.ui.MainActivity
import com.annotation.recorder.util.PermissionUtils
import com.annotation.recorder.util.RecordingModeStore
import com.annotation.recorder.capture.ScreenCaptureManager

class OverlayControlService : Service(), SessionObserver {
    private lateinit var windowManager: WindowManager
    private var overlayButton: Button? = null
    private var overlayAttached = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        attachOverlayIfNeeded()
        return START_STICKY
    }

    private fun attachOverlayIfNeeded() {
        if (overlayAttached) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val button = Button(this).apply {
            text = getString(R.string.overlay_button_start)
            alpha = 0.82f
            setOnClickListener { toggleSession() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 12
            y = 0
        }

        windowManager.addView(button, params)
        overlayButton = button
        SessionRecorder.addObserver(this)
        overlayAttached = true
    }

    override fun onDestroy() {
        if (overlayAttached) {
            SessionRecorder.removeObserver(this)
            overlayButton?.let { windowManager.removeView(it) }
        }
        overlayButton = null
        overlayAttached = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun toggleSession() {
        when (SessionRecorder.currentState()) {
            SessionState.IDLE -> {
                if (!PermissionUtils.isAccessibilityServiceEnabled(this)) {
                    Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_SHORT).show()
                    return
                }
                val mode = RecordingModeStore.get(this)
                if (mode == RecordingMode.MODE_2_VIDEO_SEMANTIC && !ScreenCaptureManager.hasPermission()) {
                    Toast.makeText(this, R.string.toast_need_mode2_permission_in_app, Toast.LENGTH_LONG).show()
                    return
                }
                if (SessionRecorder.startSession(this, mode)) {
                    SessionForegroundService.start(this)
                    RecorderAccessibilityService.requestInitialSnapshot()
                }
            }

            SessionState.RECORDING -> {
                SessionRecorder.stopSessionAsync(this) {
                    SessionForegroundService.stop(this)
                    if (Settings.canDrawOverlays(this)) {
                        start(this)
                    }
                }
            }

            SessionState.STOPPING -> Unit
        }
    }

    override fun onSessionStateChanged(state: SessionState, sessionId: String?, lastOutputPath: String?) {
        overlayButton?.text = when (state) {
            SessionState.IDLE -> getString(R.string.overlay_button_start)
            SessionState.RECORDING -> getString(R.string.overlay_button_stop)
            SessionState.STOPPING -> getString(R.string.overlay_button_stop)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            201,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "annotation_overlay_channel"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val intent = Intent(context, OverlayControlService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
