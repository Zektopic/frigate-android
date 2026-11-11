package com.zektopic.frigate.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.zektopic.frigate.FrigateApp
import com.zektopic.frigate.MainActivity
import com.zektopic.frigate.ai.MotionBox
import com.zektopic.frigate.ai.MotionConfig
import com.zektopic.frigate.ai.MotionDetector
import com.zektopic.frigate.data.AppPreferences
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.CameraRuntimeConfig
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.data.NotificationSettings
import com.zektopic.frigate.data.NvrDao
import com.zektopic.frigate.data.RecordingJanitor
import com.zektopic.frigate.data.RecordingStore
import com.zektopic.frigate.data.ReviewRules
import com.zektopic.frigate.data.ReviewSeverity
import com.zektopic.frigate.data.YamlConfigParser
import com.zektopic.frigate.media.ClipRecorder
import com.zektopic.frigate.media.DevicePerformance
import com.zektopic.frigate.media.StreamDiagnostics
import com.zektopic.frigate.media.StreamIngester
import com.zektopic.frigate.media.StreamState
import com.zektopic.frigate.notification.AlertManager
import com.zektopic.frigate.server.EmbeddedWebServer
import com.zektopic.frigate.ui.settings.CameraFeatures
import com.zektopic.frigate.ui.settings.CameraYamlEditor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class NvrService : Service(), LifecycleOwner {

    private val tag = "NvrService"
    private val binder = NvrBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeIngesters = mutableMapOf<String, StreamIngester>()
    private val lifecycleRegistry = LifecycleRegistry(this)

    /** A camera's detector together with the config it was built from. */
    private class DetectorSlot(val config: MotionConfig, val detector: MotionDetector)

    private val motionDetectors = ConcurrentHashMap<String, DetectorSlot>()
    private val lastFrameProcessTime = ConcurrentHashMap<String, Long>()

    // Per-camera in-flight guard for the motion pipeline. The time throttle below limits
    // dispatch rate, not completion, so a slow frame would otherwise keep queueing
    // coroutines (each retaining a detect-resolution bitmap) on Dispatchers.Default.
    // Drop the new frame instead — same policy as _frameFlow's DROP_OLDEST.
    private val frameProcessInFlight = ConcurrentHashMap<String, AtomicBoolean>()

    // Live Frame flow and cache
    private val latestFramesMap = ConcurrentHashMap<String, Bitmap>()
    private val _frameFlow = MutableSharedFlow<Pair<String, Bitmap>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val frameFlow = _frameFlow.asSharedFlow()

    // Per-camera connection state (Frigate-style: connecting/live/retrying/offline)
    private val _streamStates = MutableStateFlow<Map<String, StreamState>>(emptyMap())
    val streamStates = _streamStates.asStateFlow()

    /** Cameras with motion right now; drives the pulsing dot on a live tile. */
    private val _activeMotion = MutableStateFlow<Set<String>>(emptySet())
    val activeMotion = _activeMotion.asStateFlow()

    /** Cameras with an alert in progress; drives the red outline on a live tile. */
    private val _activeAlerts = MutableStateFlow<Set<String>>(emptySet())
    val activeAlerts = _activeAlerts.asStateFlow()

    /** Cameras configured and enabled but not running because the device budget is spent. */
    private val _camerasOverBudget = MutableStateFlow<List<String>>(emptyList())
    val camerasOverBudget = _camerasOverBudget.asStateFlow()

    /**
     * Whether the NVR is actually running. Not the same as the service existing: the
     * activity binds with BIND_AUTO_CREATE, so the service is alive whenever the app
     * is on screen, started or not.
     */
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _stats = MutableStateFlow<List<StatsSample>>(emptyList())
    val stats = _stats.asStateFlow()
    private val statsCollector = StatsCollector()

    private val lastMotionAt = ConcurrentHashMap<String, Long>()
    private val latestMotionBoxes = ConcurrentHashMap<String, List<MotionBox>>()

    fun getLatestFrame(cameraId: String): Bitmap? = latestFramesMap[cameraId]

    /** Boxes from the most recent analysed frame, in that frame's pixels. Empty when still. */
    fun getMotionBoxes(cameraId: String): List<MotionBox> = latestMotionBoxes[cameraId].orEmpty()

    // Embedded Ktor Web Server
    private var webServer: EmbeddedWebServer? = null

    // Motion-triggered clip recording
    private lateinit var clipRecorder: ClipRecorder
    private lateinit var recordingJanitor: RecordingJanitor
    private lateinit var alertManager: AlertManager
    private val cameraById = ConcurrentHashMap<String, CameraConfigEntity>()

    /**
     * The event currently open for a camera. One event spans one stretch of activity:
     * further motion extends it instead of logging a new row every ten seconds, which
     * is what used to leave all but the last of those rows without a clip.
     */
    private class ActiveEvent(
        val id: Long,
        @Volatile var lastMotionAt: Long,
        @Volatile var severity: ReviewSeverity,
        @Volatile var zones: Set<String>
    )

    private val activeEvents = ConcurrentHashMap<String, ActiveEvent>()

    // Live per-camera feature toggles (detect/record/snapshots) and the motion, zone
    // and review settings, all parsed from the persisted YAML.
    @Volatile private var cameraFeatures: Map<String, CameraFeatures> = emptyMap()
    @Volatile private var runtimeConfigs: Map<String, CameraRuntimeConfig> = emptyMap()

    // Notification preferences, cached from DataStore so alert gating is enforced.
    private lateinit var appPreferences: AppPreferences
    @Volatile private var notificationSettings = NotificationSettings()

    private var configJob: Job? = null

    @Inject
    lateinit var nvrDao: NvrDao

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        clipRecorder = ClipRecorder(this, nvrDao)
        recordingJanitor = RecordingJanitor(RecordingStore(applicationContext), nvrDao)
        alertManager = AlertManager(applicationContext)

        // Cache notification preferences so alert gating is enforced at post time
        appPreferences = AppPreferences(applicationContext)
        serviceScope.launch {
            appPreferences.notificationSettings.collect { notificationSettings = it }
        }

        // Cache everything the frame path reads from the persisted YAML
        serviceScope.launch {
            nvrDao.getSystemConfigFlow().collect { config ->
                val yaml = config?.configYaml
                cameraFeatures = yaml?.let {
                    try { CameraYamlEditor.readAllCameraFeatures(it) } catch (e: Exception) { emptyMap() }
                } ?: emptyMap()
                runtimeConfigs = yaml?.let {
                    try { YamlConfigParser.parseRuntimeConfigs(it) } catch (e: Exception) { emptyMap() }
                } ?: emptyMap()
            }
        }

        // A process killed mid-recording leaves its events open with no clip coming.
        serviceScope.launch {
            try {
                nvrDao.closeStaleEvents(System.currentTimeMillis() - STALE_EVENT_MS)
            } catch (e: Exception) {
                Log.e(tag, "Could not close stale events", e)
            }
        }

        // Periodically finalize idle recordings and close out their events
        serviceScope.launch {
            while (isActive) {
                delay(REAP_INTERVAL_MS)
                reapFinishedActivity()
            }
        }

        // Make room for new recordings before a full disk can stop them. Separate from the
        // 2s reap loop above: a sweep lists every recording in every location, which is far
        // too expensive to run at that cadence.
        serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                recordingJanitor.sweep()
                delay(60_000)
            }
        }

        serviceScope.launch { sampleStats() }

        // Initialize and start embedded Ktor web server
        webServer = EmbeddedWebServer(this, nvrDao)
        webServer?.start()

        Log.i(tag, "NVR Foreground Service created. Web Server initialized.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        _running.value = true

        // Show persistent status notification to run in background
        enterForeground(createStatusNotification("Initializing NVR streams..."))

        // onStartCommand runs on every startForegroundService call, and the activity
        // makes one each time it opens. Each call used to add another collector, all
        // racing to restart the same streams.
        if (configJob?.isActive != true) {
            configJob = serviceScope.launch { observeConfig() }
        }

        return START_STICKY
    }

    /**
     * Stop watching cameras. Must be called on the main thread.
     *
     * `stopService` alone does nothing while the activity is bound: Android keeps a
     * bound service alive, so the streams kept running behind a UI that said they
     * had stopped. This tears the pipeline down itself and then lets go.
     */
    fun stopNvr() {
        if (!_running.value) return
        _running.value = false
        Log.i(tag, "Stopping NVR on request")
        configJob?.cancel()
        configJob = null
        stopAllStreams()
        val saved = clipRecorder.stopAll()
        serviceScope.launch { closeAllEvents(saved) }
        _stats.value = emptyList()
        androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Observe the camera table and the config together, and (re)start streams to match. */
    @OptIn(FlowPreview::class)
    private suspend fun observeConfig() {
        try {
            nvrDao.getAllCameraConfigsFlow()
                .combine(nvrDao.getSystemConfigFlow()) { cameras, config -> cameras to config?.configYaml }
                .debounce(500L)
                .collect { (cameras, yaml) ->
                    val activeCameras = cameras.filter { it.isEnabled }
                    val streamCap = yaml?.let { YamlConfigParser.parseMaxConcurrentStreams(it) }
                    val lowResource = yaml?.let { YamlConfigParser.parseLowResource(it) } ?: false
                    withContext(Dispatchers.Main) {
                        if (activeCameras.isEmpty()) {
                            updateNotification("No active cameras configured. Configure in App UI.")
                            stopAllStreams()
                        } else {
                            startActiveStreams(activeCameras, streamCap, lowResource)
                        }
                    }
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Failed to load camera configurations flow in service", e)
            withContext(Dispatchers.Main) {
                updateNotification("Error starting streams: ${e.message}")
            }
        }
    }

    /**
     * Only the device-camera source needs the camera type, and Android 14+ refuses it
     * unless the app could use the camera right now, which a start from
     * [BootReceiver] (boot, app update) cannot. RTSP cameras need only dataSync, so
     * fall back to that rather than fail the whole service.
     */
    private fun enterForeground(notification: Notification) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            startForeground(1, notification)
            return
        }
        val dataSync = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val hasCameraPermission = checkSelfPermission(android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasCameraPermission) {
            try {
                startForeground(1, notification, dataSync or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
                return
            } catch (e: SecurityException) {
                Log.w(tag, "Camera foreground type not allowed from here (${e.message}); running as dataSync")
            } catch (e: IllegalStateException) {
                Log.w(tag, "Camera foreground type not allowed from here (${e.message}); running as dataSync")
            }
        }
        startForeground(1, notification, dataSync)
    }

    override fun onBind(intent: Intent?): IBinder {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return binder
    }

    override fun onDestroy() {
        Log.i(tag, "NVR Foreground Service destroying...")

        // Finalize any in-progress recordings and attach each to its event. Bounded:
        // this is the main thread, and a stuck database must not turn shutdown into an ANR.
        try {
            val saved = clipRecorder.stopAll()
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(1_500L) { closeAllEvents(saved) }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error stopping recordings", e)
        }

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
        latestFramesMap.clear()
        latestMotionBoxes.clear()

        // Stop web server
        webServer?.stop()
        webServer = null

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // What was last admitted, to avoid unnecessary restarts
    private var lastAdmittedSignatures: Set<String> = emptySet()
    private var lastLowResource = false

    private fun stopAllStreams() {
        synchronized(activeIngesters) {
            for (ingester in activeIngesters.values) ingester.stop()
            activeIngesters.clear()
            resetPerStreamState()
            lastAdmittedSignatures = emptySet()
        }
        _camerasOverBudget.value = emptyList()
    }

    /** Must be called holding [activeIngesters]. */
    private fun resetPerStreamState() {
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
        latestMotionBoxes.clear()
        lastMotionAt.clear()
        _streamStates.value = emptyMap()
        _activeMotion.value = emptySet()
    }

    /**
     * @param streamCap the config's `android.max_concurrent_streams`, or null to let
     *   the device budget decide how many of [cameras] to run.
     */
    private fun startActiveStreams(cameras: List<CameraConfigEntity>, streamCap: Int?, lowResource: Boolean) {
        DevicePerformance.lowResource = lowResource
        val budget = DevicePerformance.budget(this)
        val cap = DevicePerformance.effectiveMaxStreams(budget, streamCap)
        DevicePerformance.streamCapOverride = streamCap
        val admitted = cameras.take(cap)
        _camerasOverBudget.value = cameras.drop(cap).map { it.name }

        updateNotification(
            if (admitted.size < cameras.size) "Monitoring ${admitted.size} of ${cameras.size} cameras (device limit)"
            else "Monitoring ${admitted.size} active camera stream(s)"
        )

        // Compare what would actually run. Comparing every configured camera against
        // the running ingesters never matched while the cap left some out, so every
        // emission restarted every stream; comparing the admitted set also restarts
        // when only the cap changes.
        val admittedSignatures = admitted.map { configSignature(it) }.toSet()
        if (admittedSignatures == lastAdmittedSignatures &&
            activeIngesters.size == admitted.size &&
            lowResource == lastLowResource
        ) {
            Log.d(tag, "Camera configs unchanged, skipping stream restart.")
            return
        }

        if (streamCap != null && cap != streamCap) {
            Log.w(tag, "android.max_concurrent_streams=$streamCap exceeds the codec pool; using $cap")
        }
        if (admitted.size < cameras.size) {
            val dropped = cameras.drop(cap).joinToString { it.name }
            val source = if (streamCap != null) "android.max_concurrent_streams" else "device budget, ${budget.describe()}"
            Log.w(tag, "Running at most $cap concurrent streams ($source); not starting: $dropped")
        }

        Log.i(tag, "Camera configs changed. Restarting ${admitted.size} stream(s)...")
        lastAdmittedSignatures = admittedSignatures
        lastLowResource = lowResource

        val pending = mutableListOf<StreamIngester>()
        synchronized(activeIngesters) {
            // Stop any existing streams first
            for (ingester in activeIngesters.values) {
                ingester.stop()
            }
            activeIngesters.clear()
            resetPerStreamState()

            // Initialize new ingesters
            for (camera in admitted) {
                cameraById[camera.id] = camera

                val ingester = StreamIngester(
                    this,
                    camera,
                    onFrameExtracted = { frameBitmap -> onFrame(camera, frameBitmap) },
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

    /** Runs on the stream's frame-extraction thread: must never block. */
    private fun onFrame(camera: CameraConfigEntity, frame: Bitmap) {
        // Cache frame and emit for Compose/Ktor clients
        latestFramesMap[camera.id] = frame
        _frameFlow.tryEmit(Pair(camera.id, frame))

        // Feed any in-progress motion recording for this camera
        clipRecorder.offerFrame(camera.id, frame)

        // Route the frame through motion detection on a background thread, throttled
        // to 5 FPS per camera and with at most one frame in flight.
        val now = System.currentTimeMillis()
        val lastProc = lastFrameProcessTime[camera.id] ?: 0L
        if (now - lastProc < MIN_DETECT_INTERVAL_MS) return
        val inFlight = frameProcessInFlight.getOrPut(camera.id) { AtomicBoolean(false) }
        if (!inFlight.compareAndSet(false, true)) {
            statsCollector.onFrameSkipped(camera.id)
            return
        }
        lastFrameProcessTime[camera.id] = now
        serviceScope.launch(Dispatchers.Default) {
            try {
                processIncomingFrame(camera.id, frame)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Motion pipeline failed for ${camera.id}", e)
            } finally {
                inFlight.set(false)
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
                val saved = clipRecorder.stopAll()
                serviceScope.launch { closeAllEvents(saved) }
            }
            level >= TRIM_MEMORY_RUNNING_LOW -> {
                Log.w(tag, "Memory low (level=$level): dropping cached frames")
                latestFramesMap.clear()
            }
        }
    }

    private fun configSignature(c: CameraConfigEntity): String {
        return "${c.id}|${c.name}|${c.rtspUrl}|${c.detectRtspUrl}|${c.isEnabled}|" +
            "${c.detectWidth}|${c.detectHeight}|${c.fps}"
    }

    /** The camera's detector, rebuilt when its motion settings change in the config. */
    private fun detectorFor(cameraId: String, config: MotionConfig): MotionDetector {
        val slot = motionDetectors[cameraId]
        if (slot != null && slot.config == config) return slot.detector
        return MotionDetector(config).also { motionDetectors[cameraId] = DetectorSlot(config, it) }
    }

    private suspend fun processIncomingFrame(cameraId: String, bitmap: Bitmap) {
        val camera = cameraById[cameraId] ?: return
        val runtime = runtimeConfigs[cameraId] ?: DEFAULT_RUNTIME

        // Per-camera live toggles (default all-on when a camera has no flags yet)
        val features = cameraFeatures[cameraId] ?: CameraFeatures()

        // 1. Motion detection against the running background model
        val startedAt = System.nanoTime()
        val result = detectorFor(cameraId, runtime.motion).detect(bitmap)
        statsCollector.onFrameProcessed(cameraId, System.nanoTime() - startedAt)

        latestMotionBoxes[cameraId] = if (result.hasMotion) result.boxes else emptyList()
        if (!result.hasMotion) return

        val now = System.currentTimeMillis()
        lastMotionAt[cameraId] = now
        if (cameraId !in _activeMotion.value) _activeMotion.update { it + cameraId }

        // 2. Open (or extend) a motion-triggered clip recording for this camera
        //    — gated by the camera's Record toggle
        if (features.record) clipRecorder.onMotion(camera, bitmap)

        // Detect toggle off → no events or alerts (recording above is independent)
        if (!features.detect) return

        val zones = runtime.zonesFor(result.boxes, bitmap.width, bitmap.height)
        val severity = runtime.review.classify(ReviewRules.MOTION_LABEL, zones) ?: return

        // 3. One event per stretch of activity: extend the open one, or start a new one
        val open = activeEvents[cameraId]
        if (open != null) {
            open.lastMotionAt = now
            val mergedZones = open.zones + zones
            val promoted = severity == ReviewSeverity.ALERT && open.severity != ReviewSeverity.ALERT
            if (promoted || mergedZones != open.zones) {
                open.zones = mergedZones
                if (promoted) open.severity = ReviewSeverity.ALERT
                nvrDao.updateEventReview(open.id, mergedZones.joinToString(",").ifEmpty { null }, open.severity.key)
                if (promoted) raiseAlert(camera, mergedZones.toList(), bitmap)
            }
            return
        }

        // Save a snapshot for the event thumbnail — gated by the Snapshots toggle
        val snapshotPath = if (features.snapshots)
            clipRecorder.saveEventSnapshot(cameraId, ReviewRules.MOTION_LABEL, bitmap)
        else null

        // The clip is attached when the recording finalizes (see reapFinishedActivity)
        val eventId = nvrDao.insertEvent(
            EventEntity(
                cameraId = cameraId,
                label = ReviewRules.MOTION_LABEL,
                confidence = 1.0f,
                timestamp = now,
                snapshotPath = snapshotPath,
                videoPath = null,
                zone = zones.joinToString(",").ifEmpty { null },
                severity = severity.key
            )
        )
        activeEvents[cameraId] = ActiveEvent(eventId, now, severity, zones.toSet())
        if (severity == ReviewSeverity.ALERT) raiseAlert(camera, zones, bitmap)
    }

    private fun raiseAlert(camera: CameraConfigEntity, zones: List<String>, frame: Bitmap) {
        _activeAlerts.update { it + camera.id }
        // Respect the user's notification preferences (global + per-camera mute)
        if (!notificationSettings.shouldNotify(camera.id)) return
        alertManager.postMotionAlert(camera.id, camera.name, zones, frame)
    }

    /** Finalize idle recordings, close the events they belong to, and expire motion state. */
    private suspend fun reapFinishedActivity() {
        val now = System.currentTimeMillis()
        try {
            for ((cameraId, path) in clipRecorder.reapIdleSessions()) {
                closeEvent(cameraId, path, now)
            }
            // Events with no recording behind them (Record off, or the encoder cap was
            // hit) have nothing to finalize, so they end when the motion does.
            for ((cameraId, event) in activeEvents) {
                if (!clipRecorder.isRecording(cameraId) && now - event.lastMotionAt > EVENT_IDLE_MS) {
                    closeEvent(cameraId, null, event.lastMotionAt)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Failed to finalize recordings", e)
        }

        val still = _activeMotion.value.filterTo(HashSet()) { now - (lastMotionAt[it] ?: 0L) <= MOTION_HOLD_MS }
        if (still != _activeMotion.value) _activeMotion.value = still
        for (id in latestMotionBoxes.keys) {
            if (id !in still) latestMotionBoxes[id] = emptyList()
        }
    }

    private suspend fun closeEvent(cameraId: String, videoPath: String?, endTime: Long) {
        val event = activeEvents.remove(cameraId) ?: return
        _activeAlerts.update { it - cameraId }
        try {
            nvrDao.finishEvent(event.id, videoPath, endTime)
        } catch (e: Exception) {
            Log.e(tag, "Failed to close event ${event.id}", e)
        }
    }

    private suspend fun closeAllEvents(savedClips: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        val clipByCamera = savedClips.toMap()
        for (cameraId in activeEvents.keys.toList()) {
            closeEvent(cameraId, clipByCamera[cameraId], now)
        }
    }

    private suspend fun sampleStats() {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        val runtime = Runtime.getRuntime()
        while (serviceScope.isActive) {
            if (!_running.value) {
                delay(StatsCollector.SAMPLE_INTERVAL_MS)
                continue
            }
            am.getMemoryInfo(memInfo)
            val memoryPercent = if (memInfo.totalMem > 0)
                100f * (memInfo.totalMem - memInfo.availMem) / memInfo.totalMem else 0f
            val sample = statsCollector.sample(
                nowMs = System.currentTimeMillis(),
                cameraIds = cameraById.keys.toList(),
                frames = { StreamDiagnostics.byCamera[it]?.frames?.get() ?: 0L },
                cpuTimeMs = android.os.Process.getElapsedCpuTime(),
                memoryPercent = memoryPercent,
                heapMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576f
            )
            if (sample != null) {
                _stats.update { (it + sample).takeLast(StatsCollector.HISTORY) }
            }
            delay(StatsCollector.SAMPLE_INTERVAL_MS)
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

    private companion object {
        val DEFAULT_RUNTIME = CameraRuntimeConfig()
        const val MIN_DETECT_INTERVAL_MS = 200L
        const val REAP_INTERVAL_MS = 2_000L
        /** How long after the last motion an event without a recording stays open. */
        const val EVENT_IDLE_MS = 6_000L
        /** How long a tile keeps its motion indicator after the last moving frame. */
        const val MOTION_HOLD_MS = 3_000L
        const val STALE_EVENT_MS = 120_000L
    }
}
