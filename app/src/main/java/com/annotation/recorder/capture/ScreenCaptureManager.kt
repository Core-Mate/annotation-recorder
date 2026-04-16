package com.annotation.recorder.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object ScreenCaptureManager {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    private var permissionResultCode: Int? = null
    private var permissionData: Intent? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var width = 0
    private var height = 0
    private var densityDpi = 0

    fun updatePermission(resultCode: Int, data: Intent?) {
        permissionResultCode = resultCode
        permissionData = data
        releaseProjection()
    }

    fun hasPermission(): Boolean = permissionResultCode != null && permissionData != null

    fun permissionResultCode(): Int = permissionResultCode ?: 0

    fun permissionData(): Intent? = permissionData

    fun captureSnapshot(
        context: Context,
        sessionId: String,
        snapshotId: String,
        delayMs: Long,
        onCaptured: (String) -> Unit
    ) {
        if (!hasPermission()) return
        val appContext = context.applicationContext
        executor.schedule({
            val path = doCapture(appContext, sessionId, snapshotId) ?: return@schedule
            onCaptured(path)
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun doCapture(context: Context, sessionId: String, snapshotId: String): String? {
        if (!ensureProjection(context)) return null

        var image = imageReader?.acquireLatestImage()
        var retry = 0
        while (image == null && retry < 4) {
            SystemClock.sleep(80)
            image = imageReader?.acquireLatestImage()
            retry++
        }
        image ?: return null

        image.use { frame ->
            val plane = frame.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + (rowPadding / pixelStride)

            val bitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            bitmap.recycle()

            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val imageDir = File(baseDir, "sessions/$sessionId/images")
            if (!imageDir.exists()) imageDir.mkdirs()
            val output = File(imageDir, "$snapshotId.png")
            FileOutputStream(output).use { fos ->
                cropped.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            cropped.recycle()
            return output.absolutePath
        }
    }

    private fun ensureProjection(context: Context): Boolean {
        if (mediaProjection != null && virtualDisplay != null && imageReader != null) return true

        val resultCode = permissionResultCode ?: return false
        val data = permissionData ?: return false
        val projectionManager =
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data) ?: return false

        val metrics = context.resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels
        densityDpi = metrics.densityDpi

        if (width <= 0 || height <= 0 || densityDpi <= 0) return false

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        val display = projection.createVirtualDisplay(
            "annotation_capture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )

        mediaProjection = projection
        imageReader = reader
        virtualDisplay = display
        return true
    }

    private fun releaseProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }
}
