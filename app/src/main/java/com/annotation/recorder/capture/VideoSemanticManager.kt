package com.annotation.recorder.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object VideoSemanticManager {
    private const val TAG = "VideoSemanticManager"

    private val semanticExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    private var mediaProjection: MediaProjection? = null
    @Volatile
    private var virtualDisplay: VirtualDisplay? = null
    @Volatile
    private var mediaRecorder: MediaRecorder? = null
    @Volatile
    private var recordingVideoPath: String? = null

    fun startVideoRecording(context: Context, sessionId: String): String? {
        if (!ScreenCaptureManager.hasPermission()) return null
        return try {
            val appContext = context.applicationContext
            val projectionManager =
                appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val permissionData = ScreenCaptureManager.permissionData() ?: return null
            val projection = projectionManager.getMediaProjection(
                ScreenCaptureManager.permissionResultCode(),
                permissionData
            ) ?: return null

            val metrics = appContext.resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            val baseDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            val modeDir = File(baseDir, "sessions/$sessionId/mode_2")
            modeDir.mkdirs()
            val videoFile = File(modeDir, "recording.mp4")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoFile.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(24)
                setVideoEncodingBitRate(4_000_000)
                prepare()
            }

            val display = projection.createVirtualDisplay(
                "annotation_video",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null,
                null
            )

            recorder.start()
            mediaProjection = projection
            mediaRecorder = recorder
            virtualDisplay = display
            recordingVideoPath = videoFile.absolutePath
            videoFile.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "startVideoRecording failed", t)
            stopVideoRecording()
            null
        }
    }

    fun stopVideoRecording(): String? {
        val path = recordingVideoPath
        try {
            mediaRecorder?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "recorder stop warning", t)
        }
        try {
            mediaRecorder?.reset()
        } catch (_: Throwable) {
        }
        mediaRecorder?.release()
        mediaRecorder = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        recordingVideoPath = null
        return path
    }

    fun analyzeSnapshotAsync(context: Context, sessionId: String, snapshotId: String, imagePath: String): String? {
        semanticExecutor.execute {
            val semanticPath = try {
                val bitmap = BitmapFactory.decodeFile(imagePath) ?: return@execute
                val image = InputImage.fromBitmap(bitmap, 0)
                val result = runTextRecognitionBlocking(image) ?: return@execute

                val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
                val semanticDir = File(baseDir, "sessions/$sessionId/mode_2/semantics")
                semanticDir.mkdirs()
                val output = File(semanticDir, "$snapshotId.json")

                FileWriter(output, false).use { writer ->
                    writer.append("{\n")
                    writer.append("  \"snapshotId\": \"").append(snapshotId).append("\",\n")
                    writer.append("  \"imagePath\": \"").append(imagePath.replace("\\", "\\\\")).append("\",\n")
                    writer.append("  \"fullText\": \"")
                        .append(escapeJson(result.text))
                        .append("\",\n")
                    writer.append("  \"blocks\": [\n")
                    result.textBlocks.forEachIndexed { index, block ->
                        val b = block.boundingBox ?: Rect()
                        writer.append("    {\"text\": \"")
                            .append(escapeJson(block.text))
                            .append("\", \"bounds\": \"")
                            .append("${b.left},${b.top},${b.right},${b.bottom}")
                            .append("\"}")
                        if (index != result.textBlocks.lastIndex) writer.append(",")
                        writer.append("\n")
                    }
                    writer.append("  ]\n")
                    writer.append("}\n")
                }
                output.absolutePath
            } catch (t: Throwable) {
                Log.e(TAG, "analyzeSnapshotAsync failed", t)
                null
            }
            if (semanticPath != null) {
                SessionRecorder.attachSnapshotSemantic(snapshotId, semanticPath)
            }
        }
        return null
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    private fun runTextRecognitionBlocking(image: InputImage): Text? {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<Text?>(null)
        val errorRef = AtomicReference<Throwable?>(null)

        recognizer.process(image)
            .addOnSuccessListener { text ->
                resultRef.set(text)
                latch.countDown()
            }
            .addOnFailureListener { err ->
                errorRef.set(err)
                latch.countDown()
            }

        val completed = latch.await(5, TimeUnit.SECONDS)
        if (!completed) {
            Log.w(TAG, "OCR timeout after 5s")
            return null
        }
        errorRef.get()?.let { throw it }
        return resultRef.get()
    }
}
