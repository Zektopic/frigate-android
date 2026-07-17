package com.zektopic.frigate.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.NvrDao
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Motion-triggered MP4 clip recording and snapshot capture.
 *
 * A recording session is opened per camera on motion ([onMotion]); every frame
 * that arrives while a session is active is encoded ([offerFrame]). The session
 * finalizes after [TAIL_MS] of no motion or once [MAX_CLIP_MS] is reached,
 * producing a playable MP4 in the app's external files dir. The completed clip's
 * path is delivered via [onClipFinished] so the caller can attach it to an event.
 */
class ClipRecorder(
    private val context: Context,
    private val nvrDao: NvrDao
) {
    private val tag = "ClipRecorder"

    private val baseRecordingsDir: File by lazy {
        File(context.getExternalFilesDir(null), "frigate_recordings").apply { if (!exists()) mkdirs() }
    }

    private data class Session(
        val encoder: VideoClipEncoder,
        val file: File,
        val startedAt: Long,
        @Volatile var lastMotionAt: Long
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    companion object {
        private const val TAIL_MS = 6_000L       // keep recording this long after motion stops
        private const val MAX_CLIP_MS = 60_000L  // hard cap on a single clip
    }

    /** Signal that motion was just detected on this camera — opens or extends a recording. */
    @Synchronized
    fun onMotion(camera: CameraConfigEntity, firstFrame: Bitmap) {
        val now = System.currentTimeMillis()
        val existing = sessions[camera.id]
        if (existing != null) {
            existing.lastMotionAt = now
            return
        }
        val file = File(baseRecordingsDir, "${camera.id}_${now}.mp4")
        val w = if (firstFrame.width > 0) firstFrame.width else camera.detectWidth
        val h = if (firstFrame.height > 0) firstFrame.height else camera.detectHeight
        val encoder = VideoClipEncoder(w, h, camera.fps.coerceAtLeast(1), file)
        if (!encoder.start()) {
            Log.e(tag, "Could not start clip encoder for ${camera.name}")
            return
        }
        sessions[camera.id] = Session(encoder, file, now, now)
        Log.i(tag, "Started recording clip for ${camera.name}: ${file.name}")
    }

    /** Feed a frame into an active session (no-op if the camera isn't recording). */
    fun offerFrame(cameraId: String, bitmap: Bitmap) {
        val session = sessions[cameraId] ?: return
        val now = System.currentTimeMillis()
        val motionExpired = now - session.lastMotionAt > TAIL_MS
        val tooLong = now - session.startedAt > MAX_CLIP_MS
        if (motionExpired || tooLong) {
            finishSession(cameraId, session)
        } else {
            session.encoder.encodeFrame(bitmap)
        }
    }

    /** Returns the completed clip path if a session for this camera just finalized, else null. */
    @Synchronized
    private fun finishSession(cameraId: String, session: Session): String? {
        if (sessions.remove(cameraId) == null) return null
        val ok = session.encoder.stop()
        return if (ok) {
            Log.i(tag, "Finished clip ${session.file.name} (${session.file.length()} bytes)")
            session.file.absolutePath
        } else {
            session.file.delete()
            Log.w(tag, "Clip for $cameraId produced no playable file; discarded")
            null
        }
    }

    /** Poll for any sessions that have gone idle and finalize them; returns finished (cameraId, path) pairs. */
    fun reapIdleSessions(): List<Pair<String, String>> {
        val finished = mutableListOf<Pair<String, String>>()
        val now = System.currentTimeMillis()
        for ((cameraId, session) in sessions) {
            if (now - session.lastMotionAt > TAIL_MS || now - session.startedAt > MAX_CLIP_MS) {
                finishSession(cameraId, session)?.let { finished.add(cameraId to it) }
            }
        }
        return finished
    }

    /** Stop and finalize all recordings (service shutdown). */
    fun stopAll() {
        for ((cameraId, session) in sessions.toMap()) {
            finishSession(cameraId, session)
        }
    }

    fun saveEventSnapshot(cameraId: String, label: String, bitmap: Bitmap): File? {
        val destFile = File(baseRecordingsDir, "${cameraId}_${label}_${System.currentTimeMillis()}.jpg")
        return try {
            FileOutputStream(destFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            destFile
        } catch (e: IOException) {
            Log.e(tag, "Failed to save event snapshot", e)
            null
        }
    }

    suspend fun enforceRetentionPolicy(camera: CameraConfigEntity) {
        try {
            val expirationLimitMs = System.currentTimeMillis() - (camera.recordingRetentionDays * 24 * 60 * 60 * 1000).toLong()
            val files = baseRecordingsDir.listFiles { _, name -> name.startsWith("${camera.id}_") } ?: emptyArray()
            var purged = 0
            for (file in files) {
                val parts = file.nameWithoutExtension.split("_")
                val timestamp = parts.lastOrNull()?.toLongOrNull()
                if (timestamp != null && timestamp < expirationLimitMs && file.delete()) purged++
            }
            nvrDao.deleteEventsOlderThan(expirationLimitMs)
            if (purged > 0) Log.i(tag, "Purged $purged expired recording(s) for ${camera.name}")
        } catch (e: Exception) {
            Log.e(tag, "Retention sweep error", e)
        }
    }
}
