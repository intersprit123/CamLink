# CamLink Desktop Viewer

A small Windows/Linux/macOS desktop viewer for the CamLink Android camera server.

## Requirements

- Python 3.10+
- Python Tk support (included with standard Python on Windows)
- Pillow

## Run

From this folder:

```bash
python -m pip install -r requirements.txt
python camlink_desktop.py
```

1. Connect the computer and camera phone to the same trusted Wi-Fi network.
2. On the phone, open CamLink → Share this phone's camera → Start camera sharing.
3. Enter the IP address shown on the phone into CamLink Desktop, then click Connect.

This viewer uses the current CamLink HTTP JPEG-frame endpoint on port 8080. It is a prototype, not encrypted, and should only be used on a trusted local network. Do not port-forward the camera server to the internet.
