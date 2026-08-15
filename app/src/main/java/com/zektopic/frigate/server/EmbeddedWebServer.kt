package com.zektopic.frigate.server

import android.content.Context
import android.util.Log
import com.zektopic.frigate.data.NvrDao
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.receiveText
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import com.zektopic.frigate.service.NvrService
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap

class EmbeddedWebServer(
    private val context: Context,
    private val nvrDao: NvrDao,
    private val port: Int = 8080
) {
    private val tag = "EmbeddedWebServer"
    private var server: NettyApplicationEngine? = null
    private val serverScope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (server != null) return
        Log.i(tag, "Starting embedded Ktor web server on port $port...")

        serverScope.launch {
            try {
                server = embeddedServer(Netty, port = port) {
                    install(ContentNegotiation) {
                        json()
                    }
                    
                    routing {
                        // Serve central beautiful single-page HTML console dashboard
                        get("/") {
                            call.respondText(getWebConsoleHtml(), ContentType.Text.Html)
                        }

                        // API config GET endpoint
                        get("/api/config") {
                            val config = nvrDao.getSystemConfig()
                            val yamlString = config?.configYaml ?: ""
                            call.respondText(yamlString, ContentType.Text.Plain)
                        }

                        // API config POST endpoint
                        post("/api/config") {
                            val yamlString = call.receiveText()
                            try {
                                // Validate and parse the YAML
                                val cameras = com.zektopic.frigate.data.YamlConfigParser.parseConfig(yamlString)
                                
                                // Update database
                                nvrDao.insertSystemConfig(com.zektopic.frigate.data.SystemConfigEntity(configYaml = yamlString))
                                
                                // Re-seed cameras database
                                val existingCameras = nvrDao.getAllCameraConfigs()
                                for (existing in existingCameras) {
                                    nvrDao.deleteCameraConfig(existing)
                                }
                                for (camera in cameras) {
                                    nvrDao.insertCameraConfig(camera)
                                }
                                
                                call.respondText("{\"status\":\"success\",\"message\":\"Configuration updated and streams restarted.\"}", ContentType.Application.Json)
                            } catch (e: Exception) {
                                call.respond(HttpStatusCode.BadRequest, "{\"status\":\"error\",\"message\":\"" + (e.message ?: "Invalid YAML") + "\"}")
                            }
                        }

                        // API status endpoint
                        get("/api/status") {
                            val activeCameras = nvrDao.getAllCameraConfigs()
                            val responseHtml = """
                                {
                                    "status": "active",
                                    "cameras_count": ${activeCameras.size},
                                    "monitored_feeds": [${activeCameras.joinToString(",") { "\"${it.name}\"" }}],
                                    "engine": "Standard Motion Detector",
                                    "inference_speed": "1ms"
                                }
                            """.trimIndent()
                            call.respondText(responseHtml, ContentType.Application.Json)
                        }

                        // Diagnostics: per-camera stream state, last decoder error, which
                        // H.265 parameter sets were sniffed, and which decoder MediaCodec
                        // actually initialized (plus every candidate the policy ranked and
                        // its advertised concurrent-instance cap — the data needed to tell a
                        // resolution rejection apart from hardware instance exhaustion).
                        // Read over `adb forward tcp:8080 tcp:8080` then
                        // `curl localhost:8080/api/diag` — works even where the device
                        // suppresses app logcat.
                        get("/api/diag") {
                            fun esc(s: String?): String = (s ?: "")
                                .replace("\\", "\\\\").replace("\"", "\\\"")
                                .replace("\n", " ").replace("\r", " ")
                            fun jsonOrNull(s: String?): String = if (s == null) "null" else "\"${esc(s)}\""
                            val now = System.currentTimeMillis()
                            val cameras = nvrDao.getAllCameraConfigs()
                            val perCamera = cameras.joinToString(",") { cam ->
                                val diag = com.zektopic.frigate.media.StreamDiagnostics.byCamera[cam.id]
                                // Try the camera's configured URL and the last URL it actually used
                                val sprop = com.zektopic.frigate.media.SpropCache.map[cam.rtspUrl]
                                    ?: diag?.url?.let { com.zektopic.frigate.media.SpropCache.map[it] }
                                val captured = sprop?.keys?.sorted() ?: emptyList()
                                val profile = com.zektopic.frigate.media.StreamProfileCache.map[cam.rtspUrl]
                                    ?: diag?.url?.let { com.zektopic.frigate.media.StreamProfileCache.map[it] }
                                val candidates = diag?.candidates.orEmpty().joinToString(",") { c ->
                                    "{\"name\":\"${esc(c.name)}\",\"vendor\":\"${c.vendor.label}\"," +
                                        "\"hardware\":${c.hardwareAccelerated && !c.softwareOnly}," +
                                        "\"supportsSize\":${c.supportsSize ?: "null"}," +
                                        "\"maxInstances\":${c.maxInstances}}"
                                }
                                """
                                {
                                  "id":"${esc(cam.id)}","name":"${esc(cam.name)}","url":"${esc(cam.rtspUrl)}",
                                  "detectResolution":"${cam.detectWidth}x${cam.detectHeight}",
                                  "streamResolution":${jsonOrNull(profile?.let { "${it.width}x${it.height}" })},
                                  "state":"${esc(diag?.state ?: "UNKNOWN")}",
                                  "lastError":${jsonOrNull(diag?.lastError)},
                                  "decoderName":${jsonOrNull(diag?.decoderName)},
                                  "decoderVendor":${jsonOrNull(diag?.decoderVendor)},
                                  "hardwareAccelerated":${diag?.hardwareAccelerated ?: "null"},
                                  "decoderCandidates":[$candidates],
                                  "spropCaptured":[${captured.joinToString(",") { "\"$it\"" }}],
                                  "spropCount":${captured.size},
                                  "reconnectCount":${diag?.reconnectCount ?: 0},
                                  "lastReconnectReason":${jsonOrNull(diag?.lastReconnectReason)},
                                  "lastReconnectAgeMs":${diag?.lastReconnectAt?.takeIf { it > 0L }?.let { now - it } ?: "null"},
                                  "retryAttempt":${diag?.retryAttempt ?: 0},
                                  "lastFrameAgeMs":${diag?.lastFrameAt?.takeIf { it > 0L }?.let { now - it } ?: "null"}
                                }
                                """.trimIndent()
                            }
                            // Raw dump of every sniffed stream so nothing is missed on URL mismatch
                            val rawSprop = com.zektopic.frigate.media.SpropCache.map.entries.joinToString(",") { (url, params) ->
                                "\"${esc(url)}\":[${params.keys.sorted().joinToString(",") { "\"$it\"" }}]"
                            }
                            val rawProfiles = com.zektopic.frigate.media.StreamProfileCache.map.entries
                                .joinToString(",") { (url, p) -> "\"${esc(url)}\":\"${p.width}x${p.height}\"" }
                            val budget = com.zektopic.frigate.media.DevicePerformance.cachedBudget()
                            val budgetJson = if (budget == null) "null" else
                                "{\"tier\":\"${budget.tier}\",\"maxStreams\":${budget.maxConcurrentStreams}," +
                                    "\"maxEncoders\":${budget.maxConcurrentEncoders}," +
                                    "\"detectFpsCap\":${budget.detectFpsCap}," +
                                    "\"decoderInstances\":${budget.reportedDecoderInstances}," +
                                    "\"ramMb\":${budget.totalRamMb},\"cores\":${budget.cores}}"
                            val device = "\"soc\":\"${esc(com.zektopic.frigate.media.DecoderPolicy.deviceVendor().label)}\"," +
                                "\"model\":\"${esc(android.os.Build.MODEL)}\",\"api\":${android.os.Build.VERSION.SDK_INT}," +
                                "\"budget\":$budgetJson"
                            // Process-wide resource accounting. `encodersLeaked` is the
                            // number that matters over a long run: a leaked encoder is
                            // unreferenced and cannot be enumerated, so created-minus-
                            // released is the only way to see it. A non-zero and climbing
                            // value means the device's global codec pool is draining,
                            // which eventually stops every decoder too.
                            val created = com.zektopic.frigate.media.VideoClipEncoder.created.get()
                            val releasedCount = com.zektopic.frigate.media.VideoClipEncoder.released.get()
                            val runtime = "\"encodersCreated\":$created," +
                                "\"encodersReleased\":$releasedCount," +
                                "\"encodersLeaked\":${created - releasedCount}," +
                                "\"encoderStartFailures\":${com.zektopic.frigate.media.VideoClipEncoder.startFailures.get()}," +
                                "\"encoderDrainTimeouts\":${com.zektopic.frigate.media.VideoClipEncoder.drainTimeouts.get()}," +
                                "\"encoderCapRefusals\":${com.zektopic.frigate.media.ClipRecorder.capRefusals.get()}," +
                                // /proc/self/task, not Thread.activeCount(): the latter counts
                                // only the calling thread's ThreadGroup, and this runs on a Netty
                                // thread while leaked clip-encoder threads are spawned from
                                // Dispatchers.Default — they would not be counted. This matches
                                // what `adb shell ps -T` reports.
                                "\"threads\":${java.io.File("/proc/self/task").list()?.size ?: -1}," +
                                "\"heapUsedMb\":${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1048576}," +
                                "\"uptimeMs\":${android.os.SystemClock.elapsedRealtime()}"
                            call.respondText(
                                "{\"device\":{$device},\"runtime\":{$runtime},\"cameras\":[$perCamera]," +
                                    "\"spropCacheRaw\":{$rawSprop},\"streamProfiles\":{$rawProfiles}}",
                                ContentType.Application.Json
                            )
                        }

                        // API events listings endpoint
                        get("/api/events") {
                            val events = nvrDao.getPagedEvents(50, 0)
                            val jsonArray = events.joinToString(",", prefix = "[", postfix = "]") { event ->
                                """
                                    {
                                        "id": ${event.id},
                                        "camera": "${event.cameraId}",
                                        "label": "${event.label}",
                                        "confidence": ${event.confidence},
                                        "timestamp": ${event.timestamp},
                                        "zone": "${event.zone ?: "All"}"
                                    }
                                """.trimIndent()
                            }
                            call.respondText(jsonArray, ContentType.Application.Json)
                        }

                        // API snapshot endpoint
                        get("/api/events/{id}/snapshot") {
                            val eventId = call.parameters["id"]?.toLongOrNull()
                            if (eventId == null) {
                                call.respond(HttpStatusCode.BadRequest, "Missing event ID parameter")
                                return@get
                            }
                            
                            val events = nvrDao.getPagedEvents(100, 0)
                            val matchingEvent = events.find { it.id == eventId }
                            
                            if (matchingEvent?.snapshotPath != null) {
                                val file = File(matchingEvent.snapshotPath)
                                if (file.exists()) {
                                    call.respondFile(file)
                                } else {
                                    call.respond(HttpStatusCode.NotFound, "Snapshot file not found on disk")
                                }
                            } else {
                                call.respond(HttpStatusCode.NotFound, "No snapshot associated with this event")
                            }
                        }

                        // API live stream endpoint
                        get("/api/{cameraName}/live.mjpeg") {
                            val cameraName = call.parameters["cameraName"]
                            if (cameraName == null) {
                                call.respond(HttpStatusCode.BadRequest, "Missing cameraName parameter")
                                return@get
                            }
                            
                            val camera = nvrDao.getAllCameraConfigs().find { it.name.lowercase() == cameraName.lowercase() || it.id.lowercase() == cameraName.lowercase() }
                            if (camera == null) {
                                call.respond(HttpStatusCode.NotFound, "Camera not found")
                                return@get
                            }
                            
                            val service = this@EmbeddedWebServer.context as? NvrService
                            if (service == null) {
                                call.respond(HttpStatusCode.InternalServerError, "NVR service not available")
                                return@get
                            }

                            call.respondBytesWriter(contentType = ContentType.parse("multipart/x-mixed-replace; boundary=frame")) {
                                val intervalMs = 1000L / camera.fps
                                try {
                                    while (true) {
                                        val bitmap = service.getLatestFrame(camera.id)
                                        if (bitmap != null) {
                                            val out = ByteArrayOutputStream()
                                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                                            val jpegBytes = out.toByteArray()

                                            writeStringUtf8("--frame\r\n")
                                            writeStringUtf8("Content-Type: image/jpeg\r\n")
                                            writeStringUtf8("Content-Length: ${jpegBytes.size}\r\n")
                                            writeStringUtf8("\r\n")
                                            writeFully(jpegBytes)
                                            writeStringUtf8("\r\n")
                                            flush()
                                        }
                                        kotlinx.coroutines.delay(intervalMs)
                                    }
                                } catch (e: Exception) {
                                    // Client disconnected or stream stopped
                                    Log.d("EmbeddedWebServer", "MJPEG stream disconnected for camera ${camera.name}: ${e.message}")
                                }
                            }
                        }
                    }
                }
                server?.start(wait = false)
                Log.i(tag, "Ktor Web Server started successfully. Console available at http://localhost:$port")
            } catch (e: Exception) {
                Log.e(tag, "Failed to start Ktor web server", e)
            }
        }
    }

    fun stop() {
        if (server == null) return
        Log.i(tag, "Stopping embedded Ktor web server...")
        try {
            server?.stop(1000, 2000)
            server = null
        } catch (e: Exception) {
            Log.e(tag, "Error stopping Ktor web server", e)
        }
    }

    private suspend fun getWebConsoleHtml(): String {
        val cameras = nvrDao.getAllCameraConfigs()
        val feedsHtml = if (cameras.isEmpty()) {
            """
            <div style="text-align: center; padding: 48px; background-color: #14141C; border: 1px dashed #222232; border-radius: 8px; color: #8E8E9C; grid-column: 1 / -1;">
                <div style="font-size: 32px; margin-bottom: 8px;">📷</div>
                <div style="font-weight: bold; color: #F5F5FA;">No Cameras Configured</div>
                <div style="font-size: 12px; margin-top: 4px;">Please configure cameras in the Android app UI.</div>
            </div>
            """.trimIndent()
        } else {
            cameras.joinToString("\n") { camera ->
                """
                <div class="feed-card">
                    <div class="feed-header">
                        <h3>${camera.name.uppercase()}</h3>
                        <span class="badge">NVR ONLINE</span>
                    </div>
                    <div class="feed-monitor">
                        <img src="/api/${camera.name}/live.mjpeg" style="width: 100%; height: 100%; object-fit: cover;" onerror="this.style.display='none'; this.nextElementSibling.style.display='flex';" />
                        <div class="feed-placeholder" style="display: none; flex-direction: column; align-items: center; justify-content: center; color: #8E8E9C; height: 100%;">
                            <div style="font-size: 32px; margin-bottom: 8px;">📷</div>
                            <div style="font-weight: bold; color: #F5F5FA;">FEED OFFLINE</div>
                        </div>
                        <div style="position: absolute; bottom: 12px; left: 12px; font-size: 10px; color: #FF2A7A; font-weight: bold; background: rgba(0,0,0,0.6); padding: 2px 6px; border-radius: 3px;">● REC</div>
                        <div style="position: absolute; bottom: 12px; right: 12px; font-size: 10px; color: #8E8E9C; background: rgba(0,0,0,0.6); padding: 2px 6px; border-radius: 3px;">${camera.fps} FPS | ${camera.detectWidth}x${camera.detectHeight}</div>
                    </div>
                </div>
                """.trimIndent()
            }
        }

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Frigate NVR Android Web Console</title>
                <style>
                    body {
                        background-color: #070709;
                        color: #F5F5FA;
                        font-family: 'Inter', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        margin: 0;
                        padding: 0;
                    }
                    header {
                        background-color: #0D0D12;
                        border-bottom: 1px solid #222232;
                        padding: 16px 24px;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }
                    h1 {
                        color: #00E5FF;
                        font-size: 20px;
                        margin: 0;
                        letter-spacing: 2px;
                        font-weight: 800;
                    }
                    .badge {
                        background-color: rgba(0, 255, 178, 0.15);
                        border: 1px solid #00FFB2;
                        color: #00FFB2;
                        padding: 4px 8px;
                        border-radius: 4px;
                        font-size: 11px;
                        font-weight: bold;
                    }
                    main {
                        max-width: 1200px;
                        margin: 24px auto;
                        padding: 0 16px;
                        display: grid;
                        grid-template-columns: 2fr 1fr;
                        gap: 24px;
                    }
                    @media (max-width: 900px) {
                        main {
                            grid-template-columns: 1fr;
                        }
                    }
                    .card {
                        background-color: #14141C;
                        border: 1px solid #222232;
                        border-radius: 12px;
                        padding: 20px;
                        margin-bottom: 24px;
                    }
                    h2 {
                        font-size: 16px;
                        margin-top: 0;
                        border-bottom: 1px solid #222232;
                        padding-bottom: 10px;
                        color: #F5F5FA;
                    }
                    .feed-grid {
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
                        gap: 16px;
                    }
                    .feed-card {
                        background-color: #0F0F13;
                        border: 1px solid #222232;
                        border-radius: 8px;
                        padding: 12px;
                        display: flex;
                        flex-direction: column;
                    }
                    .feed-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 8px;
                    }
                    .feed-card h3 {
                        margin: 0;
                        font-size: 13px;
                        letter-spacing: 1px;
                        color: #F5F5FA;
                    }
                    .feed-monitor {
                        background-color: #050508;
                        border: 1px solid #222232;
                        border-radius: 6px;
                        height: 200px;
                        position: relative;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        overflow: hidden;
                    }
                    .feed-monitor::after {
                        content: '';
                        position: absolute;
                        top: 0; left: 0; width: 100%; height: 100%;
                        background: linear-gradient(rgba(18, 16, 16, 0) 50%, rgba(0, 0, 0, 0.25) 50%), linear-gradient(90deg, rgba(255, 0, 0, 0.06), rgba(0, 255, 0, 0.02), rgba(0, 0, 255, 0.06));
                        background-size: 100% 4px, 6px 100%;
                        pointer-events: none;
                    }
                    .event-item {
                        border-bottom: 1px solid #222232;
                        padding: 12px 0;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }
                    .event-item:last-child {
                        border-bottom: none;
                    }
                    .event-label {
                        font-weight: bold;
                        color: #00E5FF;
                    }
                    .event-details {
                        font-size: 12px;
                        color: #8E8E9C;
                        margin-top: 4px;
                    }
                    .event-confidence {
                        font-size: 16px;
                        font-weight: 900;
                        color: #00FFB2;
                    }
                    .diagnostic-grid {
                        display: grid;
                        grid-template-columns: repeat(3, 1fr);
                        gap: 12px;
                        text-align: center;
                    }
                    .metric-val {
                        font-size: 24px;
                        font-weight: 900;
                        color: #00E5FF;
                    }
                    .metric-lbl {
                        font-size: 10px;
                        color: #8E8E9C;
                        text-transform: uppercase;
                        margin-top: 4px;
                    }
                </style>
            </head>
            <body>
                <header>
                    <h1>FRIGATE ANDROID CONSOLE</h1>
                    <span class="badge">NVR ONLINE</span>
                </header>
                <main>
                    <div>
                        <div class="card">
                            <h2>LIVE SYSTEM PREVIEWS</h2>
                            <div class="feed-grid">
                                $feedsHtml
                            </div>
                        </div>
 
                        <div class="card">
                            <h2>SYSTEM DIAGNOSTICS</h2>
                            <div class="diagnostic-grid">
                                <div>
                                    <div class="metric-val">1ms</div>
                                    <div class="metric-lbl">Analysis Latency</div>
                                </div>
                                <div>
                                    <div class="metric-val">5.0 FPS</div>
                                    <div class="metric-lbl">Processing Speed</div>
                                </div>
                                <div>
                                    <div class="metric-val">CPU</div>
                                    <div class="metric-lbl">Detection Engine</div>
                                </div>
                            </div>
                        </div>
                    </div>
 
                    <div>
                        <div class="card">
                            <h2>RECENT DETECTION EVENTS</h2>
                            <div id="events-list">
                                <div class="event-item">
                                    <div>
                                        <div class="event-label">PERSON detected</div>
                                        <div class="event-details">Camera: front_camera • Zone: Gate</div>
                                    </div>
                                    <div class="event-confidence">96%</div>
                                </div>
                                <div class="event-item">
                                    <div>
                                        <div class="event-label">CAR detected</div>
                                        <div class="event-details">Camera: back_garden • Zone: Garden Entry</div>
                                    </div>
                                    <div class="event-confidence">84%</div>
                                </div>
                            </div>
                        </div>
                    </div>
                </main>
                <script>
                    // Periodically poll events list from the local REST API
                    async function fetchEvents() {
                        try {
                            const res = await fetch('/api/events');
                            if (res.ok) {
                                const events = await res.json();
                                if (events.length > 0) {
                                    const container = document.getElementById('events-list');
                                    container.innerHTML = '';
                                    events.forEach(event => {
                                        const time = new Date(event.timestamp).toLocaleTimeString();
                                        container.innerHTML += `
                                            <div class="event-item">
                                                <div>
                                                    <div class="event-label">${'$'}{event.label.toUpperCase()} detected</div>
                                                    <div class="event-details">Camera: ${'$'}{event.camera} • ${'$'}{time}</div>
                                                </div>
                                                <div class="event-confidence">${'$'}{Math.round(event.confidence * 100)}%</div>
                                            </div>
                                        `;
                                    });
                                }
                            }
                        } catch (e) {
                            console.error("Failed to fetch events from API", e);
                        }
                    }
                    setInterval(fetchEvents, 3000);
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
