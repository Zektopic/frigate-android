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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var nvrDao: NvrDao

    override fun onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState)

        setContent {
            FrigateAndroidTheme {
                val cameraConfigs by nvrDao.getAllCameraConfigsFlow().collectAsState(initial = emptyList())
                val events by nvrDao.getAllEventsFlow().collectAsState(initial = emptyList())
                var isServiceRunning by remember { mutableStateOf(false) }

                // Periodically check if background service is running
                LaunchedEffect(Unit) {
                    while (true) {
                        isServiceRunning = isServiceRunning(NvrService::class.java)
                        kotlinx.coroutines.delay(1000)
                    }
                }

                DashboardScreen(
                    cameraConfigs = cameraConfigs,
                    events = events,
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
        // Mock feeds reflecting typical configurations
        val frontCamera = CameraConfigEntity(
            id = "front_camera",
            name = "Front Driveway",
            rtspUrl = "rtsp://admin:passwd@192.168.1.33:554/live/ch0",
            isEnabled = true,
            detectWidth = 640,
            detectHeight = 360,
            fps = 5
        )

        val backyardCamera = CameraConfigEntity(
            id = "back_garden",
            name = "Back Garden",
            rtspUrl = "rtsp://admin:manupa@192.168.1.20:554/live/ch0",
            isEnabled = true,
            detectWidth = 640,
            detectHeight = 360,
            fps = 5
        )

        val localDeviceCamera = CameraConfigEntity(
            id = "local_sensor",
            name = "Android Built-in Cam",
            rtspUrl = "", // Empty indicates physical sensor integration
            isEnabled = true,
            detectWidth = 640,
            detectHeight = 480,
            fps = 10
        )

        nvrDao.insertCameraConfig(frontCamera)
        nvrDao.insertCameraConfig(backyardCamera)
        nvrDao.insertCameraConfig(localDeviceCamera)

        // Seed some mock historical events
        val event1 = EventEntity(
            cameraId = "front_camera",
            label = "person",
            confidence = 0.94f,
            timestamp = System.currentTimeMillis() - 50000,
            snapshotPath = null,
            videoPath = null,
            zone = "Driveway Zone"
        )
        val event2 = EventEntity(
            cameraId = "back_garden",
            label = "car",
            confidence = 0.81f,
            timestamp = System.currentTimeMillis() - 120000,
            snapshotPath = null,
            videoPath = null,
            zone = "Garden Entry"
        )
        nvrDao.insertEvent(event1)
        nvrDao.insertEvent(event2)
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
}
