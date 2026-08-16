<div align="center">

# Frigate Android

**A native Android NVR head for your [Frigate](https://frigate.video/) cameras.**

Live RTSP viewing, motion-triggered recording, and full YAML configuration — built to mirror
the behaviour of the Frigate web UI, and tuned to run on hardware you already own.

[![Android CI](https://github.com/Zektopic/frigate-android/actions/workflows/android-build.yml/badge.svg)](https://github.com/Zektopic/frigate-android/actions/workflows/android-build.yml)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.02-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)

</div>

---

## Overview

Frigate Android turns a spare phone or tablet into a self-contained NVR viewer. It ingests RTSP
streams directly, detects motion on-device, records clips to local storage, and exposes a small
HTTP API — all from a foreground service, with no cloud dependency.

It is designed around a hard constraint: **the device is probably slow**. Stream counts, encoder
limits and frame rates are derived from the hardware at runtime rather than assumed, and the app
sheds load under memory pressure instead of waiting to be killed.

> [!NOTE]
> This is an NVR **viewer and recorder**, not a detection engine. Motion detection is
> frame-differencing; object detection (person/car classification) runs on your Frigate server,
> not here. See [Scope and limitations](#scope-and-limitations).

---

## Features

### Live

| | |
|---|---|
| **Multi-camera wall** | Adaptive grid with 16:9 tiles — navigation rail on tablets and landscape, bottom bar on phones |
| **Real RTSP playback** | Media3/ExoPlayer with H.264 and H.265/HEVC, hardware decoding where available |
| **Honest stream state** | `LIVE` / `CONNECTING` / `RETRYING` / `OFFLINE` with exponential-backoff reconnect — no placeholder or simulated feeds, ever |
| **Fullscreen view** | Tap any tile for single-camera playback |
| **Per-camera gating** | Detect / Record / Snapshots toggles that genuinely gate behaviour without restarting the stream |

### Review

Motion events with snapshot thumbnails, relative timestamps and camera filter chips. Recorded MP4
clips play in-app once finalized.

### System

Live device metrics — CPU, memory, storage — plus a per-camera status table showing the selected
decoder, negotiated resolution and connection health.

### Settings

- **Camera wizard** — 3-step add/edit/delete with a live RTSP connection test (`DESCRIBE` probe reporting reachability and codec)
- **Global configuration** — MQTT host/port, detection defaults, tracked objects
- **Notifications** — master switch, alert-on-motion, per-camera mute, backed by DataStore and enforced by the service
- **Storage** — recording count, on-disk size, and bulk delete
- **Advanced** — raw YAML editor for the full Frigate-style config

---

## Performance on low-end hardware

Running seven 4 MP HEVC streams on a budget SoC does not work, and failing gracefully matters more
than pretending otherwise. The app measures what the device can do and stays inside it.

### Device budget

At startup the app derives a tier from total RAM, core count and Android's low-RAM flag, then
clamps it by the *smallest* advertised decoder instance limit across H.264/H.265 — the minimum,
because a device that runs eight AVC decoders but only two HEVC ones still falls over on an
all-HEVC camera set.

| Tier | Trigger | Streams | Encoders | Detect FPS |
|---|---|--:|--:|--:|
| `POTATO` | < 3 GB RAM, ≤ 4 cores, or low-RAM flag | 2 | 1 | 3 |
| `MODEST` | < 6 GB RAM | 4 | 2 | 5 |
| `CAPABLE` | everything else | 8 | 3 | 10 |

Cameras beyond the budget are declined with a log line rather than started and lost. The resolved
budget is reported by `GET /api/diag`.

### Detect substreams

The single largest saving. Frigate's own approach: detect on a low-resolution feed, record from the
high-resolution one. Decoding 2592×1520 purely to produce a 640×360 detection frame is roughly 17×
more work than necessary.

```yaml
cameras:
  front_camera:
    ffmpeg:
      inputs:
        - path: rtsp://go2rtc-host:8554/front_camera_sub
          roles: [detect]          # low-res — what the app ingests
        - path: rtsp://go2rtc-host:8554/front_camera
          roles: [record]          # full-res
```

An input tagged `[detect, record]` is one stream, not two. Single-input configs behave exactly as
before, so this is opt-in.

### Load shedding

`onTrimMemory` drops cached frames at `RUNNING_LOW` and additionally stops active recordings at
`RUNNING_CRITICAL`. Streams stay up; the process survives rather than being killed by the
low-memory killer. Stream startup is staggered so simultaneous `MediaCodec.configure()` calls
don't turn "not enough codecs" into "no codecs at all".

---

## Requirements

- **Android 8.0 (API 26)** or newer
- A **[go2rtc](https://github.com/AlexxIT/go2rtc) restreamer** — see below
- Cameras reachable on the same network as the device

### Point at go2rtc, not at cameras

Use your go2rtc restreamer rather than camera URLs directly. Cameras typically allow only 1–2
concurrent RTSP sessions, and Frigate already holds them — so `DESCRIBE` succeeds but no RTP ever
flows.

```yaml
- path: rtsp://<go2rtc-host>:8554/<stream_name>
```

### Requesting an H.264 transcode

go2rtc selects a transcoded source with a **codec query**, not a name suffix. Given a go2rtc
config like:

```yaml
go2rtc:
  streams:
    back_garden:
      - rtsp://user:pass@192.168.1.20:554/live/ch0
      - ffmpeg:back_garden#video=h264#hardware
```

request it as:

```yaml
- path: rtsp://<go2rtc-host>:8554/back_garden?video=h264   # ✅ selects the transcode
- path: rtsp://<go2rtc-host>:8554/back_garden_h264         # ❌ no such stream — DESCRIBE 404
```

Combining both tricks gives an H.264 detect substream in one source:

```yaml
front_camera_sub:
  - ffmpeg:front_camera#video=h264#width=640#height=360#hardware
```

---

## Architecture

```mermaid
flowchart LR
    subgraph svc["NvrService — foreground service"]
        SI["StreamIngester<br/><i>per camera</i>"]
        MD["MotionDetector"]
        CR["ClipRecorder"]
        WS["Ktor server"]
    end

    CAM["go2rtc / RTSP"] --> SI
    SI -->|"detect frames"| MD
    MD -->|"motion"| CR
    CR -->|"MP4 + snapshot"| FS[("External files")]
    SI --> WS
    DB[("Room<br/>YAML source of truth")] --> svc
    svc --> DB
    svc --> UI["Compose UI<br/>Live · Review · System · Settings"]
    WS --> API["HTTP API<br/>:8080"]
```

**Config is YAML-first.** The Frigate-style YAML in Room is the single source of truth. The wizard,
global form and live toggles all edit that YAML, and the service hot-reloads from it. Per-camera
switches map to `cameras.<id>.{detect,record,snapshots}.enabled`.

**Frame pipeline.** `StreamIngester` drives an ExoPlayer RTSP session and extracts frames through an
offscreen EGL pipeline, rendering the quad v-inverted so `glReadPixels` yields correct orientation
with a single bitmap allocation per frame.

**RTSP interception.** Some servers omit the SDP parameter sets that Media3 requires. The app
intercepts the RTSP stream to sniff VPS/SPS/PPS (H.265) or SPS/PPS (H.264) in-band from the RTP
data, then injects them on reconnect. It also rewrites relative `a=control:` attributes to absolute
URLs when the stream URL carries a query string — Media3 composes track URLs with `Uri.appendPath`,
which would otherwise place the path segment before the query and produce `SETUP 400`.

---

## Getting started

```bash
git clone git@github.com:Zektopic/frigate-android.git
cd frigate-android

./gradlew assembleDebug        # build the debug APK
./gradlew testDebugUnitTest    # run the unit suite
./gradlew installDebug         # install on a connected device
```

On first launch the app seeds a default config **once**, gated on the real database so your edits
are never overwritten on restart. Open **Settings → Cameras** to add your own, or edit the YAML
under **Advanced**.

### HTTP API

The embedded Ktor server listens on port `8080`.

| Endpoint | Purpose |
|---|---|
| `GET /api/config` | Current YAML configuration |
| `POST /api/config` | Replace configuration and restart streams |
| `GET /api/status` | Service and camera summary |
| `GET /api/events` | Recent motion events |
| `GET /api/events/{id}/snapshot` | JPEG snapshot for an event |
| `GET /api/diag` | Device budget, per-camera diagnostics, encoder counters |
| `GET /api/{camera}/live.mjpeg` | MJPEG stream at detect resolution |

> [!WARNING]
> The server binds all interfaces and has **no authentication**. `POST /api/config` can replace your
> entire camera configuration, and `GET /api/config` returns RTSP URLs including any embedded
> credentials. Run it only on a trusted network.

---

## Tech stack

**Runtime** — Jetpack Compose · Hilt · Room · Media3/ExoPlayer 1.3.1 · Ktor · SnakeYAML · DataStore · MediaCodec + EGL

**Toolchain** — AGP 8.5.1 · Gradle 8.7 · Kotlin 1.9.22 · JDK 17 · compile/target SDK 34 · min SDK 26

> Media3 is pinned to 1.3.1 deliberately: RTSP H.265 streams do not reach `STATE_READY` on 1.4.1
> with these cameras. Verify on-device before changing it.

---

## Project layout

```
app/src/main/java/com/zektopic/frigate/
├── MainActivity.kt                    # Compose host, service binding, first-run seeding
├── service/NvrService.kt              # foreground service: streams, detection, recording, alerts
├── media/
│   ├── StreamIngester.kt              # RTSP ingest, EGL frame extraction, sprop sniffing
│   ├── DevicePerformance.kt           # hardware tiering and budget
│   ├── DecoderPolicy.kt               # vendor-aware decoder ranking
│   ├── ClipRecorder.kt                # motion-triggered recording sessions
│   └── VideoClipEncoder.kt            # MediaCodec AVC + EGL MP4 encoder
├── ui/
│   ├── dashboard/DashboardScreen.kt   # Live · Review · System · Settings
│   ├── settings/                      # camera wizard, YAML editor, settings sections
│   └── zones/                         # zone drawing canvas
├── server/EmbeddedWebServer.kt        # Ktor HTTP API and web console
├── ai/MotionDetector.kt               # frame-differencing motion detection
└── data/                              # Room entities, DAO, YAML parser, preferences
```

---

## Scope and limitations

Being explicit about what this does **not** do:

- **No object detection.** `MotionDetector` is grayscale frame-differencing. Events are labelled
  `motion`; person/car classification happens on your Frigate server.
- **No continuous recording.** Motion-triggered clips only, capped at 60 s each.
- **MQTT and tracked-object settings persist but are not yet consumed.**
- **Zones** have a drawing canvas that is not yet wired into detection.
- **The web console is MJPEG** at detect resolution — no WebRTC or MSE.
- **No authentication** on the HTTP API (see warning above).

---

## Contributing

CI runs lint, the unit suite and a debug APK build on every push to `main` and `feature/**`, and on
every pull request to `main`. Unit tests and the build are the gates; lint is advisory.

Please make sure `./gradlew testDebugUnitTest assembleDebug` passes locally before opening a PR, and
avoid committing logs, keystores or camera credentials — `.gitignore` covers the usual suspects.
