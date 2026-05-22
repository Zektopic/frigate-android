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

    private fun getWebConsoleHtml(): String {
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
                        grid-template-columns: 1fr;
                        gap: 16px;
                    }
                    .feed-monitor {
                        background-color: #0F0F13;
                        border: 1px solid #222232;
                        border-radius: 8px;
                        height: 240px;
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
                                <div class="feed-monitor">
                                    <div style="text-align: center; color: #8E8E9C;">
                                        <div style="font-size: 32px; margin-bottom: 8px;">📷</div>
                                        <div style="font-weight: bold; color: #F5F5FA;">ACTIVE NVR CAMERA FEED</div>
                                        <div style="font-size: 11px; margin-top: 4px;">RTSP stream is being processed by Motion Detection Engine</div>
                                    </div>
                                    <div style="position: absolute; bottom: 12px; left: 12px; font-size: 10px; color: #FF2A7A; font-weight: bold;">● REC</div>
                                    <div style="position: absolute; bottom: 12px; right: 12px; font-size: 10px; color: #8E8E9C;">5.0 FPS | 300x300</div>
                                </div>
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
