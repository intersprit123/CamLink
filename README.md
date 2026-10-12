# CamLink 📱

CamLink is an Android camera-sharing prototype. A camera phone can stream to the built-in viewer, another phone's browser, or the CamLink Desktop viewer over a trusted local network.

## V1 features

- CameraX live preview and JPEG frame endpoint on port 8080.
- Front/rear camera toggle.
- **Local website:** when camera sharing is running, open `http://CAMERA_PHONE_IP:8080/` in any device browser on the same local network. The page includes remote front/rear switching, flashlight toggle, and zoom slider (where supported).
- Android viewer: enter the camera phone's local IP address.
- GitHub Actions builds a debug APK automatically.

The viewer refreshes JPEG frames; this is not yet a smooth, low-latency video call. CamLink uses a camera foreground service with an ongoing notification so the stream can continue when you switch apps or lock the screen. Android may still stop it due to battery restrictions, force-stop, or manufacturer-specific background limits.

## Build the APK

1. Open the **Actions** tab in this repository.
2. Select **Build CamLink APK** and wait for a successful run.
3. Open the successful run and download the **CamLink-debug-apk** artifact.
4. Extract the ZIP to get `app-debug.apk`, then install it on the Android phone(s).

## Use the local website

1. Connect the camera phone and the viewing phone/computer to the same Wi-Fi network. Alternatively, turn on the camera phone's hotspot and connect the viewer device to that hotspot; whether this works depends on Android/network routing.
2. On the camera phone, open CamLink → **Share this phone's camera** → grant camera permission → **Start camera sharing**.
3. Note the IP address shown by CamLink.
4. On the other phone, open its browser and enter `http://<camera-phone-IP>:8080/`, replacing the example with the IP shown in CamLink. Example: `http://192.168.1.12:8080/`.
5. The page should show the live camera feed. Keep sharing running on the camera phone.

The Android in-app viewer is also available from **View another phone**.

If the page does not load, check that both devices are on the same network, the network allows devices to communicate with each other, and no VPN/firewall blocks local traffic. Some guest Wi-Fi networks isolate clients. On some phone hotspots, connected clients may not be able to reach each other.

## Privacy and limitations

- The web page is hosted on the camera phone; no cloud server is required.
- V1 has no pairing code or encryption. Anyone who can reach port 8080 on that local network may be able to view the camera while sharing is active. Use only on a trusted network; do not expose or port-forward this port to the internet.
- Camera permission is required. The camera phone must grant camera permission and show the ongoing foreground-service notification while sharing. You can switch apps or lock the screen, but Android battery policies may still stop the service.
- This is a development build, not a production-ready security-reviewed app.
- Secure pairing, HTTPS/authentication, improved hotspot compatibility, and smoother video are planned for later versions. Remote camera controls are available in the local website, subject to camera hardware support.
