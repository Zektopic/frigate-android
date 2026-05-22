# Frigate NVR: Feature List and Android Porting Strategy

Frigate is a local-first, highly efficient NVR designed for real-time AI object detection. To build a native Android app that operates like Frigate, we must port its architectural principles and core features. Below is the comprehensive mapping of the original Frigate features and how we will implement them natively on Android.

---

## 1. Core Feature Matrix

| Feature | Original Frigate (Server/Docker) | Android Port (Native App) |
| :--- | :--- | :--- |
| **Video Ingestion** | FFmpeg decoding of RTSP streams | **ExoPlayer / LibVLC / Custom FFmpeg Wrapper** for RTSP, and **CameraX API** for local camera input. |
| **Motion Detection** | OpenCV pixel comparison (CPU-efficient pre-filter) | **Custom Frame Analyzer** using basic frame-differencing or a lightweight native OpenCV library to filter static scenes. |
| **AI Object Detection** | TensorFlow Lite / YOLO (Coral TPU, GPU, OpenVINO) | **TensorFlow Lite (TFLite) Android SDK** with GPU/NNAPI hardware acceleration delegate (using SSD-MobileNet or YOLO-tiny). |
| **Recording & Clips** | continuous or event-based recording via FFmpeg | **MediaMuxer / CameraX VideoCapture** or custom FFmpeg container writer storing MP4 clips in Android External/Internal storage. |
| **Live Viewing** | `go2rtc` for WebRTC, MSE, and RTSP re-streaming | **Jetpack Compose video player Canvas** rendering raw streams, and dynamic live feed grid. |
| **Zones & Masks** | Polygon coordinates defined in YAML configurations | **Interactive Canvas Overlay** in Compose allowing users to draw polygon zones/masks directly on the touch screen. |
| **Alerts & Integrations** | MQTT, Home Assistant Integration, Webhooks | **Local Android Notifications** with event snapshot previews, and a **Room Database** event log. |
| **Local Web Interface** | React-based web dashboard served via Nginx | **Embedded Ktor Web Server** running inside an Android Service, allowing network access to a local web console. |

---

## 2. Detailed Technical Architecture on Android

### A. Threading & Multiprocessing model
*   **Original**: Frigate uses multi-processing to separate stream decoding, motion detection, and object detection.
*   **Android Port**: We will utilize **Kotlin Coroutines** and background threading. The app will have a foreground **Android Service** to run the stream decoding, motion detection, and TFLite inference off the main thread, ensuring high performance and UI responsiveness.

```
+-----------------------------------------------------------------------------------+
|                              Foreground Android Service                           |
|                                                                                   |
|  +--------------------+      +--------------------+      +---------------------+  |
|  |   Video Decoder    | ---> |  Motion Detection  | ---> | AI Object Detection |  |
|  | (LibVLC / CameraX) |      | (Frame-differencing)|     | (TensorFlow Lite)   |  |
|  +--------------------+      +--------------------+      +---------------------+  |
|            |                                                        |             |
|            v                                                        v             |
|  +--------------------+                                  +---------------------+  |
|  |  Recording Worker  | <--------------------------------|   Event Trigger /   |  |
|  |   (MediaMuxer)     |                                  |   Room database     |  |
|  +--------------------+                                  +---------------------+  |
+-----------------------------------------------------------------------------------+
                                                                      |
                                                                      v
                                                           +---------------------+
                                                           | Jetpack Compose UI  |
                                                           |     (Dashboard)     |
                                                           +---------------------+
```

### B. AI Engine & Optimization
*   **Model**: We will use a pre-packaged **TensorFlow Lite model** (SSD MobileNet V2 or YOLOv8-tiny) optimized for mobile CPU/GPU.
*   **Acceleration**: Support both **GPU Delegate** (OpenCL/OpenGL) and **NNAPI Delegate** (Neural Networks API) to harness on-device NPUs.
*   **Precision**: Quantized INT8 or FP16 models to keep file sizes low and inference speeds fast (< 30ms per frame).

### C. Motion Pre-Filtering (The "Gatekeeper" Pattern)
*   To conserve battery and CPU resources, TFLite inference will *only* be executed when motion is detected.
*   A frame analyzer will compare the current frame with a baseline frame. If the pixel change ratio exceeds a configurable threshold, the frame is marked "active" and queued for TFLite analysis.

---

## 3. Implementation Phases & Git Branches

To ensure rigorous development loops, we will create dedicated branches for each development loop, test locally, and push to remote.

1.  **Phase 1: Project Setup & Modern UI Dashboard (`feature/setup-and-ui`)**
    *   Initialize Android project with Jetpack Compose.
    *   Build the premium UI dashboard, supporting dark/light mode, custom glassmorphism components, and camera feeds layout.
    *   Setup the Hilt dependency injection, Navigation, and Room database for storage.

2.  **Phase 2: Video Stream Ingestion (`feature/stream-ingestion`)**
    *   Implement RTSP stream player using LibVLC/ExoPlayer.
    *   Implement Local Camera input option using CameraX.
    *   Extract raw bitmaps/frames from the stream for processing.

3.  **Phase 3: Motion & Object Detection AI (`feature/ai-detection`)**
    *   Implement frame difference motion detection.
    *   Integrate TFLite with GPU/NNAPI delegation.
    *   Perform real-time detection, map bounding boxes, and draw them on screen.

4.  **Phase 4: Zones, Masks, and Event Triggers (`feature/zones-and-masks`)**
    *   Create an interactive canvas overlay to draw polygon zones.
    *   Implement event triggering (e.g. Person entering Zone A).
    *   Save event snapshots and video clips to storage.

5.  **Phase 5: Local Server & Web Console (`feature/web-server`)**
    *   Embed a Ktor HTTP server inside the foreground service.
    *   Create a local web dashboard to view events and streams from other browsers on the same WiFi.
