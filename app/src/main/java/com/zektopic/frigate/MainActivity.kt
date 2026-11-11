package com.zektopic.frigate

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
import com.zektopic.frigate.data.AppPreferences
import com.zektopic.frigate.data.RecordingStore
import com.zektopic.frigate.data.SystemConfigEntity
import com.zektopic.frigate.data.UiSettings
import com.zektopic.frigate.data.YamlConfigParser
import com.zektopic.frigate.service.NvrRunState
import com.zektopic.frigate.ui.FrigateAppShell
import com.zektopic.frigate.ui.NvrActions
import com.zektopic.frigate.ui.NvrUiState
import kotlinx.coroutines.flow.flowOf
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
        } else {
            maybeAskToIgnoreBatteryOptimizations()
        }

        openReviewRequest = if (intent?.getBooleanExtra(EXTRA_OPEN_REVIEW, false) == true) 1 else 0
        val appPreferences = AppPreferences(applicationContext)

        setContent {
            val uiSettings by appPreferences.uiSettings.collectAsState(initial = UiSettings())
            FrigateAndroidTheme(themeMode = uiSettings.themeMode) {
                val cameraConfigs by nvrDao.getAllCameraConfigsFlow().collectAsState(initial = emptyList())
                val events by nvrDao.getRecentEventsFlow(MAX_EVENTS_IN_UI).collectAsState(initial = emptyList())
                val systemConfig by nvrDao.getSystemConfigFlow().collectAsState(initial = null)
                val latestFrames = remember { mutableStateMapOf<String, Bitmap>() }
                val service = nvrService

                // The bound service's state, or an empty value while nothing is bound.
                val streamStates by remember(service) { service?.streamStates ?: flowOf(emptyMap()) }
                    .collectAsState(initial = emptyMap())
                val activeMotion by remember(service) { service?.activeMotion ?: flowOf(emptySet()) }
                    .collectAsState(initial = emptySet())
                val activeAlerts by remember(service) { service?.activeAlerts ?: flowOf(emptySet()) }
                    .collectAsState(initial = emptySet())
                val overBudget by remember(service) { service?.camerasOverBudget ?: flowOf(emptyList()) }
                    .collectAsState(initial = emptyList())
                val stats by remember(service) { service?.stats ?: flowOf(emptyList()) }
                    .collectAsState(initial = emptyList())
                val isServiceRunning by remember(service) { service?.running ?: flowOf(false) }
                    .collectAsState(initial = false)

                // Start the NVR with the app, unless the user stopped it themselves.
                LaunchedEffect(Unit) {
                    if (NvrRunState.shouldRun(this@MainActivity)) startNvrService()
                }

                // Collect frames from active bound service
                LaunchedEffect(service) {
                    if (service == null) {
                        latestFrames.clear()
                    } else {
                        service.frameFlow.collect { (cameraId, bitmap) -> latestFrames[cameraId] = bitmap }
                    }
                }
                // A stopped stream leaves its last frame behind; drop frames for
                // cameras that are no longer running so a tile cannot show a stale one.
                LaunchedEffect(streamStates.keys) {
                    latestFrames.keys.retainAll(streamStates.keys)
                }

                // Seed the default config exactly once, on genuine first run.
                // IMPORTANT: gate on the real DB (getSystemConfig() == null), NOT on
                // the collected `cameraConfigs` — that starts as emptyList() on every
                // launch before Room loads, so seeding there would overwrite the
                // user's saved config with defaults on every restart.
                LaunchedEffect(Unit) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        if (nvrDao.getSystemConfig() == null) {
                            seedMockCameras()
                        }
                    }
                }

                val actions = remember(service) {
                    object : NvrActions {
                        override suspend fun saveConfig(yaml: String) {
                            val parsedCameras = YamlConfigParser.parseConfig(yaml)
                            // Atomic replace — the service never sees a momentary empty camera list
                            nvrDao.applyConfig(SystemConfigEntity(configYaml = yaml), parsedCameras)
                        }

                        override fun startService() = startNvrService()
                        override fun stopService() = stopNvrService()
                        override fun sendTestNotification() = triggerTestAlertNotification()

                        override suspend fun clearAllRecordings() {
                            // Files are deleted by the Storage page; clear the event log too
                            nvrDao.deleteAllEvents()
                        }

                        override fun setReviewed(eventIds: List<Long>, reviewed: Boolean) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                // SQLite caps bound variables per statement
                                eventIds.chunked(500).forEach { nvrDao.setEventsReviewed(it, reviewed) }
                            }
                        }

                        override fun deleteEvents(events: List<EventEntity>) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val store = RecordingStore(applicationContext)
                                for (event in events) {
                                    event.videoPath?.let { store.delete(it) }
                                    event.snapshotPath?.let { store.delete(it) }
                                }
                                events.map { it.id }.chunked(500).forEach { nvrDao.deleteEvents(it) }
                            }
                        }

                        override fun motionBoxes(cameraId: String) = service?.getMotionBoxes(cameraId).orEmpty()

                        override fun updateUi(settings: UiSettings) {
                            lifecycleScope.launch { appPreferences.setUiSettings(settings) }
                        }
                    }
                }

                FrigateAppShell(
                    state = NvrUiState(
                        cameras = cameraConfigs,
                        events = events,
                        configYaml = systemConfig?.configYaml ?: "",
                        streamStates = streamStates,
                        activeMotion = activeMotion,
                        activeAlerts = activeAlerts,
                        camerasOverBudget = overBudget,
                        stats = stats,
                        isServiceRunning = isServiceRunning,
                        ui = uiSettings
                    ),
                    frames = latestFrames,
                    actions = actions,
                    openReviewRequest = openReviewRequest
                )
            }
        }
    }

    /** Bumped each time an alert notification is tapped, so the UI can react to each tap. */
    private var openReviewRequest by mutableIntStateOf(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_REVIEW, false)) openReviewRequest++
    }

    private fun startNvrService() {
        com.zektopic.frigate.service.NvrRunState.setShouldRun(this, true)
        val intent = Intent(this, NvrService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopNvrService() {
        // An explicit Stop also keeps BootReceiver from restarting it after a reboot
        com.zektopic.frigate.service.NvrRunState.setShouldRun(this, false)
        // Through the binder when there is one: stopService cannot stop a bound service.
        nvrService?.stopNvr() ?: stopService(Intent(this, NvrService::class.java))
    }

    /**
     * Asks once to exempt the app from battery optimization: Doze and app standby
     * otherwise defer the network access and wakeups an always-on NVR depends on.
     * Asked after the runtime permissions so the two system dialogs don't stack.
     */
    @android.annotation.SuppressLint("BatteryLife")
    private fun maybeAskToIgnoreBatteryOptimizations() {
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("battery_optimization", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit().putBoolean("asked", true).apply()
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (e: android.content.ActivityNotFoundException) {
            android.util.Log.w("MainActivity", "No screen to request the battery optimization exemption", e)
        }
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

            # All cameras are pulled through the go2rtc restreamer on the home
            # server. Direct camera URLs exhaust the cameras' 1-2 RTSP session
            # slots (already held by Frigate/birdseye) — DESCRIBE succeeds but
            # no RTP data ever flows, so the app must use go2rtc like every
            # other consumer.
            cameras:
              front_camera:
                ffmpeg:
                  inputs:
                    - path: rtsp://192.168.1.9:8554/front_camera
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
                    - path: rtsp://192.168.1.9:8554/work_camera
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
                    - path: rtsp://192.168.1.9:8554/work_room
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
                    - path: rtsp://192.168.1.9:8554/stairway_camera
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
                    - path: rtsp://192.168.1.9:8554/back_garden?video=h264
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
                    - path: rtsp://192.168.1.9:8554/kitchen_camera?video=h264
                      roles:
                        - detect
                        - record
                record:
                  enabled: true
                  retain:
                    days: 3
                snapshots:
                  enabled: true

              living_room:
                ffmpeg:
                  inputs:
                    - path: rtsp://192.168.1.9:8554/living_room
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

        val parsedCameras = com.zektopic.frigate.data.YamlConfigParser.parseConfig(defaultYaml)
        nvrDao.applyConfig(
            com.zektopic.frigate.data.SystemConfigEntity(configYaml = defaultYaml),
            parsedCameras
        )

        // Events are created at runtime by the NVR service when motion is detected,
        // with real locally-recorded MP4 clips attached. No mock events are seeded.
    }

    private fun triggerTestAlertNotification() {
        // Android 13+ requires the notification permission at runtime
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            return
        }
        // Only the notification. This used to log a made-up "person, 96%" event as
        // well, which then sat on the Review page as if it had happened.
        com.zektopic.frigate.notification.AlertManager(this).postTestAlert()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            // Restart NVR service to pick up newly granted camera/notification permissions,
            // but not if the user has stopped it: a permission dialog is not a request to start.
            if (NvrRunState.shouldRun(this)) startNvrService()
            maybeAskToIgnoreBatteryOptimizations()
        }
    }

    companion object {
        /** Set on the intent an alert notification opens, to land on the Review page. */
        const val EXTRA_OPEN_REVIEW = "open_review"

        /** Newest events the UI observes; older ones stay on disk and in the API. */
        private const val MAX_EVENTS_IN_UI = 500
    }
}
