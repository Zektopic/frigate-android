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
import com.zektopic.frigate.util.CredentialRedactor
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap

class EmbeddedWebServer(
    private val context: Context,
    private val nvrDao: NvrDao,
    private val port: Int = 8080
) {
    private val tag = "EmbeddedWebServer"
    private val recordingStore = com.zektopic.frigate.data.RecordingStore(context)
    private var server: NettyApplicationEngine? = null
    private val serverScope = CoroutineScope(Dispatchers.IO)
    private val nvrService: NvrService? get() = context as? NvrService

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
                        get("/") {
                            call.respondText(webConsoleHtml(), ContentType.Text.Html)
                        }

                        // Credentials are masked on the way out. This endpoint has no
                        // authentication, and camera passwords are worth more than the
                        // convenience of reading them back over HTTP.
                        get("/api/config") {
                            val yamlString = nvrDao.getSystemConfig()?.configYaml ?: ""
                            call.respondText(CredentialRedactor.redact(yamlString), ContentType.Text.Plain)
                        }

                        post("/api/config") {
                            val received = call.receiveText()
                            try {
                                // A client that edits what GET returned sends the masks
                                // back; put the real credentials behind them again.
                                val stored = nvrDao.getSystemConfig()?.configYaml ?: ""
                                val yamlString = CredentialRedactor.restore(received, stored)
                                if (CredentialRedactor.containsMask(yamlString)) {
                                    call.respondText(
                                        errorJson("A stream URL still contains masked credentials (*:*). Send the real username and password for any URL you change."),
                                        ContentType.Application.Json, HttpStatusCode.BadRequest
                                    )
                                    return@post
                                }
                                val cameras = com.zektopic.frigate.data.YamlConfigParser.parseConfig(yamlString)
                                // One transaction: the service must never observe the
                                // camera table half-replaced, or it stops every stream.
                                nvrDao.applyConfig(
                                    com.zektopic.frigate.data.SystemConfigEntity(configYaml = yamlString),
                                    cameras
                                )
                                call.respondText(
                                    buildJsonObject {
                                        put("status", "success")
                                        put("message", "Configuration updated and streams restarted.")
                                    }.toString(),
                                    ContentType.Application.Json
                                )
                            } catch (e: Exception) {
                                call.respondText(
                                    errorJson(e.message ?: "Invalid YAML"),
                                    ContentType.Application.Json, HttpStatusCode.BadRequest
                                )
                            }
                        }

                        get("/api/status") {
                            val cameras = nvrDao.getAllCameraConfigs()
                            val states = nvrService?.streamStates?.value.orEmpty()
                            call.respondText(
                                buildJsonObject {
                                    put("status", "active")
                                    put("cameras_count", cameras.size)
                                    put("cameras_live", cameras.count { states[it.id] == com.zektopic.frigate.media.StreamState.LIVE })
                                    putJsonArray("monitored_feeds") { cameras.forEach { add(it.name) } }
                                    put("engine", "Motion detector")
                                }.toString(),
                                ContentType.Application.Json
                            )
                        }

                        // The same numbers the app's System page graphs.
                        get("/api/stats") {
                            val latest = nvrService?.stats?.value?.lastOrNull()
                            val states = nvrService?.streamStates?.value.orEmpty()
                            val cameras = nvrDao.getAllCameraConfigs()
                            call.respondText(
                                buildJsonObject {
                                    putJsonObject("service") {
                                        put("uptime", android.os.SystemClock.elapsedRealtime() / 1000)
                                        put("cpu_percent", latest?.cpuPercent)
                                        put("memory_percent", latest?.memoryPercent)
                                        put("heap_mb", latest?.heapMb)
                                        put("motion_ms", latest?.motionMs)
                                    }
                                    putJsonObject("cameras") {
                                        for (camera in cameras) {
                                            val stats = latest?.cameras?.get(camera.id)
                                            putJsonObject(camera.id) {
                                                put("name", camera.name)
                                                put("state", states[camera.id]?.name ?: "STOPPED")
                                                put("camera_fps", stats?.cameraFps)
                                                put("detection_fps", stats?.detectFps)
                                                put("skipped_fps", stats?.skippedFps)
                                                put("motion_ms", stats?.motionMs)
                                            }
                                        }
                                    }
                                }.toString(),
                                ContentType.Application.Json
                            )
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
                                  "id":"${esc(cam.id)}","name":"${esc(cam.name)}","url":"${esc(CredentialRedactor.redact(cam.rtspUrl))}",
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
                                "\"${esc(CredentialRedactor.redact(url))}\":[${params.keys.sorted().joinToString(",") { "\"$it\"" }}]"
                            }
                            val rawProfiles = com.zektopic.frigate.media.StreamProfileCache.map.entries
                                .joinToString(",") { (url, p) -> "\"${esc(CredentialRedactor.redact(url))}\":\"${p.width}x${p.height}\"" }
                            val budget = com.zektopic.frigate.media.DevicePerformance.cachedBudget()
                            // maxStreams is the device's own cap; effectiveMaxStreams is what
                            // runs after the config's android.max_concurrent_streams, if any.
                            val streamCap = com.zektopic.frigate.media.DevicePerformance.streamCapOverride
                            val budgetJson = if (budget == null) "null" else
                                "{\"tier\":\"${budget.tier}\",\"maxStreams\":${budget.maxConcurrentStreams}," +
                                    "\"maxStreamsOverride\":${streamCap ?: "null"}," +
                                    "\"lowResource\":${com.zektopic.frigate.media.DevicePerformance.lowResource}," +
                                    "\"effectiveMaxStreams\":${com.zektopic.frigate.media.DevicePerformance.effectiveMaxStreams(budget, streamCap)}," +
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
                            val activeEncoders = com.zektopic.frigate.media.ClipRecorder.activeSessions.get()
                            val runtime = "\"encodersCreated\":$created," +
                                "\"encodersReleased\":$releasedCount," +
                                "\"encodersActive\":$activeEncoders," +
                                // Subtract the ones legitimately mid-recording, or a healthy
                                // device recording two clips reports them as leaked.
                                "\"encodersLeaked\":${(created - releasedCount - activeEncoders).coerceAtLeast(0)}," +
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

                        get("/api/events") {
                            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 50
                            val events = nvrDao.getPagedEvents(limit, 0)
                            call.respondText(
                                buildJsonArray {
                                    for (event in events) addJsonObject {
                                        put("id", event.id)
                                        put("camera", event.cameraId)
                                        put("label", event.label)
                                        put("severity", event.severity)
                                        put("timestamp", event.timestamp)
                                        put("end_time", event.endTime)
                                        put("has_been_reviewed", event.hasBeenReviewed)
                                        putJsonArray("zones") { event.zones.forEach { add(it) } }
                                        put("has_snapshot", event.snapshotPath != null)
                                        put("has_clip", event.videoPath != null)
                                    }
                                }.toString(),
                                ContentType.Application.Json
                            )
                        }

                        get("/api/events/{id}/snapshot") {
                            val eventId = call.parameters["id"]?.toLongOrNull()
                            if (eventId == null) {
                                call.respond(HttpStatusCode.BadRequest, "Missing event ID parameter")
                                return@get
                            }

                            // By id, not by scanning the newest hundred: anything older
                            // than that used to 404 while its snapshot was still on disk.
                            val snapshotPath = nvrDao.getEventById(eventId)?.snapshotPath
                            if (snapshotPath != null) {
                                // Snapshots may live in a user-chosen folder (SD card, USB),
                                // where they are document URIs rather than files — read them
                                // through the store so both forms are served.
                                val bytes = recordingStore.openInput(snapshotPath)?.use { it.readBytes() }
                                if (bytes != null) {
                                    call.respondBytes(bytes, ContentType.Image.JPEG)
                                } else {
                                    call.respond(HttpStatusCode.NotFound, "Snapshot file not found on disk")
                                }
                            } else {
                                call.respond(HttpStatusCode.NotFound, "No snapshot associated with this event")
                            }
                        }

                        get("/api/{cameraName}/live.mjpeg") {
                            val cameraName = call.parameters["cameraName"]
                            if (cameraName == null) {
                                call.respond(HttpStatusCode.BadRequest, "Missing cameraName parameter")
                                return@get
                            }

                            val camera = nvrDao.getAllCameraConfigs().find {
                                it.id.equals(cameraName, ignoreCase = true) || it.name.equals(cameraName, ignoreCase = true)
                            }
                            if (camera == null) {
                                call.respond(HttpStatusCode.NotFound, "Camera not found")
                                return@get
                            }

                            val service = nvrService
                            if (service == null) {
                                call.respond(HttpStatusCode.InternalServerError, "NVR service not available")
                                return@get
                            }

                            call.respondBytesWriter(contentType = ContentType.parse("multipart/x-mixed-replace; boundary=frame")) {
                                // fps comes from the config; a zero there must not become
                                // a division by zero on a request thread.
                                val intervalMs = 1000L / camera.fps.coerceAtLeast(1)
                                val out = ByteArrayOutputStream()
                                var lastSent: Bitmap? = null
                                try {
                                    while (true) {
                                        val bitmap = service.getLatestFrame(camera.id)
                                        // Encode only frames the client has not seen: a
                                        // stalled camera should not cost a JPEG per tick.
                                        if (bitmap != null && bitmap !== lastSent && !bitmap.isRecycled) {
                                            lastSent = bitmap
                                            out.reset()
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
                                    Log.d(tag, "MJPEG stream disconnected for camera ${camera.name}: ${e.message}")
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

    private fun errorJson(message: String): String =
        buildJsonObject {
            put("status", "error")
            put("message", message)
        }.toString()

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    /**
     * A small read-only console in the Frigate web UI's colours. Everything it shows
     * is fetched from the API above; nothing on the page is a placeholder.
     */
    private suspend fun webConsoleHtml(): String {
        val cameras = nvrDao.getAllCameraConfigs()
        val tiles = if (cameras.isEmpty()) {
            """<div class="empty">No cameras configured. Add one in the Android app.</div>"""
        } else {
            cameras.joinToString("\n") { camera ->
                val id = java.net.URLEncoder.encode(camera.id, "UTF-8").replace("+", "%20")
                """
                <div class="tile">
                    <img src="/api/$id/live.mjpeg" alt="" onerror="this.style.display='none'">
                    <span class="chip">${escapeHtml(camera.name)}</span>
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
                <title>Frigate Android</title>
                <style>
                    :root { color-scheme: dark; }
                    body { margin: 0; background: #000; color: #fff; font: 14px/1.4 Inter, system-ui, sans-serif; }
                    header { display: flex; align-items: center; gap: 12px; height: 44px; padding: 0 16px;
                             background: #171717; border-bottom: 1px solid #404040; }
                    header b { font-weight: 600; }
                    header span { color: #6b6b6b; font-size: 12px; }
                    main { padding: 12px; display: grid; gap: 16px; grid-template-columns: minmax(0, 3fr) minmax(240px, 1fr); }
                    @media (max-width: 900px) { main { grid-template-columns: 1fr; } }
                    h2 { margin: 0 0 8px; font-size: 14px; font-weight: 500; color: #6b6b6b; }
                    .grid { display: grid; gap: 16px; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); }
                    .tile { position: relative; aspect-ratio: 16 / 9; background: #000; border-radius: 16px;
                            overflow: hidden; outline: 1px solid #262626; }
                    .tile img { width: 100%; height: 100%; object-fit: cover; }
                    .chip { position: absolute; top: 8px; right: 8px; padding: 2px 8px; border-radius: 16px;
                            font-size: 12px; background: linear-gradient(to bottom right, #9ca3af, #6b7280); }
                    .card { background: #171717; border-radius: 16px; padding: 10px; margin-bottom: 16px; }
                    .row { display: flex; justify-content: space-between; gap: 12px; padding: 6px 0;
                           border-bottom: 1px solid #262626; font-size: 13px; }
                    .row:last-child { border-bottom: 0; }
                    .muted { color: #6b6b6b; }
                    .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 8px; }
                    .alert { background: #991b1b; } .detection { background: #ea580c; }
                    .empty { color: #6b6b6b; padding: 32px; text-align: center; grid-column: 1 / -1; }
                </style>
            </head>
            <body>
                <header><b>Frigate Android</b><span id="summary"></span></header>
                <main>
                    <section>
                        <h2>Live</h2>
                        <div class="grid">$tiles</div>
                    </section>
                    <aside>
                        <h2>System</h2>
                        <div class="card" id="stats"><div class="muted">Loading…</div></div>
                        <h2>Review</h2>
                        <div class="card" id="events"><div class="muted">Loading…</div></div>
                    </aside>
                </main>
                <script>
                    // Rows are built with textContent only: camera names and labels come
                    // from user configuration and must never be parsed as markup.
                    function row(left, right, dotClass) {
                        const el = document.createElement('div');
                        el.className = 'row';
                        const a = document.createElement('span');
                        if (dotClass) {
                            const dot = document.createElement('span');
                            dot.className = 'dot ' + dotClass;
                            a.appendChild(dot);
                        }
                        a.appendChild(document.createTextNode(left));
                        const b = document.createElement('span');
                        b.className = 'muted';
                        b.textContent = right;
                        el.append(a, b);
                        return el;
                    }
                    function fill(id, rows, emptyText) {
                        const box = document.getElementById(id);
                        box.replaceChildren();
                        if (rows.length === 0) {
                            const el = document.createElement('div');
                            el.className = 'muted';
                            el.textContent = emptyText;
                            box.appendChild(el);
                        } else {
                            rows.forEach(r => box.appendChild(r));
                        }
                    }
                    const fmt = (v, unit) => v == null ? '—' : v.toFixed(1) + unit;
                    async function refresh() {
                        try {
                            const stats = await (await fetch('/api/stats')).json();
                            const cams = Object.values(stats.cameras);
                            const live = cams.filter(c => c.state === 'LIVE').length;
                            document.getElementById('summary').textContent = live + ' of ' + cams.length + ' cameras live';
                            const rows = [
                                row('CPU', fmt(stats.service.cpu_percent, '%')),
                                row('Memory', fmt(stats.service.memory_percent, '%')),
                                row('Motion detection', fmt(stats.service.motion_ms, ' ms'))
                            ];
                            cams.forEach(c => rows.push(row(c.name, c.state === 'LIVE' ? fmt(c.camera_fps, ' fps') : c.state.toLowerCase())));
                            fill('stats', rows, '');
                        } catch (e) { /* the next tick retries */ }
                        try {
                            const events = await (await fetch('/api/events?limit=20')).json();
                            fill('events', events.map(e => row(
                                e.camera.replace(/_/g, ' ') + (e.zones.length ? ' · ' + e.zones.join(', ') : ''),
                                new Date(e.timestamp).toLocaleTimeString(),
                                e.severity
                            )), 'No events yet');
                        } catch (e) { /* the next tick retries */ }
                    }
                    refresh();
                    setInterval(refresh, 3000);
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
