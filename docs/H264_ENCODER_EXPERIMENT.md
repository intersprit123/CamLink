# Hardware H.264 streaming prototype

This implementation is isolated on `feature/h264-streaming-mediacodec`. The existing JPEG `/frame` and `/stream` endpoints remain available as compatibility paths.

## What it measures

When enabled, CameraX sends frames to a `Preview` surface connected directly to Android `MediaCodec` using the AVC/H.264 MIME type. The service publishes codec configuration and encoded access units as an Annex-B byte stream at `/h264`. A bounded queue drops old access units when the viewer falls behind. JPEG conversion is skipped while H.264 is active to reduce CPU load; the JPEG endpoint remains available as a compatibility route but does not receive fresh frames during H.264 mode.

The encoder is selected from hardware AVC encoders that report support for the requested size (falling back to 720p or 480p) at 30 FPS or 15 FPS. Devices that do not report a usable hardware encoder return an error in `/stats`; this test does not silently switch to a software encoder.

## Run the test

1. Start CamLink camera sharing on the Android phone.
2. The Windows viewer's **H.264 · MediaCodec** mode enables the encoder automatically. To enable it manually, open this URL on the same trusted network:

   `http://PHONE_IP:8080/control?cmd=h264test&value=on`

3. The Annex-B stream is available at `http://PHONE_IP:8080/h264`. Read the live metrics at:

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

Record a 30-second JPEG-only baseline first. Then enable H.264, wait a few seconds for the camera session to settle, and record another 30-second sample. Compare actual JPEG FPS/encode time alongside H.264 FPS/bitrate/average frame size. H.264 mode skips JPEG conversion to reduce duplicate processing. Compare delivered Windows FPS with the Android `h264Fps` metric; they are different measurements.

This is an early single-viewer prototype. Validate Annex-B framing, reconnect behavior, encoder lifecycle, sustained thermal performance, and end-to-end latency on physical devices before merging into `main`. The desktop decoder uses PyAV/FFmpeg; hardware decoding depends on the Windows FFmpeg build and available codecs.
