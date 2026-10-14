package com.intersprit.camlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.featuregroup.GroupableFeature.Companion.FPS_60
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleService
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CameraStreamingService : LifecycleService() {
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    // Reuse the large intermediate buffers; only immutable JPEG byte arrays are published to readers.
    private var nv21Buffer = ByteArray(0)
    private var rotatedNv21Buffer = ByteArray(0)
    private val jpegOutput = ByteArrayOutputStream()
    @Volatile private var captureWidth = 0
    @Volatile private var captureHeight = 0
    private var server: FrameServer? = null
    private var provider: ProcessCameraProvider? = null
    @Volatile private var activeCamera: Camera? = null
    @Volatile private var torchEnabled = false
    @Volatile private var jpegQuality = 75
    // Favor smoothness and lower CPU/network cost by default; higher capture modes remain selectable.
    @Volatile private var resolutionIndex = 1
    @Volatile private var lensFacing = CameraSelector.LENS_FACING_BACK

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_CONTROL) {
            val command = intent.getStringExtra(EXTRA_CONTROL_COMMAND).orEmpty()
            val value = intent.getStringExtra(EXTRA_CONTROL_VALUE)

            // A control request can restart a service that Android previously reclaimed.
            // Recover the foreground sharing service instead of replying "not active" and
            // dropping the first settings action.
            if (server == null) {
                try {
                    startForegroundCompat()
                    if (!ensureServerStarted()) {
                        sendControlResult("Could not restart camera sharing. Tap Stop sharing, then Start camera sharing again.")
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    startCamera()
                } catch (e: Exception) {
                    sendControlResult("Could not restart camera sharing: ${e.message ?: "unknown error"}")
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            applyControlWhenReady(command, value, 0)
            return START_STICKY
        }
        if (intent?.action == ACTION_SWITCH) {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
            return START_STICKY
        }
        startForegroundCompat()
        if (!ensureServerStarted()) {
            sendServiceStatus("ERROR", "Could not start the camera-sharing server on port 8080.")
            stopSelf()
            return START_NOT_STICKY
        }
        sendServiceStatus("STARTING", "Server started. Opening camera…")
        startCamera()
        return START_STICKY
    }

    private fun ensureServerStarted(): Boolean {
        if (server != null) return true
        return try {
            server = FrameServer(latestJpeg, ::handleWebControl).also {
                it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not start HTTP frame server on port 8080", e)
            sendServiceStatus("ERROR", "HTTP server failed to start: ${e.message ?: e.javaClass.simpleName}")
            server = null
            false
        }
    }

    private fun sendServiceStatus(state: String, message: String) {
        sendBroadcast(
            Intent(ACTION_SERVICE_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_SERVICE_STATE, state)
                .putExtra(EXTRA_SERVICE_MESSAGE, message)
        )
    }

    private fun sendControlResult(message: String) {
        sendBroadcast(
            Intent(ACTION_CONTROL_RESULT)
                .setPackage(packageName)
                .putExtra(EXTRA_CONTROL_RESULT_TEXT, message)
        )
    }

    private fun applyControlWhenReady(command: String, value: String?, attempt: Int) {
        if (server == null) {
            sendControlResult("Camera sharing stopped before the setting could be applied.")
            return
        }

        // These settings update service state and do not require an opened CameraX camera.
        if (command == "jpeg" || command == "quality" || activeCamera != null) {
            sendControlResult(handleWebControl(command, value))
            return
        }

        if (attempt >= 25) {
            sendControlResult("Camera is still starting. Wait for the live preview, then apply the setting again.")
            return
        }

        mainHandler.postDelayed({ applyControlWhenReady(command, value, attempt + 1) }, 200)
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

    @OptIn(androidx.camera.core.ExperimentalSessionConfig::class)
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
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
                    .setTargetResolution(targetSize)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    val startedNs = System.nanoTime()
                    try {
                        val jpeg = imageToJpeg(image)
                        latestJpeg.set(jpeg)
                        updateStreamMetrics(jpeg.size, System.nanoTime() - startedNs)
                    } catch (_: Exception) {
                    } finally {
                        image.close()
                    }
                }
                cameraProvider.unbindAll()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                // Prefer 60 FPS where this camera/resolution combination supports it.
                // CameraX falls back to a supported configuration instead of forcing 60 FPS.
                // Try the preferred 60 FPS session first, but do not let an optional
                // feature-group configuration prevent camera sharing on older or unusual devices.
                activeCamera = try {
                    val sessionConfig = SessionConfig.Builder(analysis)
                        .setPreferredFeatureGroup(FPS_60)
                        .build()
                    cameraProvider.bindToLifecycle(this, selector, sessionConfig)
                } catch (preferredError: Exception) {
                    Log.w(TAG, "Preferred 60 FPS session failed; retrying with standard CameraX binding", preferredError)
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, selector, analysis)
                }
                torchEnabled = false
                sendServiceStatus("LIVE", "Camera opened. Sharing at ${targetSize.width}x${targetSize.height}.")
                Log.i(TAG, "Camera sharing is live at ${targetSize.width}x${targetSize.height}")
            } catch (e: Exception) {
                activeCamera = null
                Log.e(TAG, "CameraX failed to bind the camera", e)
                sendServiceStatus("ERROR", "Camera failed to start: ${e.message ?: e.javaClass.simpleName}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun handleWebControl(command: String, value: String?): String {
        return try {
            when (command) {
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
                "focus" -> {
                    val camera = activeCamera ?: return "Camera is not ready"
                    val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    if (!camera.cameraInfo.isFocusMeteringSupported(action)) {
                        return "Autofocus metering is not supported by this camera"
                    }
                    camera.cameraControl.startFocusAndMetering(action)
                    "Center autofocus requested"
                }
                "exposure" -> {
                    val requested = value?.toIntOrNull() ?: return "Invalid exposure compensation"
                    val camera = activeCamera ?: return "Camera is not ready"
                    val exposure = camera.cameraInfo.exposureState
                    if (!exposure.isExposureCompensationSupported) return "Exposure compensation is not supported by this camera"
                    val index = requested.coerceIn(
                        exposure.exposureCompensationRange.lower,
                        exposure.exposureCompensationRange.upper
                    )
                    camera.cameraControl.setExposureCompensationIndex(index)
                    "Exposure compensation set to $index steps"
                }
                "whitebalance" -> {
                    val camera = activeCamera ?: return "Camera is not ready"
                    val requested = value?.lowercase()?.trim() ?: "auto"
                    val modes = mapOf(
                        "auto" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
                        "daylight" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                        "cloudy" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                        "incandescent" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
                        "fluorescent" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                    )
                    val mode = modes[requested] ?: return "Unknown white-balance mode"
                    val supported = Camera2CameraInfo.from(camera.cameraInfo)
                        .getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                    if (supported == null || !supported.contains(mode)) {
                        return "White balance mode '$requested' is not supported by this camera"
                    }
                    val options = CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, mode)
                        .build()
                    Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options)
                    "White balance set to $requested"
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
        } catch (e: Exception) {
            "Control failed: ${e.message ?: "camera may not support this setting"}"
        }
    }

    private fun imageToJpeg(image: androidx.camera.core.ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val requiredSize = width * height * 3 / 2
        if (nv21Buffer.size != requiredSize) nv21Buffer = ByteArray(requiredSize)
        val nv21 = nv21Buffer
        var destinationOffset = 0

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer.duplicate()
        if (yPlane.pixelStride == 1 && yPlane.rowStride >= width) {
            // Bulk-copy each luma row instead of doing a bounds-checked get for every pixel.
            for (row in 0 until height) {
                yBuffer.position(row * yPlane.rowStride)
                yBuffer.get(nv21, destinationOffset, width)
                destinationOffset += width
            }
        } else {
            for (row in 0 until height) {
                for (col in 0 until width) {
                    nv21[destinationOffset++] = yBuffer.get(row * yPlane.rowStride + col * yPlane.pixelStride)
                }
            }
        }

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val u = uPlane.buffer.duplicate()
        val v = vPlane.buffer.duplicate()
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        // CameraX 1.5+ NV21 output provides VU-interleaved chroma on plane 2.
        // Copy whole chroma rows when available; retain a stride-aware fallback for devices
        // which expose an unexpected plane layout.
        val contiguousVu = vPlane.pixelStride == 2 &&
            vPlane.rowStride >= width &&
            (chromaHeight == 0 || ((chromaHeight - 1) * vPlane.rowStride + width) <= v.limit())

        if (contiguousVu) {
            for (row in 0 until chromaHeight) {
                v.position(row * vPlane.rowStride)
                v.get(nv21, destinationOffset, width)
                destinationOffset += width
            }
        } else {
            for (row in 0 until chromaHeight) {
                for (col in 0 until chromaWidth) {
                    nv21[destinationOffset++] = v.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                    nv21[destinationOffset++] = u.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                }
            }
        }

        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val encodedWidth = if (rotation == 90 || rotation == 270) height else width
        val encodedHeight = if (rotation == 90 || rotation == 270) width else height
        val encodedData = if (rotation == 0) nv21 else rotateNv21(nv21, width, height, rotation)

        // Rotate the YUV buffer directly; never decode each frame into a Bitmap.
        jpegOutput.reset()
        YuvImage(encodedData, ImageFormat.NV21, encodedWidth, encodedHeight, null)
            .compressToJpeg(Rect(0, 0, encodedWidth, encodedHeight), jpegQuality, jpegOutput)
        captureWidth = encodedWidth
        captureHeight = encodedHeight
        return jpegOutput.toByteArray()
    }

    private fun rotateNv21(source: ByteArray, width: Int, height: Int, rotation: Int): ByteArray {
        if (rotatedNv21Buffer.size != source.size) rotatedNv21Buffer = ByteArray(source.size)
        val destination = rotatedNv21Buffer
        val outputWidth = if (rotation == 90 || rotation == 270) height else width
        val outputHeight = if (rotation == 90 || rotation == 270) width else height

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx: Int
                val dy: Int
                when (rotation) {
                    90 -> { dx = height - 1 - y; dy = x }
                    180 -> { dx = width - 1 - x; dy = height - 1 - y }
                    270 -> { dx = y; dy = width - 1 - x }
                    else -> { dx = x; dy = y }
                }
                destination[dy * outputWidth + dx] = source[y * width + x]
            }
        }

        val sourceChromaWidth = width / 2
        val sourceChromaHeight = height / 2
        val destinationChromaWidth = outputWidth / 2
        val sourceChromaOffset = width * height
        val destinationChromaOffset = outputWidth * outputHeight
        for (y in 0 until sourceChromaHeight) {
            for (x in 0 until sourceChromaWidth) {
                val dx: Int
                val dy: Int
                when (rotation) {
                    90 -> { dx = sourceChromaHeight - 1 - y; dy = x }
                    180 -> { dx = sourceChromaWidth - 1 - x; dy = sourceChromaHeight - 1 - y }
                    270 -> { dx = y; dy = sourceChromaWidth - 1 - x }
                    else -> { dx = x; dy = y }
                }
                val from = sourceChromaOffset + (y * sourceChromaWidth + x) * 2
                val to = destinationChromaOffset + (dy * destinationChromaWidth + dx) * 2
                destination[to] = source[from]
                destination[to + 1] = source[from + 1]
            }
        }
        return destination
    }

    private var statsWindowStartedNs = System.nanoTime()
    private var statsWindowFrames = 0L
    private var statsWindowBytes = 0L

    private fun updateStreamMetrics(jpegBytes: Int, encodeNanos: Long) {
        streamEncodeMs = encodeNanos / 1_000_000.0
        streamFrameWidth = captureWidth
        streamFrameHeight = captureHeight
        streamJpegQuality = jpegQuality
        statsWindowFrames++
        statsWindowBytes += jpegBytes
        val now = System.nanoTime()
        val elapsedNs = now - statsWindowStartedNs
        if (elapsedNs >= 1_000_000_000L) {
            streamFps = statsWindowFrames * 1_000_000_000.0 / elapsedNs
            streamBitrateMbps = statsWindowBytes * 8_000.0 / elapsedNs
            statsWindowFrames = 0L
            statsWindowBytes = 0L
            statsWindowStartedNs = now
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try { provider?.unbindAll() } catch (_: Exception) { }
        activeCamera = null
        try { server?.stop() } catch (_: Exception) { }
        cameraExecutor.shutdown()
        latestJpeg.set(null)
        streamFps = 0.0
        streamEncodeMs = 0.0
        streamBitrateMbps = 0.0
        streamFrameWidth = 0
        streamFrameHeight = 0
        super.onDestroy()
    }

    companion object {
        const val ACTION_SWITCH = "com.intersprit.camlink.SWITCH_CAMERA"
        const val ACTION_CONTROL = "com.intersprit.camlink.CONTROL_CAMERA"
        const val ACTION_CONTROL_RESULT = "com.intersprit.camlink.CONTROL_CAMERA_RESULT"
        const val ACTION_SERVICE_STATUS = "com.intersprit.camlink.CAMERA_SERVICE_STATUS"
        const val EXTRA_SERVICE_STATE = "com.intersprit.camlink.EXTRA_SERVICE_STATE"
        const val EXTRA_SERVICE_MESSAGE = "com.intersprit.camlink.EXTRA_SERVICE_MESSAGE"
        private const val TAG = "CamLinkCameraService"
        const val EXTRA_CONTROL_COMMAND = "com.intersprit.camlink.EXTRA_CONTROL_COMMAND"
        const val EXTRA_CONTROL_VALUE = "com.intersprit.camlink.EXTRA_CONTROL_VALUE"
        const val EXTRA_CONTROL_RESULT_TEXT = "com.intersprit.camlink.EXTRA_CONTROL_RESULT_TEXT"
        @Volatile var streamFps: Double = 0.0
        @Volatile var streamEncodeMs: Double = 0.0
        @Volatile var streamBitrateMbps: Double = 0.0
        @Volatile var streamFrameWidth: Int = 0
        @Volatile var streamFrameHeight: Int = 0
        @Volatile var streamJpegQuality: Int = 75
        fun statsJsonForViewer(): String = String.format(
            java.util.Locale.US,
            "{\"fps\":%.1f,\"encodeMs\":%.2f,\"bitrateMbps\":%.2f,\"width\":%d,\"height\":%d,\"jpegQuality\":%d}",
            streamFps, streamEncodeMs, streamBitrateMbps,
            streamFrameWidth, streamFrameHeight, streamJpegQuality
        )
        private const val NOTIFICATION_ID = 812
    }
}
