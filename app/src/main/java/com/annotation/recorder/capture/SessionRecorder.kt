package com.annotation.recorder.capture

import android.content.Context
import android.os.Build
import com.annotation.recorder.domain.EventSnapshot
import com.annotation.recorder.domain.RecordingMode
import com.annotation.recorder.domain.SessionMeta
import com.annotation.recorder.domain.TreeSnapshot
import com.annotation.recorder.storage.SessionXmlWriter
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

object SessionRecorder {
    private val observers = CopyOnWriteArraySet<SessionObserver>()
    private val xmlWriter = SessionXmlWriter()
    private val lock = Any()
    private val ioExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var state: SessionState = SessionState.IDLE
    private var sessionId: String? = null
    private var sessionMeta: SessionMeta? = null
    private var lastOutputPath: String? = null
    private var currentMode: RecordingMode = RecordingMode.MODE_1_TREE
    private val snapshotImagePaths = linkedMapOf<String, String>()
    private val snapshotSemanticPaths = linkedMapOf<String, String>()
    private val events = mutableListOf<EventSnapshot>()
    private val snapshots = mutableListOf<TreeSnapshot>()
    private var appContext: Context? = null

    fun addObserver(observer: SessionObserver) {
        observers.add(observer)
        observer.onSessionStateChanged(state, sessionId, lastOutputPath)
    }

    fun removeObserver(observer: SessionObserver) {
        observers.remove(observer)
    }

    fun currentState(): SessionState = state

    fun currentSessionId(): String? = sessionId

    fun lastOutputPath(): String? = lastOutputPath

    fun updateLastOutputPath(path: String) {
        synchronized(lock) {
            lastOutputPath = path
        }
        notifyObservers()
    }

    fun startSession(context: Context, mode: RecordingMode): Boolean {
        synchronized(lock) {
            if (state == SessionState.RECORDING) return false
            appContext = context.applicationContext
            currentMode = mode
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val id = "sess-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
            sessionId = id
            sessionMeta = SessionMeta(
                sessionId = id,
                appVersion = pkgInfo.versionName ?: "unknown",
                deviceModel = "${Build.MANUFACTURER}-${Build.MODEL}",
                sdkInt = Build.VERSION.SDK_INT,
                recordingMode = mode.value,
                pipelineVersion = "2",
                semanticStatus = if (mode == RecordingMode.MODE_2_VIDEO_SEMANTIC) "enabled" else "disabled",
                startedAtEpochMs = System.currentTimeMillis()
            )
            events.clear()
            snapshots.clear()
            snapshotImagePaths.clear()
            snapshotSemanticPaths.clear()
            lastOutputPath = null
            state = SessionState.RECORDING
        }
        if (mode == RecordingMode.MODE_2_VIDEO_SEMANTIC) {
            val ctx = context.applicationContext
            val sid = currentSessionId() ?: ""
            val videoPath = VideoSemanticManager.startVideoRecording(ctx, sid)
            synchronized(lock) {
                sessionMeta?.videoPath = videoPath
                if (videoPath == null) {
                    sessionMeta?.semanticStatus = "enabled_without_video"
                }
            }
        }
        notifyObservers()
        return true
    }

