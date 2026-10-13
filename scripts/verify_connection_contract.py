#!/usr/bin/env python3
"""Static integration-contract checks for the Android camera host/viewers.

These checks catch broken wiring between UI actions, frame production, HTTP endpoints,
and viewer requests. They complement (but do not replace) a real device/network test.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/com/intersprit/camlink/MainActivity.kt").read_text(encoding="utf-8")
SERVICE = (ROOT / "app/src/main/java/com/intersprit/camlink/CameraStreamingService.kt").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")


def section(source: str, start: str, end: str) -> str:
    begin = source.find(start)
    if begin < 0:
        raise AssertionError(f"Missing section start: {start}")
    finish = source.find(end, begin + len(start))
    if finish < 0:
        raise AssertionError(f"Missing section end: {end}")
    return source[begin:finish]


checks = []

def check(label: str, condition: bool) -> None:
    if not condition:
        raise AssertionError(f"FAIL: {label}")
    checks.append(label)


host_preview = section(MAIN, "private fun localHostPreviewHtml()", "private fun makeRootScrollable()")
remote_viewer = section(MAIN, "private fun viewerHtml(host: String)", "private fun addButton(label: String")
embedded_viewer = section(MAIN, 'fun viewerPage() = """', "\nclass FrameServer(")
frame_route = section(MAIN, '"/frame" -> {', '"/", "/index.html" ->')
control_route = section(MAIN, '"/control" -> {', '"/frame" ->')

# Button -> app/service lifecycle.
check("camera-share button opens the host screen", "addButton(\"📷  Share this phone's camera\") { showHost() }" in MAIN)
check("host Start button invokes startHost", 'addButton("Start camera sharing")' in MAIN and "startHost(status)" in MAIN)
check("host starts the foreground camera service", "ContextCompat.startForegroundService(this, serviceIntent)" in MAIN)
check("viewer Connect button opens viewerHtml(host)", 'addButton("Connect to camera")' in MAIN and "viewerHtml(host)" in MAIN)
check("camera service is registered and internet/camera permissions exist",
      'android:name=".CameraStreamingService"' in MANIFEST and
      'android.permission.INTERNET' in MANIFEST and 'android.permission.CAMERA' in MANIFEST)

# Camera frames -> shared JPEG buffer -> HTTP /frame endpoint.
check("service starts FrameServer on port 8080", 'FrameServer(latestJpeg, ::handleWebControl)' in SERVICE and
      'NanoHTTPD("0.0.0.0", 8080)' in MAIN)
check("CameraX analyzer publishes JPEG frames", "latestJpeg.set(jpeg)" in SERVICE)
check("/frame serves the latest image/jpeg bytes", "val frame = latestFrame.get()" in frame_route and
      '"image/jpeg"' in frame_route and "ByteArrayInputStream(frame)" in frame_route)
check("/frame disables caching and allows viewer requests",
      '"Cache-Control", "no-store, no-cache, must-revalidate"' in frame_route and
      '"Access-Control-Allow-Origin", "*"' in frame_route)
check("HTTP server returns a clear startup response before first frame",
      "Response.Status.SERVICE_UNAVAILABLE" in frame_route and "Camera is starting" in frame_route)

# Every Android image consumer now polls /frame sequentially, without /stream clients.
for label, client in (("local host preview", host_preview), ("Android remote viewer", remote_viewer),
                      ("hosted web viewer", embedded_viewer)):
    check(f"{label} requests /frame", "/frame?t=" in client or 'src="/frame"' in client)
    check(f"{label} uses load/error-driven sequential polling", "function pollFrame()" in client and
          "feed.onload" in client and "feed.onerror" in client and "setTimeout(pollFrame" in client)
    check(f"{label} does not request /stream", "/stream" not in client)
check("remote viewer enables CORS for canvas snapshots", 'crossorigin="anonymous"' in remote_viewer and
      'crossorigin="anonymous"' in embedded_viewer)
check("server retains /stream only as a compatibility endpoint", '"/stream" ->' in MAIN)

# Remote controls -> HTTP query -> service command handlers.
check("/control reads cmd/value and delegates to the camera handler",
      'params["cmd"]' in control_route and 'params["value"]' in control_route and
      "controlHandler.invoke(command, value)" in control_route)
for command in ("switch", "torch", "focus", "zoom", "exposure", "whitebalance", "quality", "jpeg"):
    check(f"viewer sends {command} and service handles it",
          (f"'{command}'" in remote_viewer or f'\"{command}\"' in remote_viewer) and
          f'"{command}" ->' in SERVICE)
check("stats endpoint and viewer metrics are connected", '"/stats" ->' in MAIN and
      "statsJsonForViewer()" in MAIN and "/stats" in remote_viewer and "/stats" in embedded_viewer)

print(f"Android connection contract: PASS ({len(checks)} checks)")
for item in checks:
    print(f"  PASS  {item}")
