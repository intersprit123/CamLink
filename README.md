# CamLink 📱

CamLink is an Android-to-Android camera-sharing experiment. One phone acts as the camera; another phone views its feed over the same local Wi-Fi network.

## V1 status

- Camera phone: CameraX preview and JPEG frame endpoint on port 8080.
- Viewer phone: enter the camera phone's local IP address and connect.
- Front/rear camera toggle.
- GitHub Actions builds a debug APK automatically.

This is an early MVP. The viewer currently refreshes JPEG frames, so it is not yet a smooth, low-latency video call. Keep the camera phone awake while sharing.

## Build the APK

1. Open the **Actions** tab in this repository.
2. Select **Build CamLink APK** and wait for the workflow to finish.
3. Open the successful run and download the **CamLink-debug-apk** artifact.
4. Extract the ZIP to get `app-debug.apk`, then install it on both Android phones.

The first push to `main` should start the build automatically. You can also run it manually from Actions.

## Test on two phones

1. Install CamLink on both phones and connect them to the same trusted Wi-Fi network.
2. On Phone 1, open CamLink → **Share this phone's camera** → grant camera permission → **Start camera sharing**.
3. Note the IP address displayed by the app.
4. On Phone 2, open CamLink → **View another phone**, enter Phone 1's IP address (without `http://`), then tap **Connect to camera**.
5. To stop, return to Phone 1 and stop sharing.

If the connection fails, check that both phones are on the same Wi-Fi, that the router allows devices to communicate with each other, and that no VPN/firewall blocks local traffic. Some guest Wi-Fi networks isolate devices.

## Privacy and limitations

- Streaming is only intended for your local network; do not use it on a network you do not trust.
- V1 has no pairing code or encryption. Do not expose port 8080 to the internet or use this build for sensitive video.
- Camera permission is required. The camera phone should visibly remain in the CamLink app while sharing.
- This is a development build, not a production-ready security-reviewed app.
- Hotspot connection support, remote controls, secure pairing, and smoother video are planned for later versions.
