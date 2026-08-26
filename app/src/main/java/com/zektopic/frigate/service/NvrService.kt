package com.zektopic.frigate.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.zektopic.frigate.FrigateApp
import com.zektopic.frigate.MainActivity
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.NvrDao
import com.zektopic.frigate.media.DevicePerformance
import com.zektopic.frigate.media.StreamIngester
import com.zektopic.frigate.media.StreamState
import dagger.hilt.android.AndroidEntryPoint
import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@AndroidEntryPoint
class NvrService : Service(), LifecycleOwner {

    private val tag = "NvrService"
    private val binder = NvrBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeIngesters = mutableMapOf<String, StreamIngester>()
    private val lifecycleRegistry = LifecycleRegistry(this)

    // Motion variables
    private val motionDetectors = mutableMapOf<String, com.zektopic.frigate.ai.MotionDetector>()
    private val lastEventTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastFrameProcessTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Per-camera in-flight guard for the AI pipeline. The time throttle below limits
    // dispatch rate, not completion, so a slow frame would otherwise keep queueing
    // coroutines (each retaining a detect-resolution bitmap) on Dispatchers.Default.
    // Drop the new frame instead — same policy as _frameFlow's DROP_OLDEST.
    private val frameProcessInFlight =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

    // Live Frame flow and cache
    private val latestFramesMap = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val _frameFlow = MutableSharedFlow<Pair<String, Bitmap>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val frameFlow = _frameFlow.asSharedFlow()

    // Per-camera connection state (Frigate-style: connecting/live/retrying/offline)
    private val _streamStates = MutableStateFlow<Map<String, StreamState>>(emptyMap())
    val streamStates = _streamStates.asStateFlow()

    fun getLatestFrame(cameraId: String): Bitmap? {
        return latestFramesMap[cameraId]
    }

    // Embedded Ktor Web Server
    private var webServer: com.zektopic.frigate.server.EmbeddedWebServer? = null

    // Motion-triggered clip recording
    private lateinit var clipRecorder: com.zektopic.frigate.media.ClipRecorder
    private lateinit var recordingJanitor: com.zektopic.frigate.data.RecordingJanitor
    private val cameraById = java.util.concurrent.ConcurrentHashMap<String, CameraConfigEntity>()
    private val activeEventIdByCamera = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Live per-camera feature toggles (detect/record/snapshots), parsed from the
    // persisted YAML so the tile switches genuinely gate downstream behavior.
    @Volatile private var cameraFeatures: Map<String, com.zektopic.frigate.ui.settings.CameraFeatures> = emptyMap()

    // Notification preferences, cached from DataStore so alert gating is enforced.
    private lateinit var appPreferences: com.zektopic.frigate.data.AppPreferences
    @Volatile private var notificationSettings = com.zektopic.frigate.data.NotificationSettings()

    @Inject
    lateinit var nvrDao: NvrDao

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        clipRecorder = com.zektopic.frigate.media.ClipRecorder(this, nvrDao)
        recordingJanitor = com.zektopic.frigate.data.RecordingJanitor(
            com.zektopic.frigate.data.RecordingStore(applicationContext), nvrDao
        )

        // Cache notification preferences so alert gating is enforced at post time
        appPreferences = com.zektopic.frigate.data.AppPreferences(applicationContext)
        serviceScope.launch {
            appPreferences.notificationSettings.collect { notificationSettings = it }
        }

        // Cache per-camera live feature toggles parsed from the persisted YAML
        serviceScope.launch {
            nvrDao.getSystemConfigFlow().collect { config ->
                cameraFeatures = config?.configYaml?.let {
                    try { com.zektopic.frigate.ui.settings.CameraYamlEditor.readAllCameraFeatures(it) }
                    catch (e: Exception) { emptyMap() }
                } ?: emptyMap()
            }
        }

