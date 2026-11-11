package com.zektopic.frigate.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zektopic.frigate.FrigateApp
import com.zektopic.frigate.MainActivity

class AlertManager(private val context: Context) {

    private val tag = "AlertManager"

    /**
     * Posts an alert for motion on a camera, with the triggering frame as a preview.
     *
     * One notification per camera, replaced in place: a camera that keeps seeing
     * motion updates its alert rather than stacking a new one every few seconds.
     */
    fun postMotionAlert(cameraId: String, cameraName: String, zones: List<String>, snapshot: Bitmap?) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val state = context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            if (state != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(tag, "Missing POST_NOTIFICATIONS permission; alert not shown")
                return
            }
        }

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_REVIEW, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, cameraId.hashCode(), tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = "Motion on $cameraName"
        val text = if (zones.isEmpty()) "Motion was detected."
        else "Motion was detected in ${zones.joinToString(", ") { it.replace('_', ' ') }}."

        val builder = NotificationCompat.Builder(context, FrigateApp.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        if (snapshot != null && !snapshot.isRecycled) {
            // Scaled copies, never the frame itself: a notification's bitmaps travel
            // through Binder, and a full detect frame attached twice (~1.8 MB at
            // 640x360) is enough to fail the post with TransactionTooLargeException.
            val picture = scaledCopy(snapshot, BIG_PICTURE_WIDTH)
            builder.setLargeIcon(scaledCopy(snapshot, LARGE_ICON_WIDTH))
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(title)
                    .setSummaryText(text)
            )
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_BASE + cameraId.hashCode(), builder.build())
        } catch (e: SecurityException) {
            Log.e(tag, "Not allowed to post the alert", e)
        }
    }

    private fun scaledCopy(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth) return source.copy(Bitmap.Config.ARGB_8888, false)
        val height = (source.height.toLong() * maxWidth / source.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, height, true)
    }

    /** A sample alert, so the user can check that notifications reach them. */
    fun postTestAlert() {
        postMotionAlert(TEST_CAMERA_ID, "Test alert", emptyList(), null)
    }

    private companion object {
        // Keeps alert ids clear of the foreground service's notification (id 1).
        const val NOTIFICATION_ID_BASE = 1000
        const val TEST_CAMERA_ID = "__test__"
        const val BIG_PICTURE_WIDTH = 480
        const val LARGE_ICON_WIDTH = 128
    }
}
