package com.intersprit.camlink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MainActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private var previewView: PreviewView? = null
    private var webView: WebView? = null
    private var server: FrameServer? = null
    private val latestJpeg = AtomicReference<ByteArray?>(null)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var cameraProvider: ProcessCameraProvider? = null
    private var hosting = false

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
        baseLayout("Camera phone", "Your camera stays on this device. Start sharing when you're ready.")
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))
        val status = TextView(this).apply {
            text = "Camera sharing is off."
            textSize = 15f
            setTextColor(0xFF8FE3D0.toInt())
            setPadding(0, dp(12), 0, dp(8))
        }
        root.addView(status)
        addButton("Start camera sharing") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
                Toast.makeText(this, "Grant camera permission, then tap Start again.", Toast.LENGTH_LONG).show()
            } else {
                startHost(status)
            }
        }
        addButton("Switch front / rear camera") {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            if (hosting) startService(Intent(this, CameraStreamingService::class.java).setAction(CameraStreamingService.ACTION_SWITCH))
        }
        addButton("Stop sharing") {
            stopHost()
            status.text = "Camera sharing is off."
        }
        addButton("← Back") { showHome() }
        // Camera capture is owned by the foreground service so streaming can continue when this screen is backgrounded.
    }

    private fun startHost(status: TextView) {
        if (hosting) return
        try {
            val serviceIntent = Intent(this, CameraStreamingService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            hosting = true
            val ip = localIpAddress()
            status.text = "LIVE • http://$ip:8080\nStreaming continues in the background."
            Toast.makeText(this, "Background camera sharing started", Toast.LENGTH_SHORT).show()
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
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>body{margin:0;background:#101525;color:#fff;font:16px sans-serif;text-align:center}
        h3{font-size:18px}img{width:100%;max-height:75vh;object-fit:contain;background:#000}
        p{color:#b9c3dc}</style></head><body><h3>CamLink · Live view</h3>
        <img id="feed" alt="Waiting for camera frames…"><p id="state">Connecting…</p>
        <script>const feed=document.getElementById('feed'),state=document.getElementById('state');
        function refresh(){const i=new Image();i.onload=()=>{feed.src=i.src;state.textContent='Connected · live frames'};
        i.onerror=()=>{state.textContent='Waiting for camera phone… check IP and Wi-Fi'};
        i.src='http://$host:8080/frame?t='+Date.now();} refresh();setInterval(refresh,180);</script></body></html>
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
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) return address.hostAddress ?: "0.0.0.0"
                }
            }
        } catch (_: Exception) { }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        return Formatter.formatIpAddress(wifi.connectionInfo.ipAddress).ifBlank { "0.0.0.0" }
    }

    private fun stopHost() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        cameraProvider = null
        try { stopService(Intent(this, CameraStreamingService::class.java)) } catch (_: Exception) { }
        try { server?.stop() } catch (_: Exception) { }
        server = null
        hosting = false
        latestJpeg.set(null)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        // Do not stop CameraStreamingService here: it is designed to outlive this Activity.
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        webView?.destroy()
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
:root{font-family:system-ui,-apple-system,Segoe UI,sans-serif;color:#f4f6ff;background:#101525}
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:flex;justify-content:center;align-items:center;padding:18px}
main{width:min(100%,900px)}h1{margin:0;font-size:clamp(1.6rem,5vw,2.3rem)}.muted{color:#b9c3dc}
header{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:16px}
.badge{background:#173b38;color:#8fe3d0;border-radius:999px;padding:7px 11px;font-size:.85rem}
.viewer{background:#05070d;border:1px solid #29324b;border-radius:16px;overflow:hidden;min-height:220px;display:grid;place-items:center}
img{display:block;width:100%;max-height:75vh;object-fit:contain}#placeholder{padding:28px;text-align:center;color:#b9c3dc}
footer{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-top:12px;font-size:.9rem}
button{background:#315cff;color:white;border:0;border-radius:10px;padding:11px 16px;font-weight:600;cursor:pointer}
.controls{margin-top:14px;padding:14px;background:#171f34;border:1px solid #29324b;border-radius:14px}
.row{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:12px}.row button{flex:1}
label{display:flex;justify-content:space-between;margin:8px 0}
input[type=range]{width:100%;accent-color:#315cff}
select{width:100%;padding:10px;border-radius:9px;background:#202a42;color:#f4f6ff;border:1px solid #394663;margin:4px 0 12px}
</style>
</head>
<body><main>
<header><div><h1>CamLink</h1><div class="muted">Local network camera viewer</div></div><span id="badge" class="badge">Connecting…</span></header>
<div class="viewer"><div id="placeholder">Waiting for camera frames…<br>Keep CamLink open on the camera phone.</div><img id="feed" alt="Live camera feed" hidden></div>
<section class="controls" aria-label="Remote camera controls">
<div class="row"><button id="switch" type="button">Switch front / rear</button><button id="torch" type="button">Flashlight on / off</button></div>
<label for="zoom">Zoom <strong id="zoomLabel">1.0×</strong></label>
<input id="zoom" type="range" min="1" max="4" step="0.1" value="1">
<label for="resolution">Capture resolution</label>
<select id="resolution"><option value="0">480p · compatibility</option><option value="1">720p · HD</option><option value="2" selected>1080p · Full HD</option><option value="3">1440p · 2K</option><option value="4">2160p · 4K (if supported)</option></select>
<label for="jpeg">JPEG quality <strong id="jpegLabel">85%</strong></label>
<input id="jpeg" type="range" min="50" max="95" step="5" value="85">
<div class="row"><button id="refresh" type="button">Reconnect</button></div>
<p id="controlStatus" class="muted" aria-live="polite">Controls affect the camera phone.</p>
</section>
<footer><span id="status" class="muted">Connecting to camera stream…</span></footer>
<p class="muted">Remote controls work on supported cameras. This local page is unauthenticated; use only on a trusted network.</p>
<script>
const feed=document.getElementById('feed'),placeholder=document.getElementById('placeholder'),status=document.getElementById('status'),badge=document.getElementById('badge');
let timer=null,failures=0;
function poll(){
 const image=new Image();
 image.onload=()=>{feed.src=image.src;feed.hidden=false;placeholder.hidden=true;failures=0;status.textContent='Live • refreshes automatically';badge.textContent='LIVE';};
 image.onerror=()=>{failures++;status.textContent='Waiting for camera frames. Check that sharing is started.';badge.textContent='WAITING';};
 image.src='/frame?t='+Date.now();
}
async function command(cmd,value){
 const out=document.getElementById('controlStatus');
 try{
  const suffix=value===undefined?'':'&value='+encodeURIComponent(value);
  const response=await fetch('/control?cmd='+encodeURIComponent(cmd)+suffix,{cache:'no-store'});
  out.textContent=await response.text();
 }catch(e){out.textContent='Control failed. Check that sharing is active.';}
}
document.getElementById('switch').addEventListener('click',()=>command('switch'));
document.getElementById('torch').addEventListener('click',()=>command('torch'));
const zoom=document.getElementById('zoom'),zoomLabel=document.getElementById('zoomLabel');
zoom.addEventListener('input',()=>{zoomLabel.textContent=Number(zoom.value).toFixed(1)+'×';});
zoom.addEventListener('change',()=>command('zoom',zoom.value));
const resolution=document.getElementById('resolution');
resolution.addEventListener('change',()=>command('quality',resolution.value));
const jpeg=document.getElementById('jpeg'),jpegLabel=document.getElementById('jpegLabel');
jpeg.addEventListener('input',()=>{jpegLabel.textContent=jpeg.value+'%';});
jpeg.addEventListener('change',()=>command('jpeg',jpeg.value));
document.getElementById('refresh').addEventListener('click',()=>{failures=0;poll();});
poll();timer=setInterval(poll,180);
</script>
</main></body></html>
""".trimIndent()

class FrameServer(private val latestFrame: AtomicReference<ByteArray?>) : NanoHTTPD("0.0.0.0", 8080) {
    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
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
