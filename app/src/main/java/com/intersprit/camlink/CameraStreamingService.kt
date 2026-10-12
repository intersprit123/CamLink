package com.intersprit.camlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class CameraStreamingService : LifecycleService() {
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var server: FrameServer? = null
    private var provider: ProcessCameraProvider? = null
    @Volatile private var activeCamera: Camera? = null
    @Volatile private var torchEnabled = false
    @Volatile private var jpegQuality = 85
    @Volatile private var resolutionIndex = 2
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_SWITCH) {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
            return START_STICKY
        }
        startForegroundCompat()
        if (server == null) {
            try {
                server = FrameServer(latestJpeg, ::handleWebControl).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            } catch (_: Exception) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startCamera()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val channelId = "camlink_camera_stream"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "CamLink camera sharing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("CamLink is sharing your camera")
            .setContentText("Camera streaming continues while you use other apps.")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCamera() {
        val sizes = arrayOf(
            android.util.Size(640, 480),
            android.util.Size(1280, 720),
            android.util.Size(1920, 1080),
            android.util.Size(2560, 1440),
            android.util.Size(3840, 2160)
        )
        val targetSize = sizes[resolutionIndex.coerceIn(0, sizes.lastIndex)]
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(targetSize)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    try { latestJpeg.set(imageToJpeg(image)) } catch (_: Exception) { } finally { image.close() }
                }
                cameraProvider.unbindAll()
                activeCamera = cameraProvider.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(lensFacing).build(), analysis)
                torchEnabled = false
            } catch (_: Exception) {
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun handleWebControl(command: String, value: String?): String {
        return when (command) {
            "switch" -> {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                ContextCompat.getMainExecutor(this).execute { startCamera() }
                "Switching camera"
            }
            "torch" -> {
                val camera = activeCamera ?: return "Camera is not ready"
                if (!camera.cameraInfo.hasFlashUnit()) return "Flashlight is not available on this camera"
                torchEnabled = !torchEnabled
                camera.cameraControl.enableTorch(torchEnabled)
                if (torchEnabled) "Flashlight on" else "Flashlight off"
            }
            "zoom" -> {
                val requested = value?.toFloatOrNull() ?: return "Invalid zoom value"
                val camera = activeCamera ?: return "Camera is not ready"
                val state = camera.cameraInfo.zoomState.value ?: return "Zoom is not available"
                val zoom = requested.coerceIn(state.minZoomRatio, state.maxZoomRatio)
                camera.cameraControl.setZoomRatio(zoom)
                "Zoom set to %.1fx".format(zoom)
            }
            "quality" -> {
                val requested = value?.toIntOrNull() ?: return "Invalid quality setting"
                resolutionIndex = requested.coerceIn(0, 4)
                ContextCompat.getMainExecutor(this).execute { startCamera() }
                val labels = arrayOf("480p", "720p", "1080p", "1440p / 2K", "2160p / 4K")
                "Requested ${labels[resolutionIndex]}. Actual resolution depends on camera support."
            }
            "jpeg" -> {
                val requested = value?.toIntOrNull() ?: return "Invalid JPEG quality"
                jpegQuality = requested.coerceIn(50, 95)
                "JPEG quality set to $jpegQuality%"
            }
            else -> "Unknown command"
        }
    }

    private fun imageToJpeg(image: androidx.camera.core.ImageProxy): ByteArray {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0
        val yp = image.planes[0]
        for (row in 0 until height) for (col in 0 until width) nv21[pos++] = y.get(row * yp.rowStride + col * yp.pixelStride)
        val up = image.planes[1]
        val vp = image.planes[2]
        for (row in 0 until height / 2) for (col in 0 until width / 2) {
            nv21[pos++] = v.get(row * vp.rowStride + col * vp.pixelStride)
            nv21[pos++] = u.get(row * up.rowStride + col * up.pixelStride)
        }
        val output = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), jpegQuality, output)
        val jpeg = output.toByteArray()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return jpeg

        // ImageAnalysis buffers can be landscape even when the phone is held vertically.
        // Rotate each frame so viewers match the phone's current orientation.
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val rotatedOutput = ByteArrayOutputStream()
        rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, jpegQuality, rotatedOutput)
        if (rotated !== bitmap) bitmap.recycle()
        rotated.recycle()
        return rotatedOutput.toByteArray()
    }

    override fun onDestroy() {
        try { provider?.unbindAll() } catch (_: Exception) { }
        activeCamera = null
        try { server?.stop() } catch (_: Exception) { }
        cameraExecutor.shutdown()
        latestJpeg.set(null)
        super.onDestroy()
    }

    companion object {
        const val ACTION_SWITCH = "com.intersprit.camlink.SWITCH_CAMERA"
        private const val NOTIFICATION_ID = 812
    }
}
