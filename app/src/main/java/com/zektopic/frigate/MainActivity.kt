package com.zektopic.frigate

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.data.NvrDao
import com.zektopic.frigate.service.NvrService
import com.zektopic.frigate.ui.dashboard.DashboardScreen
import com.zektopic.frigate.ui.theme.FrigateAndroidTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.ServiceConnection
import android.content.ComponentName
import android.os.IBinder
import android.graphics.Bitmap

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var nvrDao: NvrDao

    private var nvrService by mutableStateOf<NvrService?>(null)
    private var isBound by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NvrService.NvrBinder
            nvrService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            nvrService = null
            isBound = false
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, NvrService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request required permissions for notifications and camera access
        val requiredPermissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requiredPermissions.add(android.Manifest.permission.CAMERA)
        }
        if (requiredPermissions.isNotEmpty()) {
            requestPermissions(requiredPermissions.toTypedArray(), 101)
        }

        setContent {
            FrigateAndroidTheme {
                val cameraConfigs by nvrDao.getAllCameraConfigsFlow().collectAsState(initial = emptyList())
                val events by nvrDao.getAllEventsFlow().collectAsState(initial = emptyList())
                val systemConfig by nvrDao.getSystemConfigFlow().collectAsState(initial = null)
                var isServiceRunning by remember { mutableStateOf(false) }
                val latestFrames = remember { mutableStateMapOf<String, Bitmap>() }

                // Periodically check if background service is running and auto-start it
                LaunchedEffect(Unit) {
                    startNvrService()
                    while (true) {
                        isServiceRunning = isServiceRunning(NvrService::class.java)
                        kotlinx.coroutines.delay(1000)
                    }
                }

                // Collect frames from active bound service
                LaunchedEffect(nvrService) {
                    nvrService?.let { service ->
                        service.frameFlow.collect { (cameraId, bitmap) ->
                            latestFrames[cameraId] = bitmap
                        }
                    } ?: latestFrames.clear()
                }

                // Auto-seed database with mock cameras and events if empty
                LaunchedEffect(cameraConfigs) {
                    if (cameraConfigs.isEmpty()) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            seedMockCameras()
                        }
                    }
                }

                DashboardScreen(
                    cameraConfigs = cameraConfigs,
                    events = events,
                    systemConfig = systemConfig,
                    latestFrames = latestFrames,
                    onSaveConfig = { yamlText ->
                        val parsedCameras = com.zektopic.frigate.data.YamlConfigParser.parseConfig(yamlText)
                        nvrDao.insertSystemConfig(com.zektopic.frigate.data.SystemConfigEntity(configYaml = yamlText))
                        val existing = nvrDao.getAllCameraConfigs()
                        for (c in existing) {
                            nvrDao.deleteCameraConfig(c)
                        }
                        for (c in parsedCameras) {
                            nvrDao.insertCameraConfig(c)
                        }
                    },
                    onAddMockCamera = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            seedMockCameras()
                        }
                    },
                    onTriggerTestNotification = {
                        triggerTestAlertNotification()
                    },
                    onStartNvrService = {
                        startNvrService()
                    },
                    onStopNvrService = {
                        stopNvrService()
                    },
                    isServiceRunning = isServiceRunning
                )
            }
        }
    }

    private fun startNvrService() {
        val intent = Intent(this, NvrService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopNvrService() {
        val intent = Intent(this, NvrService::class.java)
        stopService(intent)
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private suspend fun seedMockCameras() {
        val defaultYaml = """
            version: 0.18.0

            mqtt:
              host: 192.168.1.10
              port: 1883

            detect:
              width: 640
              height: 360
              fps: 5

            objects:
              track:
                - person
                - car
                - bird

            cameras:
              front_camera:
                ffmpeg:
                  inputs:
                    - path: rtsp://root:3d9PBN2jqOvE@192.168.1.33/stream=1
                      roles:
                        - detect
                        - record
                record:
                  enabled: true
                  retain:
                    days: 3
                snapshots:
                  enabled: true

              work_camera:
                ffmpeg:
                  inputs:
                    - path: rtsp://10.50.0.63:8554/stream
                      roles:
                        - detect
                        - record
                record:
                  enabled: true
                  retain:
                    days: 0.5
                snapshots:
                  enabled: true
                      
              work_room:
                ffmpeg:
                  inputs:
                    - path: rtsp://10.50.0.243:8554/stream
                      roles:
                        - detect
                        - record
                record:
                  enabled: true
                  retain:
                    days: 0.5
                snapshots:
                  enabled: true

              stairway_camera:
                ffmpeg:
                  inputs:
                    - path: rtsp://root:3d9PBN2jqOvE@192.168.1.34/stream=1
                      roles:
                        - detect
                        - record
                record:
                  enabled: true
                  retain:
                    days: 3
                snapshots:
                  enabled: true
              
              back_garden:
                ffmpeg:
                  inputs:
                    - path: rtsp://admin:manupa@192.168.1.20:554/live/ch1
                      roles:
                        - detect
                        - record
                record:
                  enabled: true
                  retain:
                    days: 3
                snapshots:
                  enabled: true

              kitchen_camera:
                ffmpeg:
                  inputs:
                    - path: rtsp://admin:manupa@192.168.1.17:554/live/ch1
                      roles:
                        - detect
                        - record
                record:
                  enabled: true
                  retain:
                    days: 3
                snapshots:
                  enabled: true
        """.trimIndent()

        nvrDao.insertSystemConfig(com.zektopic.frigate.data.SystemConfigEntity(configYaml = defaultYaml))

        val parsedCameras = com.zektopic.frigate.data.YamlConfigParser.parseConfig(defaultYaml)
        val existing = nvrDao.getAllCameraConfigs()
        for (c in existing) {
            nvrDao.deleteCameraConfig(c)
        }
        for (camera in parsedCameras) {
            nvrDao.insertCameraConfig(camera)
        }

        // Seed some mock historical events with real demo video urls for playback testing
        val videoUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"
        val event1 = EventEntity(
            cameraId = "front_camera",
            label = "motion",
            confidence = 1.00f,
            timestamp = System.currentTimeMillis() - 50000,
            snapshotPath = null,
            videoPath = videoUrl,
            zone = "Driveway Zone"
        )
        val event2 = EventEntity(
            cameraId = "back_garden",
            label = "motion",
            confidence = 1.00f,
            timestamp = System.currentTimeMillis() - 120000,
            snapshotPath = null,
            videoPath = videoUrl,
            zone = "Garden Entry"
        )
        val event3 = EventEntity(
            cameraId = "front_camera",
            label = "motion",
            confidence = 1.00f,
            timestamp = System.currentTimeMillis() - 86400000 - 3600000, // yesterday, 1 hour ago
            snapshotPath = null,
            videoPath = videoUrl,
            zone = "Driveway Zone"
        )
        nvrDao.insertEvent(event1)
        nvrDao.insertEvent(event2)
        nvrDao.insertEvent(event3)
    }

    private fun triggerTestAlertNotification() {
        val notificationManager = NotificationManagerCompat.from(this)
        
        // Android 13+ requires post notification runtime permissions, handled gracefully
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val permissionState = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (permissionState != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                return
            }
        }

        val builder = NotificationCompat.Builder(this, FrigateApp.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Frigate Alert: Person Detected")
            .setContentText("A person entered the Front Driveway (Zone: Gate)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        try {
            notificationManager.notify(2, builder.build())
            
            // Log it in Room
            lifecycleScope.launch(Dispatchers.IO) {
                nvrDao.insertEvent(
                    EventEntity(
                        cameraId = "front_camera",
                        label = "person",
                        confidence = 0.96f,
                        timestamp = System.currentTimeMillis(),
                        snapshotPath = null,
                        videoPath = null,
                        zone = "Gate Zone"
                    )
                )
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            // Restart NVR service to pick up newly granted camera/notification permissions
            startNvrService()
        }
    }
}
