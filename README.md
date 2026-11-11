# SmartCast Live + Interpreter — Opus prototype v0.2

This project is an Android engineering prototype. The Live audio path now uses Opus-compressed voice frames instead of sending raw PCM over the Wi-Fi network.

## SmartCast Live audio configuration

- Codec: Opus (Concentus pure-Java implementation)
- Sample rate: 48,000 Hz
- Channels: mono
- Bitrate target: 32,000 bits/second
- Frame duration: 20 ms (960 samples per frame)
- Transport: UDP multicast on `239.10.10.10:50005`
- Packet header: magic, protocol version, flags, session ID, sequence number, capture timestamp, payload length
- Receiver: validates packet header, decodes Opus, and uses Opus packet-loss concealment for up to five missing 20 ms frames

The Concentus library supplies the Opus encoder and decoder. It is a pure Java implementation, which is convenient for this first Android prototype. Performance must be measured on the actual Android devices; a native libopus integration may be considered later if CPU usage is too high.

## What is implemented in this prototype

- Android host and receiver controls
- Microphone capture at 48 kHz mono PCM
- Opus encoding at a configured 32 kbps target
- UDP multicast transmission of compressed Opus packets
- Packet header with session ID and sequence number
- Opus decoding and audio playback
- Basic missing-packet concealment using the Opus decoder
- GitHub Actions workflow to build a debug APK
- Interpreter product area as a UI/architecture placeholder

## What is not implemented or proven yet

- No measured maximum receiver count; 5, 10, 20, 50 and 100 receivers have not been load-tested
- No true adaptive jitter buffer or packet reordering buffer yet
- No robust host discovery, QR-code joining, unicast fallback, or automatic reconnection
- No foreground service / background-playback lifecycle handling
- No production signing, security, encryption, or release hardening
- No offline speech recognition, machine translation, or text-to-speech engine; Interpreter remains a planned module
- No measured latency, battery use, codec CPU load, or audio-quality results yet

The receiver decodes packets in arrival order. It can conceal a short run of missing frames, but it does not yet reorder delayed packets. The capture timestamp is currently carried in the packet for future diagnostics; it is not yet used for synchronized clock-based latency measurement.

## Build with GitHub Actions

1. Extract this ZIP.
2. Upload the **contents** of the extracted folder to the root of the private repository `SmartCast-Live-Offline` (not the ZIP file itself).
3. Confirm that `.github/workflows/android-build.yml` is in the repository at that exact path.
4. Open GitHub → Actions → **Build SmartCast APK**.
5. Run the workflow, or push the files to `main` / `master`.
6. When the run succeeds, download the `SmartCast-Live-APK` artifact and install `app-debug.apk` on the test phones.

## First test

1. Connect two Android phones to the same Wi-Fi network.
2. Confirm that the router allows multicast and client-to-client traffic. Some guest networks and hotspots block multicast.
3. Install the same APK on both phones.
4. On the host phone, grant microphone permission and tap **Start broadcasting**.
5. On the receiver phone, tap **Join as receiver**.
6. Test with headphones first to prevent acoustic feedback.
7. Record any build error, audio delay, dropouts, and phone model / Android version.

Test only one receiver first. Then repeat with 2, 5 and 10 receivers. Do not advertise a receiver capacity until it has been measured under sustained use.

## Bandwidth estimate

At a nominal 32 kbps Opus target, audio payload is about 4 KB/s per stream before UDP/IP and SmartCast packet-header overhead. This is a target bitrate, not a guarantee that every 20 ms Opus packet has exactly the same size. Multicast sends one network stream from the host; actual Wi-Fi airtime and receiver limits still depend on the access point, interference, and device behaviour.

## Licence note

Concentus is distributed under the BSD-3-Clause licence. Review the upstream licence and retain required notices when redistributing the application.
