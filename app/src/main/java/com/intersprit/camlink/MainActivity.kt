package com.intersprit.camlink

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private var previewView: PreviewView? = null
    private var hostPreviewWebView: WebView? = null
    private var webView: WebView? = null
    private var server: FrameServer? = null
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var hosting = false
    private var hostStatusView: TextView? = null
    private var hostMetricsView: TextView? = null
    private var hostControlStatusView: TextView? = null
    private var hostResolutionSpinner: Spinner? = null
    private var hostJpegSeekBar: SeekBar? = null
    private var hostJpegLabel: TextView? = null
    private var hostZoomSeekBar: SeekBar? = null
    private var hostZoomLabel: TextView? = null
    private var hostExposureSeekBar: SeekBar? = null
    private var hostExposureLabel: TextView? = null
    private var hostWhiteBalanceSpinner: Spinner? = null
    private val metricsHandler = Handler(Looper.getMainLooper())
    private var metricsRunnable: Runnable? = null
    private var controlReceiverRegistered = false
    private val controlResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CameraStreamingService.ACTION_SERVICE_STATUS -> {
                    val state = intent.getStringExtra(CameraStreamingService.EXTRA_SERVICE_STATE).orEmpty()
                    val message = intent.getStringExtra(CameraStreamingService.EXTRA_SERVICE_MESSAGE) ?: "Camera service status changed."
                    hostStatusView?.text = when (state) {
                        "LIVE" -> "LIVE • http://${localIpAddress()}:8080\n$message"
                        "STARTING" -> "STARTING • $message"
                        else -> message
                    }
                    if (state == "ERROR") {
                        hosting = false
                        stopHostMetrics()
                        hostControlStatusView?.text = "Sharing failed. Fix the reported error, then tap Start again."
                    }
                }
                CameraStreamingService.ACTION_CONTROL_RESULT -> {
                    hostControlStatusView?.text = intent.getStringExtra(CameraStreamingService.EXTRA_CONTROL_RESULT_TEXT)
                        ?: "Control request completed"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun baseLayout(title: String, subtitle: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            setBackgroundColor(0xFF101525.toInt())
        }
        val heading = TextView(this).apply {
            text = title
            textSize = 28f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = subtitle
            textSize = 14f
            setTextColor(0xFFB9C3DC.toInt())
            setPadding(0, dp(6), 0, dp(20))
        }
        root.addView(heading)
        root.addView(sub)
        setContentView(root)
    }

    private fun showHome() {
        stopHost()
        baseLayout("CamLink", "Share a live camera between two Android phones.")
        addButton("📷  Share this phone's camera") { showHost() }
        addButton("👀  View another phone") { showViewer() }
        val note = TextView(this).apply {
            text = "V1 • Same Wi-Fi network • Local network only\n\nKeep both phones on the same trusted Wi-Fi. The camera phone must stay awake while streaming."
            textSize = 14f
            setTextColor(0xFFB9C3DC.toInt())
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(note)
    }

    private fun showHost() {
        stopHost()
        baseLayout("Camera phone", "Camera sharing and performance controls")
        makeRootScrollable()
        hostPreviewWebView?.destroy()
        hostPreviewWebView = WebView(this).apply {
            setBackgroundColor(0xFF03050A.toInt())
            settings.javaScriptEnabled = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            loadDataWithBaseURL("http://127.0.0.1:8080/", localHostPreviewHtml(), "text/html", "UTF-8", null)
        }
        root.addView(hostPreviewWebView, LinearLayout.LayoutParams(-1, dp(190)))
        val status = TextView(this).apply {
            text = "Camera sharing is off."
            textSize = 14f
            setTextColor(0xFF8FE3D0.toInt())
            setPadding(0, dp(8), 0, dp(6))
        }
        hostStatusView = status
        root.addView(status)

        addButton("Start camera sharing") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                Toast.makeText(this, "Grant camera permission, then tap Start again.", Toast.LENGTH_LONG).show()
            } else {
                startHost(status)
            }
        }

        addButton("⚡ MAX FPS / Low Latency preset") { applyHostPerformancePreset() }
        addHostCameraControls()

        val metrics = TextView(this).apply {
            text = "Waiting for camera metrics…"
            textSize = 12f
            setTextColor(0xFFB9C3DC.toInt())
            setPadding(0, dp(8), 0, dp(4))
        }
        hostMetricsView = metrics
        root.addView(metrics)
        val result = TextView(this).apply {
            text = "Controls will report their result here."
            textSize = 12f
            setTextColor(0xFF8FE3D0.toInt())
            setPadding(0, dp(2), 0, dp(8))
        }
        hostControlStatusView = result
        root.addView(result)

        addButton("Stop sharing") {
            stopHost()
            status.text = "Camera sharing is off."
            metrics.text = "Camera sharing stopped."
        }
        addButton("← Back") { showHome() }
    }

    private fun localHostPreviewHtml() = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;width:100%;height:100%;background:#03050a;display:grid;place-items:center;overflow:hidden}
        img{display:block;width:100%;height:100%;object-fit:contain}</style></head>
        <body><img id="feed" alt="Waiting for camera…"><script>
        const feed=document.getElementById('feed');
        let frameTimer=null;
        function pollFrame(){
            clearTimeout(frameTimer);
            if(document.hidden){frameTimer=setTimeout(pollFrame,150);return;}
            feed.onload=()=>{frameTimer=setTimeout(pollFrame,50);};
            feed.onerror=()=>{frameTimer=setTimeout(pollFrame,700);};
            feed.src='http://127.0.0.1:8080/frame?t='+Date.now();
        }
        pollFrame();
        document.addEventListener('visibilitychange',()=>{if(!document.hidden)pollFrame();});
        </script></body></html>
    """.trimIndent()

    private fun makeRootScrollable() {
        (root.parent as? ViewGroup)?.removeView(root)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(root, android.widget.FrameLayout.LayoutParams(-1, -2))
        setContentView(scroll)
    }

    private fun addHostCameraControls() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostResolutionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("480p", "720p · MAX", "1080p", "1440p / 2K", "2160p / 4K")
            )
            setSelection(1)
        }
        row.addView(hostResolutionSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        val applyResolution = Button(this).apply {
            text = "Apply"
            isAllCaps = false
            setOnClickListener {
                if (!hosting) {
                    hostControlStatusView?.text = "Start camera sharing before changing capture settings."
                } else {
                    sendHostControl("quality", (hostResolutionSpinner?.selectedItemPosition ?: 1).toString())
                }
            }
        }
        row.addView(applyResolution, LinearLayout.LayoutParams(dp(94), dp(44)))
        root.addView(row)

        val qualityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostJpegLabel = TextView(this).apply {
            text = "JPEG 75%"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        }
        qualityRow.addView(hostJpegLabel, LinearLayout.LayoutParams(dp(78), -2))
        hostJpegSeekBar = SeekBar(this).apply {
            max = 9
            progress = 5 // JPEG quality 75 on the 50..95 scale, steps of five.
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    hostJpegLabel?.text = "JPEG ${50 + progress * 5}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        qualityRow.addView(hostJpegSeekBar, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(qualityRow)
        addButton("Apply JPEG quality") {
            if (!hosting) {
                hostControlStatusView?.text = "Start camera sharing before changing JPEG quality."
            } else {
                val value = 50 + (hostJpegSeekBar?.progress ?: 5) * 5
                sendHostControl("jpeg", value.toString())
            }
        }

        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostZoomLabel = TextView(this).apply {
            text = "Zoom 1.0×"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        }
        zoomRow.addView(hostZoomLabel, LinearLayout.LayoutParams(dp(82), -2))
        hostZoomSeekBar = SeekBar(this).apply {
            max = 90
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    hostZoomLabel?.text = String.format(java.util.Locale.US, "Zoom %.1f×", 1.0 + progress / 10.0)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        zoomRow.addView(hostZoomSeekBar, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(zoomRow)
        addButton("Apply zoom") {
            val zoom = 1.0 + (hostZoomSeekBar?.progress ?: 0) / 10.0
            sendHostControl("zoom", String.format(java.util.Locale.US, "%.1f", zoom))
        }

        val exposureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostExposureLabel = TextView(this).apply {
            text = "Exposure 0"
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
        }
        exposureRow.addView(hostExposureLabel, LinearLayout.LayoutParams(dp(94), -2))
        hostExposureSeekBar = SeekBar(this).apply {
            max = 6
            progress = 3
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    hostExposureLabel?.text = "Exposure ${progress - 3}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        exposureRow.addView(hostExposureSeekBar, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(exposureRow)
        addButton("Apply exposure") {
            sendHostControl("exposure", ((hostExposureSeekBar?.progress ?: 3) - 3).toString())
        }

        val whiteBalanceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostWhiteBalanceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf("auto", "daylight", "cloudy", "incandescent", "fluorescent")
            )
            setSelection(0)
        }
        whiteBalanceRow.addView(hostWhiteBalanceSpinner, LinearLayout.LayoutParams(0, dp(48), 1f))
        whiteBalanceRow.addView(compactButton("Apply WB", dp(0)) {
            val value = hostWhiteBalanceSpinner?.selectedItem?.toString() ?: "auto"
            sendHostControl("whitebalance", value)
        }, LinearLayout.LayoutParams(dp(112), dp(44)))
        root.addView(whiteBalanceRow)

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quickRow.addView(compactButton("Flip", dp(0)) { sendHostControl("switch") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        quickRow.addView(compactButton("Flash", dp(0)) { sendHostControl("torch") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        quickRow.addView(compactButton("Focus", dp(0)) { sendHostControl("focus") }, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(quickRow)
    }

    private fun compactButton(label: String, ignoredWidth: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { if (hosting) action() else hostControlStatusView?.text = "Start camera sharing first." }
        }

    private fun applyHostPerformancePreset() {
        hostResolutionSpinner?.setSelection(1)
        hostJpegSeekBar?.progress = 5
        if (!hosting) {
            hostControlStatusView?.text = "MAX preset selected. Start sharing to apply 720p / JPEG quality 75."
            return
        }
        sendHostControl("quality", "1")
        metricsHandler.postDelayed({ sendHostControl("jpeg", "75") }, 180)
    }

    private fun sendHostControl(command: String, value: String? = null) {
        if (!hosting) {
            hostControlStatusView?.text = "Start camera sharing first."
            return
        }
        val intent = Intent(this, CameraStreamingService::class.java)
            .setAction(CameraStreamingService.ACTION_CONTROL)
            .putExtra(CameraStreamingService.EXTRA_CONTROL_COMMAND, command)
        if (value != null) intent.putExtra(CameraStreamingService.EXTRA_CONTROL_VALUE, value)
        try {
            startService(intent)
            hostControlStatusView?.text = "Sending $command…"
        } catch (e: Exception) {
            hostControlStatusView?.text = "Could not send $command: ${e.message ?: "unknown error"}"
        }
    }

    private fun startHostMetrics() {
        metricsRunnable?.let { metricsHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                val fps = CameraStreamingService.streamFps
                val encodeMs = CameraStreamingService.streamEncodeMs
                val mbps = CameraStreamingService.streamBitrateMbps
                val width = CameraStreamingService.streamFrameWidth
                val height = CameraStreamingService.streamFrameHeight
                val quality = CameraStreamingService.streamJpegQuality
                hostMetricsView?.text = String.format(
                    java.util.Locale.US,
                    "Camera output: %.1f FPS · %.1f ms JPEG encode · %.2f Mbps · %dx%d · JPEG %d%%",
                    fps, encodeMs, mbps, width, height, quality
                )
                if (hosting) metricsHandler.postDelayed(this, 1000)
            }
        }
        metricsRunnable = runnable
        metricsHandler.post(runnable)
    }

    private fun stopHostMetrics() {
        metricsRunnable?.let { metricsHandler.removeCallbacks(it) }
        metricsRunnable = null
    }

    private fun startHost(status: TextView) {
        if (hosting) return
        try {
            val serviceIntent = Intent(this, CameraStreamingService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            hosting = true
            hostPreviewWebView?.loadDataWithBaseURL(
                "http://127.0.0.1:8080/", localHostPreviewHtml(), "text/html", "UTF-8", null
            )
            status.text = "STARTING • Waiting for CameraX to open the camera…"
            hostControlStatusView?.text = "Starting camera service…"
            Toast.makeText(this, "Starting camera sharing…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            status.text = "Could not start sharing: ${e.message ?: "unknown error"}"
        }
    }

    private fun startCamera() {
        val preview = previewView ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(android.util.Size(640, 480))
                    .build()
                analysis.setAnalyzer(cameraExecutor) { image ->
                    try {
                        latestJpeg.set(imageToJpeg(image))
                    } catch (_: Exception) {
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.Builder().requireLensFacing(lensFacing).build(), previewUseCase, analysis)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageToJpeg(image: androidx.camera.core.ImageProxy): ByteArray {
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        val width = image.width
        val height = image.height
        val nv21 = ByteArray(width * height * 3 / 2)
        var pos = 0
        val yRowStride = image.planes[0].rowStride
        val yPixelStride = image.planes[0].pixelStride
        for (row in 0 until height) {
            for (col in 0 until width) nv21[pos++] = y.get(row * yRowStride + col * yPixelStride)
        }
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                nv21[pos++] = v.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                nv21[pos++] = u.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }
        val output = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 65, output)
        return output.toByteArray()
    }

    private fun showViewer() {
        stopHost()
        baseLayout("Viewer phone", "Connect to the camera phone on the same Wi-Fi.")
        val address = EditText(this).apply {
            hint = "Camera phone IP, e.g. 192.168.1.12"
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8A94AD.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(address)
        addButton("Connect to camera") {
            val host = address.text.toString().trim().removePrefix("http://").removeSuffix("/")
            if (host.isBlank() || host.contains("/") || host.contains(" ")) {
                Toast.makeText(this, "Enter the camera phone's IP address.", Toast.LENGTH_SHORT).show()
            } else {
                webView?.destroy()
                webView = WebView(this).apply {
                    settings.javaScriptEnabled = true
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    loadDataWithBaseURL("http://$host:8080/", viewerHtml(host), "text/html", "UTF-8", null)
                }
                root.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        }
        addButton("← Back") { webView?.destroy(); webView = null; showHome() }
    }

    private fun viewerHtml(host: String) = """
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="color-scheme" content="dark"><title>CamLink Viewer</title>
        <style>
        :root{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;color:#f4f7fb;background:#0a0f1a;color-scheme:dark}
        *{box-sizing:border-box}body{margin:0;padding:16px;background:radial-gradient(ellipse at 15% 0%,#1b2a47,#0a0f1a 70%)}
        main{max-width:1000px;margin:auto}header{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:14px}
        h1{font-size:1.7rem;margin:0}.muted{color:#a1aec2;font-size:.9rem}.badge{background:#12352f;color:#70e0bd;padding:7px 10px;border-radius:999px;font-size:.75rem;font-weight:700}
        .layout{display:grid;grid-template-columns:minmax(0,1fr) 270px;gap:14px}.viewer{background:#03050a;border:1px solid #2b3b53;border-radius:14px;overflow:hidden;display:grid;place-items:center;min-height:180px}
        img{width:100%;max-height:70vh;object-fit:contain;display:block}img.original{width:auto;max-width:none;max-height:none}section{background:#121b2b;border:1px solid #2b3b53;border-radius:14px;padding:14px}
        button,select{font:inherit;border-radius:9px;padding:10px;border:1px solid #34445d;background:#1b2a42;color:white}
        button{background:#315fe0;border-color:#5c87ff;font-weight:650;cursor:pointer}.row{display:flex;gap:6px;margin-bottom:8px}.row button{flex:1;min-width:0;font-size:.84rem;padding:9px 4px}
        label{display:flex;justify-content:space-between;margin:12px 0 5px;font-size:.9rem}input[type=range]{width:100%;accent-color:#6287ff}
        select{width:100%;margin-bottom:4px}#controlStatus{margin-top:12px;overflow-wrap:anywhere}
        @media(max-width:720px){.layout{grid-template-columns:1fr}.viewer{min-height:120px}}
        </style></head><body><main>
        <header><div><h1>CamLink</h1><div class="muted">LIVE CAMERA · REMOTE CONTROLS</div></div><span id="badge" class="badge">CONNECTING</span></header>
        <div class="layout"><div><div class="viewer"><img id="feed" crossorigin="anonymous" alt="Live camera frame"></div><p class="muted" id="status">Connecting to live frames…</p><p class="muted" id="metrics">Measuring camera FPS and bitrate…</p><div class="row"><button id="fit">Fit preview</button><button id="original">Original size</button><button id="snapshot">Save snapshot</button></div></div>
        <section><div class="row"><button id="switch">Flip camera</button><button id="torch">Flash</button><button id="focus">Focus</button></div>
        <div class="row"><button id="performance">⚡ Max FPS / Low Latency preset</button></div>
        <label for="zoom">Zoom <strong id="zoomLabel">1.0×</strong></label><input id="zoom" type="range" min="1" max="10" step=".1" value="1">
        <label for="exposure">Exposure <strong id="exposureLabel">0</strong></label><input id="exposure" type="range" min="-3" max="3" step="1" value="0">
        <label for="whitebalance">White balance</label><select id="whitebalance"><option value="auto">Auto</option><option value="daylight">Daylight</option><option value="cloudy">Cloudy</option><option value="incandescent">Incandescent</option><option value="fluorescent">Fluorescent</option></select>
        <label for="resolution">Capture resolution</label><select id="resolution"><option value="0">480p · Compatibility</option><option value="1" selected>720p · HD</option><option value="2">1080p · Full HD</option><option value="3">1440p · 2K</option><option value="4">2160p · 4K if supported</option></select>
        <label for="jpeg">JPEG quality <strong id="jpegLabel">75%</strong></label><input id="jpeg" type="range" min="50" max="95" step="5" value="75">
        <div class="row" style="margin-top:12px"><button id="reconnect">Reconnect feed</button></div><p class="muted" id="controlStatus" aria-live="polite">Controls require camera support.</p></section></div>
        <p class="muted">Use only on a trusted Wi-Fi network. Controls are available to devices that can reach this camera phone.</p>
        <script>
        const $=id=>document.getElementById(id),feed=$('feed');
        feed.onload=()=>{$('badge').textContent='LIVE';$('status').textContent='Live · frame polling';};
        feed.onerror=()=>{$('badge').textContent='WAITING';$('status').textContent='Frame unavailable. Check that camera sharing is active.';};
        async function refreshMetrics(){try{const r=await fetch('http://$host:8080/stats',{cache:'no-store'});const d=await r.json();$('metrics').textContent='Camera '+Number(d.fps||0).toFixed(1)+' FPS · '+Number(d.encodeMs||0).toFixed(1)+' ms encode · '+Number(d.bitrateMbps||0).toFixed(2)+' Mbps · '+(d.width||0)+'×'+(d.height||0)+' · JPEG '+(d.jpegQuality||75)+'%';}catch(e){$('metrics').textContent='Camera performance metrics unavailable.';}}
        refreshMetrics();setInterval(refreshMetrics,1000);
        $('fit').onclick=()=>{feed.classList.remove('original');feed.style.width='100%';feed.style.maxHeight='70vh';};
        $('original').onclick=()=>{feed.classList.add('original');feed.style.maxHeight='none';};
        $('snapshot').onclick=()=>{try{const canvas=document.createElement('canvas');canvas.width=feed.naturalWidth;canvas.height=feed.naturalHeight;canvas.getContext('2d').drawImage(feed,0,0);const a=document.createElement('a');a.href=canvas.toDataURL('image/jpeg',0.92);a.download='CamLink_snapshot.jpg';a.click();$('controlStatus').textContent='Snapshot saved to Downloads if supported by this WebView.';}catch(e){$('controlStatus').textContent='Snapshot unavailable in this WebView. Use the Windows app to save a snapshot.';}};
        async function command(cmd,value){try{const q=new URLSearchParams({cmd});if(value!==undefined)q.set('value',value);const r=await fetch('http://$host:8080/control?'+q.toString(),{cache:'no-store'});$('controlStatus').textContent=await r.text();}catch(e){$('controlStatus').textContent='Control failed · check Wi-Fi and camera sharing.';}}
        $('switch').onclick=()=>command('switch');$('torch').onclick=()=>command('torch');$('focus').onclick=()=>command('focus');
$('performance').onclick=async()=>{ $('resolution').value='1';$('jpeg').value='75';$('jpegLabel').textContent='75%';await command('quality','1');await command('jpeg','75');};
        $('zoom').oninput=()=> $('zoomLabel').textContent=Number($('zoom').value).toFixed(1)+'×';
        $('zoom').onchange=()=>command('zoom',$('zoom').value);
        $('exposure').oninput=()=> $('exposureLabel').textContent=$('exposure').value;
        $('exposure').onchange=()=>command('exposure',$('exposure').value);
        $('whitebalance').onchange=()=>command('whitebalance',$('whitebalance').value);
        $('resolution').onchange=()=>command('quality',$('resolution').value);
        $('jpeg').oninput=()=> $('jpegLabel').textContent=$('jpeg').value+'%';
        $('jpeg').onchange=()=>command('jpeg',$('jpeg').value);
        let frameTimer=null;
        function pollFrame(){
            clearTimeout(frameTimer);
            if(document.hidden){frameTimer=setTimeout(pollFrame,150);return;}
            feed.onload=()=>{$('badge').textContent='LIVE';$('status').textContent='Live · frame polling';frameTimer=setTimeout(pollFrame,50);};
            feed.onerror=()=>{$('badge').textContent='WAITING';$('status').textContent='Frame unavailable. Check that camera sharing is active.';frameTimer=setTimeout(pollFrame,700);};
            feed.src='http://$host:8080/frame?t='+Date.now();
        }
        pollFrame();
        document.addEventListener('visibilitychange',()=>{if(!document.hidden)pollFrame();});
        $('reconnect').onclick=()=>{ $('badge').textContent='CONNECTING';pollFrame();};
        </script></main></body></html>
    """.trimIndent()

    private fun addButton(label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        root.addView(button, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
    }

    private fun localIpAddress(): String {
        // Prefer the actual Wi-Fi interface address. Iterating all interfaces first can
        // accidentally select a VPN, mobile-data, or virtual-adapter IP that viewers
        // on the same Wi-Fi network cannot reach.
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val wifiIp = wifi.connectionInfo.ipAddress
            if (wifiIp != 0) {
                val formatted = Formatter.formatIpAddress(wifiIp)
                if (formatted.isNotBlank() && formatted != "0.0.0.0") return formatted
            }
        } catch (_: Exception) { }

        try {
            val candidates = mutableListOf<Pair<Int, String>>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        val isWifi = networkInterface.name.startsWith("wlan", ignoreCase = true) ||
                            networkInterface.name.startsWith("wifi", ignoreCase = true)
                        candidates.add((if (isWifi) 0 else 1) to (address.hostAddress ?: continue))
                    }
                }
            }
            candidates.sortedBy { it.first }.firstOrNull()?.second?.let { return it }
        } catch (_: Exception) { }
        return "0.0.0.0"
    }

    private fun stopHost() {
        stopHostMetrics()
        hostPreviewWebView?.stopLoading()
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        cameraProvider = null
        try { stopService(Intent(this, CameraStreamingService::class.java)) } catch (_: Exception) { }
        try { server?.stop() } catch (_: Exception) { }
        server = null
        hosting = false
        latestJpeg.set(null)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onStart() {
        super.onStart()
        if (!controlReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                controlResultReceiver,
                IntentFilter().apply {
                    addAction(CameraStreamingService.ACTION_CONTROL_RESULT)
                    addAction(CameraStreamingService.ACTION_SERVICE_STATUS)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            controlReceiverRegistered = true
        }
        if (hosting && hostMetricsView != null) startHostMetrics()
    }

    override fun onStop() {
        if (controlReceiverRegistered) {
            try { unregisterReceiver(controlResultReceiver) } catch (_: Exception) { }
            controlReceiverRegistered = false
        }
        stopHostMetrics()
        super.onStop()
    }

    override fun onDestroy() {
        // Do not stop CameraStreamingService here: it is designed to outlive this Activity.
        stopHostMetrics()
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        webView?.destroy()
        hostPreviewWebView?.destroy()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_CAMERA = 41
    }
}

fun viewerPage() = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="dark">
<title>CamLink Live Camera</title>
<style>
:root{font-family:Inter,ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif;color:#f4f7fb;background:#0a0f1a;color-scheme:dark}
*{box-sizing:border-box}body{margin:0;min-height:100vh;padding:clamp(12px,2vw,28px);background:radial-gradient(ellipse at 12% 0%,#1b2a47 0%,#0d1422 48%,#080c14 100%)}
main{width:min(100%,1500px);margin:auto}h1{margin:0;font-size:clamp(1.7rem,4vw,2.45rem);letter-spacing:-.045em;font-weight:750}.muted{color:#a1aec2}
.layout{display:grid;grid-template-columns:minmax(0,1fr) 320px;gap:20px;align-items:start}.feed-column{min-width:0}
header{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:22px;padding:4px 2px 18px;border-bottom:1px solid #27344a}
.badge{background:#12352f;color:#70e0bd;border:1px solid #235247;border-radius:999px;padding:8px 12px;font-size:.78rem;font-weight:750;letter-spacing:.04em}
.viewer{background:#05070d;border:1px solid #29324b;border-radius:16px;overflow:hidden;min-height:220px;display:grid;place-items:center;box-shadow:0 22px 60px #0005}
img{display:block;width:100%;max-height:78vh;object-fit:contain}footer{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-top:12px;font-size:.9rem}
.controls{position:sticky;top:16px;padding:18px;background:linear-gradient(160deg,#19243b,#121a2d);border:1px solid #344363;border-radius:18px;box-shadow:0 18px 50px #0004}
button{background:linear-gradient(180deg,#4778ff,#315fe0);color:white;border:1px solid #5c87ff;border-radius:12px;padding:11px 10px;font-weight:650;cursor:pointer}
button:hover{filter:brightness(1.1)}button:active{transform:scale(.99)}.row{display:flex;gap:8px;margin-bottom:12px}.row button{flex:1;min-width:0}
label{display:flex;justify-content:space-between;margin:11px 0 6px}input[type=range]{width:100%;accent-color:#5b83ff;cursor:pointer}
select{width:100%;padding:11px;border-radius:11px;background:#151f30;color:#f4f7fb;border:1px solid #34445d;margin:5px 0 12px;outline:none}
select:focus,input:focus{border-color:#6287ff}#controlStatus{line-height:1.5;overflow-wrap:anywhere}
@media(max-width:850px){.layout{grid-template-columns:1fr}.controls{position:static}.viewer{min-height:180px}}
</style>
</head>
<body><main>
<header><div><h1>CamLink</h1><div class="muted" style="margin-top:5px;font-size:.92rem">LIVE CAMERA / PRIVATE NETWORK VIEWER</div></div><span id="badge" class="badge">CONNECTING</span></header>
<div class="layout"><div class="feed-column"><div class="viewer"><img id="feed" crossorigin="anonymous" alt="Live camera frame"></div>
<footer><span id="status" class="muted">Connecting to continuous stream…</span><button id="refresh" type="button">Reconnect stream</button></footer><p id="metrics" class="muted">Measuring camera FPS and bitrate…</p></div>
<section class="controls" aria-label="Remote camera controls">
<div class="row"><button id="switch" type="button">Flip camera</button><button id="torch" type="button">Flashlight</button><button id="focus" type="button">Focus</button></div>
<div class="row"><button id="performance" type="button">⚡ Max FPS / Low Latency preset</button></div>
<label for="zoom">Zoom <strong id="zoomLabel">1.0×</strong></label><input id="zoom" type="range" min="1" max="10" step="0.1" value="1">
<label for="exposure">Exposure compensation <strong id="exposureLabel">0</strong></label><input id="exposure" type="range" min="-3" max="3" step="1" value="0">
<label for="whitebalance">White balance</label><select id="whitebalance"><option value="auto">Auto</option><option value="daylight">Daylight</option><option value="cloudy">Cloudy</option><option value="incandescent">Incandescent</option><option value="fluorescent">Fluorescent</option></select>
<label for="resolution">Capture resolution</label><select id="resolution"><option value="0">480p · Compatibility</option><option value="1" selected>720p · HD</option><option value="2">1080p · Full HD</option><option value="3">1440p · 2K</option><option value="4">2160p · 4K (if supported)</option></select>
<label for="jpeg">JPEG quality <strong id="jpegLabel">75%</strong></label><input id="jpeg" type="range" min="50" max="95" step="5" value="75">
<p id="controlStatus" class="muted" aria-live="polite">Remote controls require camera support.</p></section></div>
<p class="muted">Controls affect the camera phone. This local page is unauthenticated; use only on a trusted network.</p>
<script>
const $=id=>document.getElementById(id),feed=$('feed'),status=$('status'),badge=$('badge');
feed.onload=()=>{badge.textContent='LIVE';status.textContent='Live · frame polling';};
feed.onerror=()=>{badge.textContent='WAITING';status.textContent='Frame unavailable. Check that sharing is started.';};
async function refreshMetrics(){try{const r=await fetch('/stats',{cache:'no-store'});const d=await r.json();$('metrics').textContent='Camera '+Number(d.fps||0).toFixed(1)+' FPS · '+Number(d.encodeMs||0).toFixed(1)+' ms encode · '+Number(d.bitrateMbps||0).toFixed(2)+' Mbps · '+(d.width||0)+'×'+(d.height||0)+' · JPEG '+(d.jpegQuality||75)+'%';}catch(e){$('metrics').textContent='Camera performance metrics unavailable.';}}
refreshMetrics();setInterval(refreshMetrics,1000);
async function command(cmd,value){const out=$('controlStatus');try{const q=new URLSearchParams({cmd});if(value!==undefined)q.set('value',value);const response=await fetch('/control?'+q.toString(),{cache:'no-store'});out.textContent=await response.text();}catch(e){out.textContent='Control failed. Check that sharing is active.';}}
$('switch').onclick=()=>command('switch');$('torch').onclick=()=>command('torch');$('focus').onclick=()=>command('focus');
$('performance').onclick=async()=>{ $('resolution').value='1';$('jpeg').value='75';$('jpegLabel').textContent='75%';await command('quality','1');await command('jpeg','75');};
$('zoom').oninput=()=> $('zoomLabel').textContent=Number($('zoom').value).toFixed(1)+'×';$('zoom').onchange=()=>command('zoom',$('zoom').value);
$('exposure').oninput=()=> $('exposureLabel').textContent=$('exposure').value;$('exposure').onchange=()=>command('exposure',$('exposure').value);
$('whitebalance').onchange=()=>command('whitebalance',$('whitebalance').value);
$('resolution').onchange=()=>command('quality',$('resolution').value);
$('jpeg').oninput=()=> $('jpegLabel').textContent=$('jpeg').value+'%';$('jpeg').onchange=()=>command('jpeg',$('jpeg').value);
let frameTimer=null;
function pollFrame(){
  clearTimeout(frameTimer);
  if(document.hidden){frameTimer=setTimeout(pollFrame,150);return;}
  feed.onload=()=>{badge.textContent='LIVE';status.textContent='Live · frame polling';frameTimer=setTimeout(pollFrame,50);};
  feed.onerror=()=>{badge.textContent='WAITING';status.textContent='Frame unavailable. Check that sharing is started.';frameTimer=setTimeout(pollFrame,700);};
  feed.src='/frame?t='+Date.now();
}
pollFrame();
document.addEventListener('visibilitychange',()=>{if(!document.hidden)pollFrame();});
$('refresh').onclick=()=>{badge.textContent='CONNECTING';pollFrame();};
</script>
</main></body></html>
""".trimIndent()

class FrameServer(
    private val latestFrame: AtomicReference<ByteArray?>,
    private val controlHandler: ((String, String?) -> String)? = null
) : NanoHTTPD("0.0.0.0", 8080) {
    private fun mjpegStream(): InputStream = object : InputStream() {
        @Volatile private var closed = false
        private val trailer = "\r\n".toByteArray(Charsets.US_ASCII)
        private var header = ByteArray(0)
        private var currentFrame: ByteArray? = null
        // 0 = header, 1 = JPEG, 2 = trailing CRLF, 3 = wait for next frame.
        private var segment = 3
        private var offset = 0
        private var previousFrame: ByteArray? = null

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (len == 0) return 0
            while (true) {
                if (segment < 3) {
                    val source = when (segment) {
                        0 -> header
                        1 -> currentFrame ?: ByteArray(0)
                        else -> trailer
                    }
                    if (offset < source.size) {
                        val count = minOf(len, source.size - offset)
                        System.arraycopy(source, offset, buffer, off, count)
                        offset += count
                        return count
                    }
                    segment++
                    offset = 0
                    continue
                }

                var nextFrame: ByteArray?
                do {
                    if (closed) return -1
                    nextFrame = latestFrame.get()
                    if (nextFrame == null || nextFrame === previousFrame) {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return -1
                        }
                    }
                } while (nextFrame == null || nextFrame === previousFrame)

                currentFrame = nextFrame
                previousFrame = nextFrame
                header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${nextFrame.size}\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
                segment = 0
                offset = 0
            }
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/stream" -> newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", mjpegStream()).apply {
                addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                addHeader("Access-Control-Allow-Origin", "*")
                addHeader("Connection", "close")
            }
            "/stats" -> newFixedLengthResponse(
                Response.Status.OK, "application/json; charset=utf-8", CameraStreamingService.statsJsonForViewer()
            ).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("Access-Control-Allow-Origin", "*")
            }
            "/control" -> {
                val params = session.parameters
                val command = params["cmd"]?.firstOrNull().orEmpty()
                val value = params["value"]?.firstOrNull()
                val message = if (controlHandler == null) "Controls are unavailable" else controlHandler.invoke(command, value)
                newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", message).apply {
                    addHeader("Cache-Control", "no-store")
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            }
            "/frame" -> {
                val frame = latestFrame.get()
                if (frame == null) newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Camera is starting")
                else newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(frame), frame.size.toLong()).apply {
                    addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
                    addHeader("Access-Control-Allow-Origin", "*")
                }
            }
            "/", "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", viewerPage()).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Content-Type-Options", "nosniff")
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }
}
