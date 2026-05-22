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
import com.zektopic.frigate.media.StreamIngester
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

    // Motion variables
    private val motionDetectors = mutableMapOf<String, com.zektopic.frigate.ai.MotionDetector>()
    private val lastEventTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    // Embedded Ktor Web Server
    private var webServer: com.zektopic.frigate.server.EmbeddedWebServer? = null

    @Inject
    lateinit var nvrDao: NvrDao

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
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
                                motionDetectors.clear()
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

    private fun startActiveStreams(cameras: List<CameraConfigEntity>) {
        synchronized(activeIngesters) {
            // Stop any existing streams first
            for (ingester in activeIngesters.values) {
                ingester.stop()
            }
            activeIngesters.clear()
            motionDetectors.clear()

            // Initialize new ingesters
            for (camera in cameras) {
                // Initialize a motion detector for this camera with specific thresholds
                motionDetectors[camera.id] = com.zektopic.frigate.ai.MotionDetector(camera.motionThreshold)

                val ingester = StreamIngester(this, camera) { frameBitmap ->
                    // Cache frame and emit for Compose/Ktor clients
                    latestFramesMap[camera.id] = frameBitmap
                    _frameFlow.tryEmit(Pair(camera.id, frameBitmap))

                    // Route frame through the gated AI pipeline in a background thread
                    serviceScope.launch(Dispatchers.Default) {
                        processIncomingFrame(camera.id, frameBitmap)
                    }
                }
                activeIngesters[camera.id] = ingester
                ingester.start()
            }
        }
    }

    private suspend fun processIncomingFrame(cameraId: String, bitmap: android.graphics.Bitmap) {
        val motionDetector = motionDetectors[cameraId] ?: return
        
        // 1. Run low-power motion detection pre-filter
        val hasMotion = motionDetector.detectMotion(bitmap)
        if (!hasMotion) return

        // 2. Throttle event logging to once every 10 seconds per camera
        val currentTime = System.currentTimeMillis()
        val lastTime = lastEventTime[cameraId] ?: 0L
        if (currentTime - lastTime >= 10000L) {
            lastEventTime[cameraId] = currentTime
            
            // Insert event into local database
            nvrDao.insertEvent(
                com.zektopic.frigate.data.EventEntity(
                    cameraId = cameraId,
                    label = "motion",
                    confidence = 1.0f,
                    timestamp = currentTime,
                    snapshotPath = null,
                    videoPath = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    zone = "Main Zone"
                )
            )

            // Trigger system alerts for motion detection
            triggerSystemAlert(cameraId, "motion", 1.0f)
        }
    }

    private fun triggerSystemAlert(cameraId: String, label: String, confidence: Float) {
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
