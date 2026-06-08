package com.zektopic.frigate.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.NvrDao
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Clip and snapshot recorder for Frigate Android.
 *
 * Handles:
 * - Event snapshot saving (JPEG)
 * - Retention policy enforcement
 * - Delegates to EventRecorder for real MP4 clip recording with pre-buffer
 */
class ClipRecorder(
    private val context: Context,
    private val nvrDao: NvrDao
) {
    private val tag = "ClipRecorder"

    // Directory for recordings in scoped storage
    private val baseRecordingsDir: File by lazy {
        File(context.getExternalFilesDir(null), "frigate_recordings").apply {
            if (!exists()) mkdirs()
        }
    }

    // Active EventRecorder instances per camera
    private val eventRecorders = mutableMapOf<String, EventRecorder>()

    /**
     * Get or create an EventRecorder for a specific camera.
     */
    fun getEventRecorder(cameraId: String, config: CameraConfigEntity? = null): EventRecorder {
        return eventRecorders.getOrPut(cameraId) {
            EventRecorder(
                context = context,
                cameraId = cameraId,
                preBufferSeconds = 5,
                postBufferSeconds = 3
            ).apply { start() }
        }
    }

    /**
     * Save a high-quality JPEG snapshot when an object is detected.
     * This is the primary snapshot path used by the DetectionPipeline.
     */
    fun saveEventSnapshot(cameraId: String, label: String, bitmap: Bitmap): File? {
        val timestamp = System.currentTimeMillis()
        val filename = "${cameraId}_${label}_${timestamp}.jpg"
        val destFile = File(baseRecordingsDir, filename)

        var fos: FileOutputStream? = null
        return try {
            fos = FileOutputStream(destFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            fos.flush()
            Log.d(tag, "Snapshot saved: ${destFile.absolutePath} (${destFile.length() / 1024}KB)")
            destFile
        } catch (e: IOException) {
            Log.e(tag, "Failed to save snapshot", e)
            null
        } finally {
            fos?.close()
        }
    }

    /**
     * Enforce retention schedules, purging obsolete files and database entries.
     */
    suspend fun enforceRetentionPolicy(camera: CameraConfigEntity) {
        try {
            val expirationLimitMs = System.currentTimeMillis() - (camera.recordingRetentionDays * 24 * 60 * 60 * 1000).toLong()

            // Delete files older than retention limit
            val files = baseRecordingsDir.listFiles { _, name ->
                name.startsWith(camera.id)
            } ?: emptyArray()

            var countPurged = 0
            for (file in files) {
                if (file.lastModified() < expirationLimitMs) {
                    if (file.delete()) countPurged++
                }
            }

            // Also purge via EventRecorder
            eventRecorders[camera.id]?.enforceRetention(camera.recordingRetentionDays)

            // Sync database
            nvrDao.deleteEventsOlderThan(expirationLimitMs)

            if (countPurged > 0) {
                Log.i(tag, "Retention: purged $countPurged file(s) for camera: ${camera.name}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Retention policy error", e)
        }
    }

    /**
     * Feed a decoded frame into the active EventRecorder for pre-buffer.
     * Called continuously by the stream ingester.
     */
    fun feedFrameForPreBuffer(cameraId: String, bitmap: Bitmap) {
        eventRecorders[cameraId]?.feedFrame(bitmap)
    }

    /**
     * Trigger event recording (snapshot + clip with pre/post buffer).
     * Called by DetectionPipeline when an object is detected.
     */
    fun triggerEventRecording(
        cameraId: String,
        eventType: String,
        confidence: Float,
        currentFrame: Bitmap
    ): Pair<String?, String?> {
        val recorder = eventRecorders[cameraId]
        return if (recorder != null) {
            recorder.triggerEvent(eventType, confidence, currentFrame)
        } else {
            Pair(saveEventSnapshot(cameraId, eventType, currentFrame)?.absolutePath, null)
        }
    }

    /**
     * Stop active event recording and finalize clip.
     */
    fun stopEventRecording(cameraId: String): EventRecorder.RecordingMetadata? {
        return eventRecorders[cameraId]?.stopEvent()
    }

    /**
     * Start all event recorders for active cameras.
     */
    fun startAll(cameras: List<CameraConfigEntity>) {
        for (camera in cameras) {
            getEventRecorder(camera.id, camera)
        }
    }

    /**
     * Stop all recorders and clean up.
     */
    fun stopAll() {
        for ((cameraId, recorder) in eventRecorders) {
            recorder.stop()
        }
        eventRecorders.clear()
    }
}