    fun stopSessionAsync(context: Context, onFinished: ((File?) -> Unit)? = null): Boolean {
        val contextForWrite = context.applicationContext
        val stopPayload: StopPayload
        synchronized(lock) {
            if (state != SessionState.RECORDING) return false
            state = SessionState.STOPPING
            val id = sessionId ?: return false
            val meta = sessionMeta ?: return false
            val endedAt = System.currentTimeMillis()
            stopPayload = StopPayload(
                sessionId = id,
                meta = meta.copy(endedAtEpochMs = endedAt),
                events = events.toList(),
                snapshots = snapshots.toList(),
                snapshotImagePaths = snapshotImagePaths.toMap(),
                snapshotSemanticPaths = snapshotSemanticPaths.toMap()
            )
        }
        notifyObservers()

        ioExecutor.execute {
            var outputFile: File? = null
            try {
                if (currentMode == RecordingMode.MODE_2_VIDEO_SEMANTIC) {
                    val videoPath = VideoSemanticManager.stopVideoRecording()
                    synchronized(lock) {
                        if (!videoPath.isNullOrBlank()) {
                            sessionMeta?.videoPath = videoPath
                            stopPayload.meta.videoPath = videoPath
                        }
                    }
                }
                val baseDir = contextForWrite.getExternalFilesDir(null) ?: contextForWrite.filesDir
                val sessionsDir = File(baseDir, "sessions")
                outputFile = File(sessionsDir, "${stopPayload.sessionId}.xml")
                xmlWriter.write(
                    outputFile,
                    stopPayload.meta,
                    stopPayload.events,
                    stopPayload.snapshots,
                    stopPayload.snapshotImagePaths,
                    stopPayload.snapshotSemanticPaths
                )
                synchronized(lock) {
                    lastOutputPath = outputFile.absolutePath
                }
            } catch (t: Throwable) {
                synchronized(lock) {
                    lastOutputPath = "write_failed:${t.message}"
                }
            } finally {
                synchronized(lock) {
                    state = SessionState.IDLE
                    sessionId = null
                    sessionMeta = null
                    events.clear()
                    snapshots.clear()
                    snapshotImagePaths.clear()
                    snapshotSemanticPaths.clear()
                    appContext = null
                }
                notifyObservers()
                onFinished?.invoke(outputFile)
            }
        }
        return true
    }

    fun record(event: EventSnapshot, snapshot: TreeSnapshot) {
        var sessionIdForCapture: String? = null
        var contextForCapture: Context? = null
        var delayMs = 2000L
        synchronized(lock) {
            if (state != SessionState.RECORDING) return
            events.add(event)
            snapshots.add(snapshot)
            sessionIdForCapture = sessionId
            contextForCapture = appContext
            delayMs = if (event.type == "SESSION_START") 0L else 2000L
        }
        val captureSessionId = sessionIdForCapture
        val captureContext = contextForCapture
        if (captureSessionId != null && captureContext != null) {
            ScreenCaptureManager.captureSnapshot(
                context = captureContext,
                sessionId = captureSessionId,
                snapshotId = snapshot.id,
                delayMs = delayMs
            ) { imagePath ->
                attachSnapshotImage(snapshot.id, imagePath)
                if (currentMode == RecordingMode.MODE_2_VIDEO_SEMANTIC) {
                    VideoSemanticManager.analyzeSnapshotAsync(
                        context = captureContext,
                        sessionId = captureSessionId,
                        snapshotId = snapshot.id,
                        imagePath = imagePath
                    )
                }
            }
        }
    }

    private fun attachSnapshotImage(snapshotId: String, imagePath: String) {
        synchronized(lock) {
            if (state == SessionState.RECORDING || state == SessionState.STOPPING) {
                snapshotImagePaths[snapshotId] = imagePath
            }
        }
    }

    fun attachSnapshotSemantic(snapshotId: String, semanticPath: String) {
        synchronized(lock) {
            if (state == SessionState.RECORDING || state == SessionState.STOPPING) {
                snapshotSemanticPaths[snapshotId] = semanticPath
            }
        }
    }

    private fun notifyObservers() {
        val stateSnapshot = state
        val sessionSnapshot = sessionId
        val outputSnapshot = lastOutputPath
        observers.forEach { observer ->
            observer.onSessionStateChanged(stateSnapshot, sessionSnapshot, outputSnapshot)
        }
    }

    private data class StopPayload(
        val sessionId: String,
        val meta: SessionMeta,
        val events: List<EventSnapshot>,
        val snapshots: List<TreeSnapshot>,
        val snapshotImagePaths: Map<String, String>,
        val snapshotSemanticPaths: Map<String, String>
    )
}
