# Frigate Android

A native Android port of the [Frigate](https://frigate.video/) NVR — live RTSP camera viewing, motion‑triggered recording, and configuration, built to mirror the look and behavior of the desktop/Linux Frigate web UI.

Package: `com.zektopic.frigate` · Kotlin + Jetpack Compose · min SDK 26 (Android 8.0) · target/compile SDK 34.

---

## Features

**Live**
- Multi‑camera wall — adaptive grid (`LazyVerticalGrid`) with 16:9 tiles; a `NavigationRail` on tablets/landscape and a bottom bar on phones.
- Real RTSP playback via Media3/ExoPlayer, including **H.265/HEVC** streams.
- Honest per‑camera connection state — `LIVE` / `CONNECTING` / `RETRYING` / `OFFLINE`, with exponential‑backoff reconnect. No fake or simulated feeds, ever.
- Tap a tile for a fullscreen single‑camera view.
- Per‑camera **Detect / Record / Snapshots** toggles that actually gate behavior (see below).

**Review**
- Motion events with snapshot thumbnails, relative timestamps, and camera filter chips.
- In‑app playback of the recorded MP4 clip once it finalizes.

**System**
- Real device metrics (app CPU, memory, storage) and a per‑camera status/FPS/resolution table.

**Settings**
- **Camera manager + 3‑step wizard** (add/edit/delete) with a live RTSP connection test (`DESCRIBE` probe reporting reachability + codec).
- **Global configuration** — MQTT host/port, detection defaults, tracked objects.
- **Notifications** — master switch, alert‑on‑motion, and per‑camera mute (backed by DataStore, enforced by the service).
- **Storage** — recordings count / on‑disk size and a "clear all recordings" action.
- **About** — app/config version, camera & event counts, device info, storage path.
- **Advanced** — a raw YAML editor for the full Frigate‑style config.

**Recording**
- Motion‑triggered MP4 clips encoded on‑device (MediaCodec AVC via an EGL/GLES input surface), attached to their event for playback.

---

## How it works

- **`NvrService`** — a foreground service that owns the camera streams. It observes the config from Room, starts a `StreamIngester` per enabled camera, runs motion detection, records clips, and posts notifications (gated by the user's preferences).
- **`StreamIngester`** — drives an ExoPlayer RTSP session, extracts frames via an offscreen EGL pipeline, and manages the connection state machine + reconnect. For H.265 streams whose SDP omits the parameter sets, it sniffs the VPS/SPS/PPS in‑band and self‑heals on reconnect (see [H.265 notes](#h265-notes)).
- **Config is YAML‑first.** The Frigate‑style YAML in Room is the single source of truth; the UI (wizard, global form, live toggles) edits that YAML and the service hot‑reloads from it. The per‑camera Detect/Record/Snapshots switches map to `cameras.<id>.{detect,record,snapshots}.enabled` and are consumed by the service to gate event logging, recording, and snapshots independently — toggling one does not restart the stream.
- **Embedded web server** — a Ktor server runs inside the service for local API access.

### Cameras go through go2rtc

Point the app at your **go2rtc restreamer**, not directly at cameras. Direct camera URLs exhaust the cameras' 1–2 RTSP session slots (already held by Frigate/birdseye), so `DESCRIBE` succeeds but no RTP ever flows. Example seeded URL: `rtsp://<go2rtc-host>:8554/<stream_name>`.

### <a id="h265-notes"></a>H.265 notes & known limitation

H.265 cameras that omit `sprop-vps/sps/pps` from their SDP are handled by in‑band parameter‑set sniffing. Very high‑resolution HEVC streams from some vendors may still fail to decode on a given Android device even when they play on a desktop (which uses ffmpeg/browser decoding). The robust fix, mirroring real Frigate, is to expose a lower‑resolution or H.264 substream in go2rtc and point that camera's URL at it.

---

## Tech stack

Jetpack Compose · Hilt (DI) · Room · Media3/ExoPlayer 1.3.1 (RTSP) · Ktor (embedded server) · SnakeYAML · DataStore · MediaCodec/EGL (recording).

Toolchain: AGP 8.5.1 · Gradle 8.7 · Kotlin 1.9.22 · JDK 17.

## Build & run

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew testDebugUnitTest      # run unit tests
./gradlew installDebug           # install on a connected device
```

CI (`.github/workflows/android-build.yml`) runs lint, unit tests, and packages the debug APK on every push.

## First run

The app seeds a default config on first launch only (gated on the real DB, so your edits are never overwritten on restart). Open **Settings → Cameras** to add your own cameras with the wizard, or edit the YAML under **Advanced**.

## Project layout

```
app/src/main/java/com/zektopic/frigate/
├── MainActivity.kt              # Compose host, service binding, first-run seeding
├── service/NvrService.kt        # foreground service: streams, detection, recording, alerts
├── media/
│   ├── StreamIngester.kt        # RTSP ingest, frame extraction, H.265 sprop sniffing, state machine
│   ├── ClipRecorder.kt          # motion-triggered recording sessions
│   └── VideoClipEncoder.kt      # MediaCodec AVC + EGL MP4 encoder
├── ui/
│   ├── dashboard/DashboardScreen.kt   # Live / Review / System / Settings
│   └── settings/                # camera wizard, YAML editing, settings sections
└── data/                        # Room entities, DAO, YAML parser, preferences
```
