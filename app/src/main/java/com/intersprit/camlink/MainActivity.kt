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
import java.io.InputStream
import java.io.IOException
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
        <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta name="color-scheme" content="dark"><title>CamLink Viewer</title>
        <style>
        :root{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;color:#f4f7fb;background:#0a0f1a;color-scheme:dark}
        *{box-sizing:border-box}body{margin:0;padding:16px;background:radial-gradient(ellipse at 15% 0%,#1b2a47,#0a0f1a 70%)}
        main{max-width:1000px;margin:auto}header{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:14px}
        h1{font-size:1.7rem;margin:0}.muted{color:#a1aec2;font-size:.9rem}.badge{background:#12352f;color:#70e0bd;padding:7px 10px;border-radius:999px;font-size:.75rem;font-weight:700}
        .layout{display:grid;grid-template-columns:minmax(0,1fr) 270px;gap:14px}.viewer{background:#03050a;border:1px solid #2b3b53;border-radius:14px;overflow:hidden;display:grid;place-items:center;min-height:180px}
        img{width:100%;max-height:70vh;object-fit:contain;display:block}section{background:#121b2b;border:1px solid #2b3b53;border-radius:14px;padding:14px}
        button,select{font:inherit;border-radius:9px;padding:10px;border:1px solid #34445d;background:#1b2a42;color:white}
        button{background:#315fe0;border-color:#5c87ff;font-weight:650;cursor:pointer}.row{display:flex;gap:6px;margin-bottom:8px}.row button{flex:1;min-width:0;font-size:.84rem;padding:9px 4px}
        label{display:flex;justify-content:space-between;margin:12px 0 5px;font-size:.9rem}input[type=range]{width:100%;accent-color:#6287ff}
        select{width:100%;margin-bottom:4px}#controlStatus{margin-top:12px;overflow-wrap:anywhere}
        @media(max-width:720px){.layout{grid-template-columns:1fr}.viewer{min-height:120px}}
        </style></head><body><main>
        <header><div><h1>CamLink</h1><div class="muted">LIVE CAMERA · REMOTE CONTROLS</div></div><span id="badge" class="badge">CONNECTING</span></header>
        <div class="layout"><div><div class="viewer"><img id="feed" src="http://$host:8080/stream" alt="Live camera stream"></div><p class="muted" id="status">Connecting to continuous stream…</p></div>
        <section><div class="row"><button id="switch">Flip camera</button><button id="torch">Flash</button><button id="focus">Focus</button></div>
        <label for="zoom">Zoom <strong id="zoomLabel">1.0×</strong></label><input id="zoom" type="range" min="1" max="10" step=".1" value="1">
        <label for="exposure">Exposure <strong id="exposureLabel">0</strong></label><input id="exposure" type="range" min="-3" max="3" step="1" value="0">
        <label for="whitebalance">White balance</label><select id="whitebalance"><option value="auto">Auto</option><option value="daylight">Daylight</option><option value="cloudy">Cloudy</option><option value="incandescent">Incandescent</option><option value="fluorescent">Fluorescent</option></select>
        <label for="resolution">Capture resolution</label><select id="resolution"><option value="0">480p · Compatibility</option><option value="1">720p · HD</option><option value="2" selected>1080p · Full HD</option><option value="3">1440p · 2K</option><option value="4">2160p · 4K if supported</option></select>
        <label for="jpeg">JPEG quality <strong id="jpegLabel">85%</strong></label><input id="jpeg" type="range" min="50" max="95" step="5" value="85">
        <div class="row" style="margin-top:12px"><button id="reconnect">Reconnect stream</button></div><p class="muted" id="controlStatus" aria-live="polite">Controls require camera support.</p></section></div>
        <p class="muted">Use only on a trusted Wi-Fi network. Controls are available to devices that can reach this camera phone.</p>
        <script>
        const $=id=>document.getElementById(id),feed=$('feed');
        feed.onload=()=>{$('badge').textContent='LIVE';$('status').textContent='Live · continuous stream';};
        feed.onerror=()=>{$('badge').textContent='WAITING';$('status').textContent='Stream unavailable. Check that camera sharing is active.';};
        async function command(cmd,value){try{const q=new URLSearchParams({cmd});if(value!==undefined)q.set('value',value);const r=await fetch('http://$host:8080/control?'+q.toString(),{cache:'no-store'});$('controlStatus').textContent=await r.text();}catch(e){$('controlStatus').textContent='Control failed · check Wi-Fi and camera sharing.';}}
        $('switch').onclick=()=>command('switch');$('torch').onclick=()=>command('torch');$('focus').onclick=()=>command('focus');
        $('zoom').oninput=()=> $('zoomLabel').textContent=Number($('zoom').value).toFixed(1)+'×';
        $('zoom').onchange=()=>command('zoom',$('zoom').value);
        $('exposure').oninput=()=> $('exposureLabel').textContent=$('exposure').value;
        $('exposure').onchange=()=>command('exposure',$('exposure').value);
        $('whitebalance').onchange=()=>command('whitebalance',$('whitebalance').value);
        $('resolution').onchange=()=>command('quality',$('resolution').value);
        $('jpeg').oninput=()=> $('jpegLabel').textContent=$('jpeg').value+'%';
        $('jpeg').onchange=()=>command('jpeg',$('jpeg').value);
        $('reconnect').onclick=()=>{ $('badge').textContent='CONNECTING';feed.src='http://$host:8080/stream?reconnect='+Date.now();};
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
<div class="layout"><div class="feed-column"><div class="viewer"><img id="feed" src="/stream" alt="Live camera feed"></div>
<footer><span id="status" class="muted">Connecting to continuous stream…</span><button id="refresh" type="button">Reconnect stream</button></footer></div>
<section class="controls" aria-label="Remote camera controls">
<div class="row"><button id="switch" type="button">Flip camera</button><button id="torch" type="button">Flashlight</button><button id="focus" type="button">Focus</button></div>
<label for="zoom">Zoom <strong id="zoomLabel">1.0×</strong></label><input id="zoom" type="range" min="1" max="10" step="0.1" value="1">
<label for="exposure">Exposure compensation <strong id="exposureLabel">0</strong></label><input id="exposure" type="range" min="-3" max="3" step="1" value="0">
<label for="whitebalance">White balance</label><select id="whitebalance"><option value="auto">Auto</option><option value="daylight">Daylight</option><option value="cloudy">Cloudy</option><option value="incandescent">Incandescent</option><option value="fluorescent">Fluorescent</option></select>
<label for="resolution">Capture resolution</label><select id="resolution"><option value="0">480p · Compatibility</option><option value="1">720p · HD</option><option value="2" selected>1080p · Full HD</option><option value="3">1440p · 2K</option><option value="4">2160p · 4K (if supported)</option></select>
<label for="jpeg">JPEG quality <strong id="jpegLabel">85%</strong></label><input id="jpeg" type="range" min="50" max="95" step="5" value="85">
<p id="controlStatus" class="muted" aria-live="polite">Remote controls require camera support.</p></section></div>
<p class="muted">Controls affect the camera phone. This local page is unauthenticated; use only on a trusted network.</p>
<script>
const $=id=>document.getElementById(id),feed=$('feed'),status=$('status'),badge=$('badge');
feed.onload=()=>{badge.textContent='LIVE';status.textContent='Live · continuous stream';};
feed.onerror=()=>{badge.textContent='WAITING';status.textContent='Stream unavailable. Check that sharing is started.';};
async function command(cmd,value){const out=$('controlStatus');try{const q=new URLSearchParams({cmd});if(value!==undefined)q.set('value',value);const response=await fetch('/control?'+q.toString(),{cache:'no-store'});out.textContent=await response.text();}catch(e){out.textContent='Control failed. Check that sharing is active.';}}
$('switch').onclick=()=>command('switch');$('torch').onclick=()=>command('torch');$('focus').onclick=()=>command('focus');
$('zoom').oninput=()=> $('zoomLabel').textContent=Number($('zoom').value).toFixed(1)+'×';$('zoom').onchange=()=>command('zoom',$('zoom').value);
$('exposure').oninput=()=> $('exposureLabel').textContent=$('exposure').value;$('exposure').onchange=()=>command('exposure',$('exposure').value);
$('whitebalance').onchange=()=>command('whitebalance',$('whitebalance').value);
$('resolution').onchange=()=>command('quality',$('resolution').value);
$('jpeg').oninput=()=> $('jpegLabel').textContent=$('jpeg').value+'%';$('jpeg').onchange=()=>command('jpeg',$('jpeg').value);
$('refresh').onclick=()=>{badge.textContent='CONNECTING';feed.src='/stream?reconnect='+Date.now();};
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
