package com.annotation.recorder.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.annotation.recorder.capture.SessionRecorder
import com.annotation.recorder.capture.SessionState
import com.annotation.recorder.domain.CoordinateEstimate
import com.annotation.recorder.domain.EventSnapshot
import com.annotation.recorder.domain.TreeNodeSnapshot
import com.annotation.recorder.domain.TreeSnapshot
import java.util.concurrent.atomic.AtomicLong

class RecorderAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || SessionRecorder.currentState() != SessionState.RECORDING) return
        try {
            captureAndRecord(event = event, forcedType = null)
        } catch (t: Throwable) {
            Log.e(TAG, "onAccessibilityEvent failed", t)
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceRef = this
    }

    override fun onDestroy() {
        if (serviceRef === this) {
            serviceRef = null
        }
        super.onDestroy()
    }

    private fun captureAndRecord(event: AccessibilityEvent?, forcedType: String?) {
        if (SessionRecorder.currentState() != SessionState.RECORDING) return

        val eventId = "evt-${eventCounter.incrementAndGet()}"
        val snapshotId = "snap-${snapshotCounter.incrementAndGet()}"
        val ts = SystemClock.elapsedRealtimeNanos()

        val sourceRect = Rect()
        var sourceViewId: String? = null
        event?.source?.let { sourceNode ->
            sourceNode.getBoundsInScreen(sourceRect)
            sourceViewId = sourceNode.viewIdResourceName
            sourceNode.recycle()
        }
        val bounds = if (sourceRect.isEmpty) null else sourceRect.flattenToString()

        val rootNode = rootInActiveWindow ?: windows.firstOrNull()?.root
        val rootWindowId = rootNode?.windowId ?: -1
        val rootPackageName = rootNode?.packageName?.toString()
        val treeRoot = rootNode?.let { toNodeSnapshot(it) }

        val coord = estimateCoordinate(event, sourceRect)
        val eventSnapshot = EventSnapshot(
            id = eventId,
            tsElapsedNanos = ts,
            type = forcedType ?: eventTypeName(event?.eventType ?: -1),
            packageName = event?.packageName?.toString() ?: rootPackageName,
            className = event?.className?.toString(),
            textSummary = event?.text?.joinToString(separator = "|") { it.toString() },
            sourceViewId = sourceViewId,
            bounds = bounds,
            coord = coord,
            snapshotId = snapshotId
        )

        val treeSnapshot = TreeSnapshot(
            id = snapshotId,
            tsElapsedNanos = ts,
            rootWindowId = rootWindowId,
            root = treeRoot
        )

        SessionRecorder.record(eventSnapshot, treeSnapshot)
    }

    private fun toNodeSnapshot(node: AccessibilityNodeInfo): TreeNodeSnapshot {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val children = mutableListOf<TreeNodeSnapshot>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            children.add(toNodeSnapshot(child))
        }

        val snapshot = TreeNodeSnapshot(
            nodeId = "n-${nodeCounter.incrementAndGet()}",
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDesc = node.contentDescription?.toString(),
            viewIdRes = node.viewIdResourceName,
            bounds = rect.flattenToString(),
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            focused = node.isFocused,
            children = children
        )
        node.recycle()
        return snapshot
    }

    private fun estimateCoordinate(event: AccessibilityEvent?, sourceRect: Rect): CoordinateEstimate {
        val centerX = if (sourceRect.isEmpty) -1 else sourceRect.centerX()
        val centerY = if (sourceRect.isEmpty) -1 else sourceRect.centerY()

        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && centerX >= 0 && centerY >= 0) {
            val endX = centerX - event.scrollDeltaX
            val endY = centerY - event.scrollDeltaY
            return CoordinateEstimate(centerX, centerY, endX, endY)
        }

        return CoordinateEstimate(centerX, centerY, centerX, centerY)
    }

    private fun eventTypeName(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "TYPE_VIEW_LONG_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TYPE_VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            else -> "TYPE_$eventType"
        }
    }

    companion object {
        private const val TAG = "RecorderA11yService"
        private val eventCounter = AtomicLong(0L)
        private val snapshotCounter = AtomicLong(0L)
        private val nodeCounter = AtomicLong(0L)
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var serviceRef: RecorderAccessibilityService? = null

        fun requestInitialSnapshot() {
            tryRequestInitialSnapshot(0)
        }

        private fun tryRequestInitialSnapshot(attempt: Int) {
            val service = serviceRef
            if (service != null && SessionRecorder.currentState() == SessionState.RECORDING) {
                try {
                    service.captureAndRecord(event = null, forcedType = "SESSION_START")
                } catch (t: Throwable) {
                    Log.e(TAG, "initial snapshot failed", t)
                }
                return
            }
            if (attempt < 5) {
                mainHandler.postDelayed({ tryRequestInitialSnapshot(attempt + 1) }, 200)
            }
        }
    }
}
