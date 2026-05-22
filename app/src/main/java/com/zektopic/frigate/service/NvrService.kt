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
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class NvrService : Service(), LifecycleOwner {

    private val tag = "NvrService"
    private val binder = NvrBinder()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeIngesters = mutableMapOf<String, StreamIngester>()
    private val lifecycleRegistry = LifecycleRegistry(this)

    // Motion and AI Object Detection variables
    private val motionDetectors = mutableMapOf<String, com.zektopic.frigate.ai.MotionDetector>()
    private var objectDetector: com.zektopic.frigate.ai.ObjectDetector? = null

    // Embedded Ktor Web Server
    private var webServer: com.zektopic.frigate.server.EmbeddedWebServer? = null

    @Inject
    lateinit var nvrDao: NvrDao

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        // Initialize AI Object Detector
        objectDetector = com.zektopic.frigate.ai.ObjectDetector(this)

        // Initialize and start embedded Ktor web server
        webServer = com.zektopic.frigate.server.EmbeddedWebServer(this, nvrDao)
        webServer?.start()

        Log.i(tag, "NVR Foreground Service created. AI engine and Web Server initialized.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        // Show persistent status notification to run in background
        startForeground(1, createStatusNotification("Initializing NVR streams..."))

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

        // Release AI detector resources
        objectDetector?.close()
        objectDetector = null
        motionDetectors.clear()

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

        // 2. Motion occurred! Trigger the hardware-accelerated TFLite Object Detector
        val detections = objectDetector?.detectObjects(bitmap, confidenceThreshold = 0.5f) ?: emptyList()
        if (detections.isEmpty()) return

        // 3. Object detected! Fire alerts (Event database logging disabled for now)
        for (detection in detections) {
            Log.i(tag, "[$cameraId] AI Detected: ${detection.label} (${detection.confidence * 100}%) at ${detection.boundingBox}")
            
            /*
            // Insert event into Room database (Disabled for stability)
            val eventId = nvrDao.insertEvent(
                com.zektopic.frigate.data.EventEntity(
                    cameraId = cameraId,
                    label = detection.label,
                    confidence = detection.confidence,
                    timestamp = System.currentTimeMillis(),
                    snapshotPath = null, // Path to JPG
                    videoPath = null,    // Path to MP4
                    zone = null
                )
            )
            */

            // Trigger system alerts for high confidence key targets (person, car)
            if (detection.label == "person" || detection.label == "car") {
                triggerSystemAlert(cameraId, detection.label, detection.confidence)
            }
        }
    }

    private fun triggerSystemAlert(cameraId: String, label: String, confidence: Float) {
        val notificationManager = androidx.core.app.NotificationManagerCompat.from(this)
        
        // Validate runtime permissions
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val permissionState = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (permissionState != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        }

        val text = "A ${label.uppercase()} was detected on camera '$cameraId' (${(confidence * 100).toInt()}% confidence)."
        val builder = androidx.core.app.NotificationCompat.Builder(this, FrigateApp.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Frigate Alert: ${label.uppercase()}")
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
