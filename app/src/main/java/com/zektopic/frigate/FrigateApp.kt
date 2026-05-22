package com.zektopic.frigate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FrigateApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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

            // AI Object Detection Alerts
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "AI Event Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when key objects (people, vehicles) are detected"
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
    }
}
