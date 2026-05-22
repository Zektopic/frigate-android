package com.zektopic.frigate.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zektopic.frigate.FrigateApp
import com.zektopic.frigate.MainActivity
import java.io.File

class AlertManager(private val context: Context) {

    private val tag = "AlertManager"

    /**
     * Posts a high-priority system alert with an optional image snapshot preview of the detected object.
     */
    fun postDetectionAlert(cameraId: String, label: String, confidence: Float, snapshotFile: File?) {
        val notificationManager = NotificationManagerCompat.from(context)

        // Validate runtime notification permissions
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val permissionState = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (permissionState != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "Missing POST_NOTIFICATIONS permission. Unable to display system alert.")
                return
            }
        }

        // Action when notification is tapped (open app dashboard)
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val alertTitle = "Frigate Alert: ${label.uppercase()} Detected"
        val alertText = "A ${label.lowercase()} was detected on '$cameraId' with ${(confidence * 100).toInt()}% confidence."

        // Configure builder details
        val builder = NotificationCompat.Builder(context, FrigateApp.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(alertTitle)
            .setContentText(alertText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        // If a physical snapshot was recorded on disk, embed it as a rich BigPictureStyle preview
        if (snapshotFile != null && snapshotFile.exists()) {
            try {
                val bitmap = BitmapFactory.decodeFile(snapshotFile.absolutePath)
                if (bitmap != null) {
                    builder.setLargeIcon(bitmap)
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as android.graphics.Bitmap?)
                            .setBigContentTitle(alertTitle)
                            .setSummaryText(alertText)
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to decode event snapshot image for notification preview", e)
            }
        }

        // Fire notification
        val notificationId = label.hashCode() + cameraId.hashCode() + System.currentTimeMillis().toInt()
        try {
            notificationManager.notify(notificationId, builder.build())
            Log.d(tag, "Successfully posted detection notification alert for: $label")
        } catch (e: SecurityException) {
            Log.e(tag, "Notification security exception posting alert", e)
        }
    }
}
