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
> This is an NVR **viewer and recorder**, not a detection engine. Motion detection is Frigate's own
> algorithm, ported; object detection (person/car classification) runs on your Frigate server, not
> here. See [Scope and limitations](#scope-and-limitations).

<p align="center">
  <img src="docs/screenshots/wide-live.png" width="720" alt="Live dashboard on a tablet">
</p>

---

## Features

The interface follows the Frigate web UI page for page: its colour tokens (dark and light), its
Inter typeface, its logo and its layout — a 52 dp sidebar with a status bar on a tablet or in
landscape, a bottom bar on a phone.

| Live | Review | System |
|---|---|---|
| ![Live](docs/screenshots/phone-live.png) | ![Review](docs/screenshots/phone-review.png) | ![System](docs/screenshots/phone-system.png) |

### Live

| | |
|---|---|
| **Camera dashboard** | Rounded tiles with name chips, a pulsing dot on motion and a red outline while an alert is open; grid or list layout; recent unreviewed alerts in a strip above |
| **Camera groups** | All Cameras, or Birdseye: only the cameras that see something right now |
| **Camera view** | Back and History, Detect / Record / Snapshots toggles, pinch to zoom, stream stats, and a debug overlay drawing motion boxes, zones and masks |
| **Real RTSP playback** | Media3/ExoPlayer with H.264 and H.265/HEVC, hardware decoding where available |
| **Honest stream state** | `LIVE` / `CONNECTING` / `RETRYING` / `OFFLINE`, never a placeholder feed. Cameras that fail together reconnect staggered rather than all at once |

### Review

Alerts, Detections and Motion, as in Frigate. Thumbnails show the camera and the zones the motion fell
in, turn green once reviewed, and open a player for the clip. Filter by camera, by day and by
reviewed state; long-press to select several and mark or delete them; a timeline down the side
scrolls the grid to a time. Opening an item marks it reviewed.

### System

General (detection speed, CPU and memory graphs, hardware and performance budget), Storage (usage and
a per-camera breakdown) and Cameras (status, resolution and decoder per camera, frame-rate and
detection graphs). Every number is measured; nothing is a placeholder.

### Settings

- **UI settings** — dark mode (system, light, dark), camera name chips, dashboard layout, 24-hour time
- **Camera management** — add, edit, disable or remove cameras through a 3-step wizard with a live RTSP connection test and an optional detect substream
- **Detect / Motion** — defaults for every camera
- **Motion tuner** — threshold, contour area and contrast per camera, with the motion boxes drawn live
- **Masks / Zones** — draw zones and motion masks on the camera picture
- **Storage** — usage, a folder picker that sends recordings anywhere the device can write (SD cards and USB drives included), bulk delete
- **Performance** — low-resource mode and the concurrent-stream cap
- **Notifications** — master switch, alerts, per-camera mute
- **Configuration editor** — the whole YAML, with Copy and Save

## Motion, zones and review

Motion detection is Frigate's `ImprovedMotionDetector`, with the pixel pipeline from the
`frigate-motion-rs` crate in the [Frigate fork](https://github.com/Zektopic/frigate): contrast
stretch, mask, blur, difference against a running-average background, threshold, dilate, and regions
filtered by area. The `motion:` keys mean what they mean in Frigate:

```yaml
motion:
  threshold: 30          # per-pixel luma change, 1-255
  contour_area: 10       # smallest region that counts, in motion-frame pixels
  improve_contrast: true
  lightning_threshold: 0.8
  mask:
    - 0,0,0.35,0,0.35,0.08,0,0.08   # e.g. a timestamp overlay

cameras:
  front_door:
    zones:
      porch:
        coordinates: 0.05,0.55,0.55,0.5,0.6,0.95,0.05,0.95

review:
  alerts:
    required_zones: [porch]    # motion elsewhere is filed as a detection
```

Motion is attributed to a zone when the bottom-centre of its box falls inside, as Frigate does for
objects. One review item spans one stretch of activity. Only alerts notify.

> [!IMPORTANT]
> Earlier versions read `motion.threshold` as a *percentage of pixels*, and the wizard wrote values
> of 1–50. On first start, values of 50 or less are removed so the Frigate default applies; values
> above 50 are kept.

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
don't turn "not enough codecs" into "no codecs at all", and when several streams drop together
(Wi-Fi, a go2rtc restart) their reconnects are staggered too.

### Low-resource mode

The fork's "potato PC" switch. It pins the budget to the `POTATO` tier whatever the hardware probe
decided, for a device that throttles or is short of memory. Also under **Settings → Performance**.

```yaml
low_resource:
  enabled: true
android:
  max_concurrent_streams: 3   # optional: override the measured stream cap
```

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

**Config is YAML-first.** The Frigate-style YAML in Room is the single source of truth. Every
settings page edits that YAML, changing only the keys it is about, and the service hot-reloads from
it. Per-camera switches map to `cameras.<id>.{detect,record,snapshots}.enabled`.

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
are never overwritten on restart. Open **Settings → Camera management** to add your own, or edit the
YAML in the **Configuration editor** (gear menu).

### Screenshots

`docs/screenshots/` is rendered on the JVM with Robolectric, so a UI change can be looked at
without a device:

```bash
./gradlew testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'
```

### HTTP API

The embedded Ktor server listens on port `8080`.

| Endpoint | Purpose |
|---|---|
| `GET /` | A small read-only console in Frigate's colours |
| `GET /api/config` | Current YAML configuration, credentials masked |
| `POST /api/config` | Replace the configuration and restart streams |
| `GET /api/status` | Service and camera summary |
| `GET /api/stats` | CPU, memory and per-camera frame and detection rates |
| `GET /api/events?limit=` | Recent review items with severity, zones and reviewed state |
| `GET /api/events/{id}/snapshot` | JPEG snapshot for an item |
| `GET /api/diag` | Device budget, per-camera diagnostics, encoder counters |
| `GET /api/{camera}/live.mjpeg` | MJPEG stream at detect resolution |

Stream credentials are masked (`rtsp://*:*@host`) everywhere they leave the app: the API, diagnostics
and logs. A config fetched from `GET /api/config`, edited and sent back keeps its passwords:
`POST` puts the stored credentials back behind every URL whose host and path did not change.

> [!WARNING]
> The server binds all interfaces and has **no authentication**. `POST /api/config` can replace your
> entire camera configuration. Run it only on a trusted network.

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
├── service/
│   ├── NvrService.kt                  # foreground service: streams, motion, events, recording, alerts
│   └── SystemStats.kt                 # counters to per-interval rates for the System page
├── ai/MotionDetector.kt               # Frigate's motion detector, ported
├── media/
│   ├── StreamIngester.kt              # RTSP ingest, EGL frame extraction, sprop sniffing
│   ├── ReconnectCoordinator.kt        # staggers reconnects when cameras fail together
│   ├── DevicePerformance.kt           # hardware tiering, budget, low-resource mode
│   ├── DecoderPolicy.kt               # vendor-aware decoder ranking
│   ├── ClipRecorder.kt                # motion-triggered recording sessions
│   └── VideoClipEncoder.kt            # MediaCodec AVC + EGL MP4 encoder
├── data/                              # Room, YAML parsing, zones and review rules, storage
├── server/EmbeddedWebServer.kt        # Ktor HTTP API and web console
├── util/CredentialRedactor.kt         # masks credentials in URLs
└── ui/
    ├── AppShell.kt                    # sidebar, bottom bar, status bar, gear menu
    ├── theme/                         # Frigate's colour tokens and Inter
    ├── components/                    # buttons, chips, toggles, panels
    ├── live/  review/  system/        # the pages
    ├── settings/                      # settings pages, camera wizard, YAML editing
    └── config/                        # configuration editor
```

---

## Scope and limitations

Being explicit about what this does **not** do:

- **No object detection.** Events are labelled `motion`; person/car classification happens on your
  Frigate server. Review items are alerts by default; use `review.alerts.required_zones` to file
  motion outside chosen zones as detections.
- **No continuous recording.** Motion-triggered clips only, capped at 60 s each. The Review page's
  Motion tab shows the cameras live rather than scrubbing recorded motion.
- **MQTT settings persist but are not consumed.** Nothing is published.
- **The web console is MJPEG** at detect resolution — no WebRTC or MSE.
- **No authentication** on the HTTP API (see warning above).

---

## Contributing

CI runs lint, the unit suite and a debug APK build on every push to `main` and `feature/**`, and on
every pull request to `main`. Unit tests and the build are the gates; lint is advisory.

Please make sure `./gradlew testDebugUnitTest assembleDebug` passes locally before opening a PR, and
avoid committing logs, keystores or camera credentials — `.gitignore` covers the usual suspects.
