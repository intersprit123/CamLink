# Experimental hardware H.264 encoder test

This experiment is isolated on `test/camera2-fps-capabilities`. It does not change the default stream protocol.

## What it measures

When enabled, CameraX sends the same camera session to:
- the existing `ImageAnalysis` path, which continues producing JPEG frames for `/frame`;
- an additional `Preview` surface connected to Android `MediaCodec` using the AVC/H.264 MIME type.

The encoded H.264 access units are measured and discarded. They are **not** sent to Windows yet, so the current Windows viewer and `/frame` endpoint remain unchanged.

The encoder is selected from hardware AVC encoders that report support for the requested size (falling back to 720p or 480p) at 30 FPS or 15 FPS. Devices that do not report a usable hardware encoder return an error in `/stats`; this test does not silently switch to a software encoder.

## Run the test

1. Start CamLink camera sharing on the Android phone.
2. From a browser on the same trusted Wi-Fi network, enable the test:

   `http://PHONE_IP:8080/control?cmd=h264test&value=on`

3. Read the live metrics:

   `http://PHONE_IP:8080/stats`

4. Stop the test:

   `http://PHONE_IP:8080/control?cmd=h264test&value=off`

## Metrics

- `h264TestEnabled`: whether the H.264 experiment is active.
- `h264Codec` and `h264Hardware`: selected encoder name and hardware-acceleration classification.
- `h264Width`, `h264Height`, `h264TargetFps`: configured encoder output size and target rate.
- `h264Fps`: measured encoded output frames per second.
- `h264BitrateMbps`: measured encoded output bitrate.
- `h264AverageFrameKb`: average encoded access-unit size in KB.
- `h264LatencyMs`: approximate camera-surface timestamp to encoded-output callback age. Treat this as an end-to-end encoder-pipeline indicator, not a pure codec-only timing measurement.
- `h264Error`: setup or runtime error, if any.

## How to compare

Record a 30-second JPEG-only baseline first. Then enable H.264, wait a few seconds for the camera session to settle, and record another 30-second sample. Compare actual JPEG FPS/encode time alongside H.264 FPS/bitrate/average frame size. The two pipelines run concurrently during this experiment, so this test measures encoder capability and overhead; it is not yet a fair measurement of the final H.264-only streaming pipeline.

Do not merge this experiment into `main` until it has been tested on physical devices. No Windows H.264 decoder or H.264 network transport is included in this first benchmark.