        // Periodically finalize idle recordings and attach the clip to its event
        serviceScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(2000)
                for ((cameraId, path) in clipRecorder.reapIdleSessions()) {
                    activeEventIdByCamera.remove(cameraId)?.let { eventId ->
                        try { nvrDao.updateEventVideoPath(eventId, path) } catch (e: Exception) {
                            Log.e(tag, "Failed to attach clip to event", e)
                        }
                    }
                }
            }
        }

        // Make room for new recordings before a full disk can stop them. Separate from the
        // 2s reap loop above: a sweep lists every recording in every location, which is far
        // too expensive to run at that cadence.
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                recordingJanitor.sweep()
                kotlinx.coroutines.delay(60_000)
            }
        }

        // Initialize and start embedded Ktor web server
        webServer = com.zektopic.frigate.server.EmbeddedWebServer(this, nvrDao)
        webServer?.start()

        Log.i(tag, "NVR Foreground Service created. Web Server initialized.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        // Show persistent status notification to run in background
        val hasCameraPermission = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (hasCameraPermission) {
                type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(1, createStatusNotification("Initializing NVR streams..."), type)
        } else {
            startForeground(1, createStatusNotification("Initializing NVR streams..."))
        }

        // Observe database configurations flow and launch/reload streams dynamically
        serviceScope.launch {
            try {
                @OptIn(kotlinx.coroutines.FlowPreview::class)
                nvrDao.getAllCameraConfigsFlow().debounce(500L).collect { cameras ->
                    val activeCameras = cameras.filter { it.isEnabled }
                    withContext(Dispatchers.Main) {
                        if (activeCameras.isEmpty()) {
                            updateNotification("No active cameras configured. Configure in App UI.")
                            synchronized(activeIngesters) {
                                for (ingester in activeIngesters.values) {
                                    ingester.stop()
                                }
                                activeIngesters.clear()
                                motionDetectors.clear()
                                _streamStates.value = emptyMap()
                            }
                        } else {
                            updateNotification("Monitoring ${activeCameras.size} active camera stream(s)")
                            startActiveStreams(activeCameras)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to load camera configurations flow in service", e)
                withContext(Dispatchers.Main) {
                    updateNotification("Error starting streams: ${e.message}")
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return binder
    }

    override fun onDestroy() {
        Log.i(tag, "NVR Foreground Service destroying...")

        // Finalize any in-progress recordings before tearing down
        try { clipRecorder.stopAll() } catch (e: Exception) { Log.e(tag, "Error stopping recordings", e) }

        serviceScope.cancel()

        // Safely stop all stream ingesters
        synchronized(activeIngesters) {
            for ((cameraId, ingester) in activeIngesters) {
                Log.d(tag, "Stopping ingester stream for camera: $cameraId")
                ingester.stop()
            }
            activeIngesters.clear()
        }

        // Release resources
        motionDetectors.clear()
        lastEventTime.clear()
        latestFramesMap.clear()

        // Stop web server
        webServer?.stop()
        webServer = null

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // Track the last camera config set to avoid unnecessary restarts
    private var lastCameraConfigSnapshot: List<CameraConfigEntity> = emptyList()

    private fun startActiveStreams(cameras: List<CameraConfigEntity>) {
        // Compare new config with existing - only restart if something actually changed
        val newConfigSignatures = cameras.map { configSignature(it) }.toSet()
        val oldConfigSignatures = lastCameraConfigSnapshot.map { configSignature(it) }.toSet()

        if (newConfigSignatures == oldConfigSignatures && activeIngesters.size == cameras.size) {
            Log.d(tag, "Camera configs unchanged, skipping stream restart.")
            return
        }

        val budget = DevicePerformance.budget(this)
        val admitted = cameras.take(budget.maxConcurrentStreams)
        if (admitted.size < cameras.size) {
            val dropped = cameras.drop(budget.maxConcurrentStreams).joinToString { it.name }
            Log.w(
                tag,
                "Device budget allows ${budget.maxConcurrentStreams} concurrent streams " +
                    "(${budget.describe()}); not starting: $dropped"
            )
        }

        Log.i(tag, "Camera configs changed. Restarting ${admitted.size} stream(s)...")
        lastCameraConfigSnapshot = cameras.toList()

        val pending = mutableListOf<StreamIngester>()
        synchronized(activeIngesters) {
            // Stop any existing streams first
            for (ingester in activeIngesters.values) {
                ingester.stop()
            }
            activeIngesters.clear()
            motionDetectors.clear()
            cameraById.clear()
            // Drop in-flight markers with the ingesters that set them; a stale `true`
            // would permanently gate the new ingester for that camera.
            frameProcessInFlight.clear()
            // Drop cached frames with the ingesters that produced them. Not recycled:
            // Compose and the MJPEG endpoint may still be holding one, and a recycled
            // Bitmap under them is a crash. Releasing the reference is enough to stop
            // a removed camera's full-resolution frame living for the service's life.
            latestFramesMap.clear()
            _streamStates.value = emptyMap()

            // Initialize new ingesters
            for (camera in admitted) {
                // Initialize a motion detector for this camera with specific thresholds
                motionDetectors[camera.id] = com.zektopic.frigate.ai.MotionDetector(camera.motionThreshold)
                cameraById[camera.id] = camera

                val ingester = StreamIngester(
                    this,
                    camera,
                    onFrameExtracted = { frameBitmap ->
                        // Cache frame and emit for Compose/Ktor clients
                        latestFramesMap[camera.id] = frameBitmap
                        _frameFlow.tryEmit(Pair(camera.id, frameBitmap))

                        // Feed any in-progress motion recording for this camera
                        clipRecorder.offerFrame(camera.id, frameBitmap)

                        // Route frame through the gated AI pipeline in a background thread
                        // (throttled to 5 FPS per camera max, and at most one in flight)
                        val now = System.currentTimeMillis()
                        val lastProc = lastFrameProcessTime[camera.id] ?: 0L
                        if (now - lastProc >= 200L) {
                            val inFlight = frameProcessInFlight.getOrPut(camera.id) {
                                java.util.concurrent.atomic.AtomicBoolean(false)
                            }
                            if (inFlight.compareAndSet(false, true)) {
                                lastFrameProcessTime[camera.id] = now
                                serviceScope.launch(Dispatchers.Default) {
                                    try {
                                        processIncomingFrame(camera.id, frameBitmap)
                                    } finally {
                                        inFlight.set(false)
                                    }
                                }
                            }
                        }
                    },
                    onStateChanged = { state ->
                        _streamStates.update { it + (camera.id to state) }
                    }
                )
                activeIngesters[camera.id] = ingester
                pending += ingester
            }
        }

        // Start staggered rather than all at once. Seven simultaneous MediaCodec
        // configure() calls is what turns "not enough codecs" into "no codecs at all",
        // and the allocation spike is also what the low-memory killer notices first.
        serviceScope.launch {
            pending.forEachIndexed { index, ingester ->
                if (index > 0) delay(budget.streamStartStaggerMs)
                // The set can be swapped out from under us by another config change.
                if (!activeIngesters.containsValue(ingester)) return@forEachIndexed
                ingester.start()
            }
        }
    }

    /**
     * Android's warning shot before the low-memory killer. Previously unimplemented,
     * which meant the app's only response to memory pressure was to be killed - the
     * observed failure on the Helio G88 tablet, where streams simply stop after a
     * while. Shedding here is strictly better than dying: a dropped frame cache
     * rebuilds in one frame, a killed process loses every stream.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.w(tag, "Memory critical (level=$level): dropping frame cache and stopping recordings")
                latestFramesMap.clear()
                clipRecorder.stopAll()
            }
            level >= TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(tag, "Memory low (level=$level): dropping cached frames")
                latestFramesMap.clear()
            }
        }
    }

    private fun configSignature(c: CameraConfigEntity): String {
        return "${c.id}|${c.name}|${c.rtspUrl}|${c.detectRtspUrl}|${c.isEnabled}|" +
            "${c.detectWidth}|${c.detectHeight}|${c.fps}|${c.motionThreshold}"
    }

    private suspend fun processIncomingFrame(cameraId: String, bitmap: android.graphics.Bitmap) {
        val motionDetector = motionDetectors[cameraId] ?: return

        // Per-camera live toggles (default all-on when a camera has no flags yet)
        val features = cameraFeatures[cameraId] ?: com.zektopic.frigate.ui.settings.CameraFeatures()

        // 1. Run low-power motion detection pre-filter
        val hasMotion = motionDetector.detectMotion(bitmap)
        if (!hasMotion) return

        // 2. Open (or extend) a motion-triggered clip recording for this camera
        //    — gated by the camera's Record toggle
        val camera = cameraById[cameraId]
        if (camera != null && features.record) {
            clipRecorder.onMotion(camera, bitmap)
        }

        // Detect toggle off → no events or alerts (recording above is independent)
        if (!features.detect) return

        // 3. Throttle event logging to once every 10 seconds per camera
        val currentTime = System.currentTimeMillis()
        val lastTime = lastEventTime[cameraId] ?: 0L
        if (currentTime - lastTime >= 10000L) {
            lastEventTime[cameraId] = currentTime

            // Save a snapshot for the event thumbnail — gated by the Snapshots toggle
            val snapshotPath = if (features.snapshots)
                clipRecorder.saveEventSnapshot(cameraId, "motion", bitmap)
            else null

            // Insert event; videoPath is attached when the clip finalizes (see reap loop)
            val eventId = nvrDao.insertEvent(
                com.zektopic.frigate.data.EventEntity(
                    cameraId = cameraId,
                    label = "motion",
                    confidence = 1.0f,
                    timestamp = currentTime,
                    snapshotPath = snapshotPath,
                    videoPath = null,
                    zone = "Main Zone"
                )
            )
            activeEventIdByCamera[cameraId] = eventId

            // Trigger system alerts for motion detection
            triggerSystemAlert(cameraId, "motion", 1.0f)
        }
    }

    private fun triggerSystemAlert(cameraId: String, label: String, confidence: Float) {
        // Respect the user's notification preferences (global + per-camera mute)
        if (!notificationSettings.shouldNotify(cameraId)) return

        val notificationManager = androidx.core.app.NotificationManagerCompat.from(this)

        // Validate runtime permissions
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val permissionState = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (permissionState != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }

        val text = "Motion was detected on camera '$cameraId'."
        val builder = androidx.core.app.NotificationCompat.Builder(this, FrigateApp.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Frigate Alert: Motion Detected")
            .setContentText(text)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            notificationManager.notify(label.hashCode() + cameraId.hashCode(), builder.build())
        } catch (e: SecurityException) {
            Log.e(tag, "Notification security exception", e)
        }
    }

    private fun createStatusNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, FrigateApp.CHANNEL_NVR_STATUS)
            .setContentTitle("Frigate NVR Service")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(1, createStatusNotification(text))
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    inner class NvrBinder : Binder() {
        fun getService(): NvrService = this@NvrService
    }
}
