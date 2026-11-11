package com.zektopic.frigate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.zektopic.frigate.data.ConfigMigrations
import com.zektopic.frigate.data.NvrDao
import com.zektopic.frigate.data.SystemConfigEntity
import com.zektopic.frigate.data.YamlConfigParser
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FrigateApp : Application() {

    @Inject
    lateinit var nvrDao: NvrDao

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        appScope.launch { migrateStoredConfig() }
    }

    /**
     * One-off rewrites of a config stored by an earlier version. Each runs once, is
     * recorded as done even when it had nothing to change, and never blocks startup.
     */
    private suspend fun migrateStoredConfig() {
        val prefs = getSharedPreferences("config_migrations", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MOTION_THRESHOLD, false)) return
        try {
            val stored = nvrDao.getSystemConfig()?.configYaml
            val migrated = stored?.let { ConfigMigrations.dropLegacyMotionThresholds(it) }
            if (migrated != null) {
                nvrDao.applyConfig(SystemConfigEntity(configYaml = migrated), YamlConfigParser.parseConfig(migrated))
                Log.i("FrigateApp", "Dropped motion thresholds written under the old percent meaning")
            }
            prefs.edit().putBoolean(KEY_MOTION_THRESHOLD, true).apply()
        } catch (e: Exception) {
            // Left unmarked, so the next start tries again.
            Log.e("FrigateApp", "Config migration failed", e)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // NVR Status Notifications
            val statusChannel = NotificationChannel(
                CHANNEL_NVR_STATUS,
                "NVR Service Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows NVR background processing state"
            }

            // Alerts. The channel id is unchanged; only the name the user sees is corrected.
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Motion alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when a camera raises an alert"
                enableLights(true)
                enableVibration(true)
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(statusChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val CHANNEL_NVR_STATUS = "nvr_status_channel"
        const val CHANNEL_ALERTS = "nvr_alerts_channel"
        private const val KEY_MOTION_THRESHOLD = "motion_threshold_semantics"
    }
}
