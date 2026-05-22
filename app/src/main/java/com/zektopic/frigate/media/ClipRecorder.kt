package com.zektopic.frigate.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.NvrDao
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ClipRecorder(
    private val context: Context,
    private val nvrDao: NvrDao
) {
    private val tag = "ClipRecorder"
    
    // Directory mapping in scoped storage to keep things tidy
    private val baseRecordingsDir: File by lazy {
        File(context.getExternalFilesDir(null), "frigate_recordings").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Records a video segment or saves a high-quality frame snapshot when an object is detected.
     */
    fun saveEventSnapshot(cameraId: String, label: String, bitmap: Bitmap): File? {
        val filename = "${cameraId}_${label}_${System.currentTimeMillis()}.jpg"
        val destFile = File(baseRecordingsDir, filename)

        var fos: FileOutputStream? = null
        return try {
            fos = FileOutputStream(destFile)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            fos.flush()
            Log.d(tag, "Saved event snapshot on disk: ${destFile.absolutePath}")
            destFile
        } catch (e: IOException) {
            Log.e(tag, "Failed to save event snapshot to disk", e)
            null
        } finally {
            fos?.close()
        }
    }

    /**
     * Enforces video retention schedules, purging obsolete files and cleaning up Room database items.
     */
    suspend fun enforceRetentionPolicy(camera: CameraConfigEntity) {
        try {
            val expirationLimitMs = System.currentTimeMillis() - (camera.recordingRetentionDays * 24 * 60 * 60 * 1000).toLong()
            
            // Delete historical files from disk that exceed limit
            val filePrefix = "${camera.id}_"
            val files = baseRecordingsDir.listFiles { _, name -> name.startsWith(filePrefix) } ?: emptyArray()

            var countPurged = 0
            for (file in files) {
                // Parse timestamp out of standard naming structure e.g., camera_label_timestamp.jpg
                val nameParts = file.nameWithoutExtension.split("_")
                if (nameParts.size >= 3) {
                    val timestampStr = nameParts[2]
                    val timestamp = timestampStr.toLongOrNull()
                    if (timestamp != null && timestamp < expirationLimitMs) {
                        if (file.delete()) {
                            countPurged++
                        }
                    }
                }
            }

            // Sync database listings
            nvrDao.deleteEventsOlderThan(expirationLimitMs)
            
            if (countPurged > 0) {
                Log.i(tag, "Cleaned up $countPurged expired recording file(s) for camera: ${camera.name}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error performing file retention sweep", e)
        }
    }

    /**
     * Simulates MP4 clip recording initialization (using MediaMuxer).
     */
    fun recordEventClip(cameraId: String, durationSecs: Int): File? {
        val destFile = File(baseRecordingsDir, "${cameraId}_clip_${System.currentTimeMillis()}.mp4")
        
        try {
            // Setup MediaMuxer container for writing standard MP4 containers
            val muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            // In a real device, the NVR service writes encoded video frames from MediaCodec.
            // We simulate a basic configuration structure and start/stop the muxer container:
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 640, 360)
            // Add mock tracks and start
            Log.d(tag, "Created event clip container: ${destFile.absolutePath}")
            
            // We return the file placeholder representing the saved clip
            return destFile
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize MediaMuxer recording clip", e)
            return null
        }
    }
}
