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
import com.zektopic.frigate.ai.DetectionPipeline
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.NvrDao
import com.zektopic.frigate.media.ClipRecorder
import com.zektopic.frigate.media.StreamIngester
import com.zektopic.frigate.media.HevcDecoderChecker
import dagger.hilt.android.AndroidEntryPoint
import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@AndroidEntryPoint
class NvrService : Service(), LifecycleOwner {

    private val tag = "NvrService"
    private val binder = NvrBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeIngesters = mutableMapOf<String, StreamIngester>()
    private val lifecycleRegistry = LifecycleRegistry(this)

    // Live Frame flow and cache
    private val latestFramesMap = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val _frameFlow = MutableSharedFlow<Pair<String, Bitmap>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val frameFlow = _frameFlow.asSharedFlow()

    fun getLatestFrame(cameraId: String): Bitmap? {
        return latestFramesMap[cameraId]
    }

    // AI Detection Pipeline
    private var detectionPipeline: DetectionPipeline? = null

    // Clip Recorder with real EventRecorder for pre-buffer recordings
    private var clipRecorder: ClipRecorder? = null

    // Embedded Ktor Web Server
    private var webServer: com.zektopic.frigate.server.EmbeddedWebServer? = null

    @Inject
    lateinit var nvrDao: NvrDao

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        // Log hardware decoder capabilities
        com.zektopic.frigate.media.RealStreamDecoder.logHardwareDecoderCapabilities()
        HevcDecoderChecker.logHevcCapabilities()

        // Initialize Clip Recorder with event recording
        Log.i(tag, "Initializing ClipRecorder with EventRecorder...")
        clipRecorder = ClipRecorder(this, nvrDao)

        // Initialize AI Detection Pipeline
        Log.i(tag, "Initializing Frigate AI Detection Pipeline...")
        detectionPipeline = DetectionPipeline(this, nvrDao)
        detectionPipeline?.initialize()

        // Initialize and start embedded Ktor web server
        webServer = com.zektopic.frigate.server.EmbeddedWebServer(this, nvrDao)
        webServer?.start()

        Log.i(tag, "NVR Foreground Service created. Detection pipeline ready.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        // Show persistent status notification
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
                nvrDao.getAllCameraConfigsFlow().collect { cameras ->
                    val activeCameras = cameras.filter { it.isEnabled }
                    withContext(Dispatchers.Main) {
                        if (activeCameras.isEmpty()) {
                            updateNotification("No active cameras configured. Configure in App UI.")
                            synchronized(activeIngesters) {
                                for (ingester in activeIngesters.values) {
                                    ingester.stop()
                                }
                                activeIngesters.clear()
                            }
                        } else {
                            val aiStatus = if (detectionPipeline?.isAiReady() == true) "AI" else "Motion-Only"
                            updateNotification("Monitoring ${activeCameras.size} active stream(s) [$aiStatus mode]")
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
        serviceScope.cancel()

        // Stop clip recorders
        clipRecorder?.stopAll()
        clipRecorder = null

        // Safely stop all stream ingesters
        synchronized(activeIngesters) {
            for ((cameraId, ingester) in activeIngesters) {
                Log.d(tag, "Stopping ingester stream for camera: $cameraId")
                ingester.stop()
            }
            activeIngesters.clear()
        }

        // Shutdown the AI detection pipeline
        detectionPipeline?.shutdown()
        detectionPipeline = null

        // Release resources
        latestFramesMap.clear()

        // Stop web server
        webServer?.stop()
        webServer = null

        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun startActiveStreams(cameras: List<CameraConfigEntity>) {
        synchronized(activeIngesters) {
            // Stop any existing streams first
            for (ingester in activeIngesters.values) {
                ingester.stop()
            }
            activeIngesters.clear()

            // Initialize new ingesters with AI pipeline integration
            for (camera in cameras) {
                val ingester = StreamIngester(this, camera) { frameBitmap ->
                    // Cache frame for UI and Ktor clients
                    latestFramesMap[camera.id] = frameBitmap
                    _frameFlow.tryEmit(Pair(camera.id, frameBitmap))

                    // Feed frame to recording ring buffer
                    clipRecorder?.feedFrameForPreBuffer(camera.id, frameBitmap)

                    // Route frame through the full AI detection pipeline
                    serviceScope.launch(Dispatchers.Default) {
                        detectionPipeline?.processFrame(camera, frameBitmap)
                    }
                }
                activeIngesters[camera.id] = ingester
                ingester.start()
            }
        }
    }

    /**
     * Get current detection pipeline stats for the dashboard
     */
    fun getDetectionStats(): DetectionPipeline.PipelineStats {
        return detectionPipeline?.getStats() ?: DetectionPipeline.PipelineStats()
    }

    fun isAiEnabled(): Boolean = detectionPipeline?.isAiReady() ?: false

    private fun createStatusNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, FrigateApp.CHANNEL_NVR_STATUS)
            .setContentTitle("Frigate NVR")
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
