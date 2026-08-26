package com.zektopic.frigate.media

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.NvrDao
import com.zektopic.frigate.data.RecordingStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Motion-triggered MP4 clip recording and snapshot capture.
 *
 * A recording session is opened per camera on motion ([onMotion]); every frame
 * that arrives while a session is active is encoded ([offerFrame]). The session
 * finalizes after [TAIL_MS] of no motion or once [MAX_CLIP_MS] is reached,
 * producing a playable MP4. The completed clip's ref — an absolute path, or a
 * document URI when the user has pointed recordings at an SD card or other
 * folder — is returned from [reapIdleSessions] so the caller can attach it to
 * an event.
 *
 * Encoding always targets a real file in [RecordingStore.stagingDir]; the clip
 * moves to its final destination in [reapIdleSessions]. See [RecordingStore] for
 * why the encoder never writes into a SAF tree directly.
 */
class ClipRecorder(
    private val context: Context,
    private val nvrDao: NvrDao,
    private val store: RecordingStore = RecordingStore(context)
) {
    private val tag = "ClipRecorder"

    private data class Session(
        val encoder: VideoClipEncoder,
        val file: File,
        val startedAt: Long,
        @Volatile var lastMotionAt: Long
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    /** When a camera's encoder last failed to start, so we don't retry it every frame. */
    private val lastStartFailureAt = ConcurrentHashMap<String, Long>()

    /**
     * Ceiling on simultaneous AVC encoders across every camera, taken from the
     * device budget rather than a constant: encoders share one codec pool with the
     * decoders, so the right number on a Helio G88 is not the right number on a
     * flagship. Cameras beyond the cap skip recording for that event - logged and
     * counted in [capRefusals] rather than dropped silently.
     */
    private val maxConcurrentEncoders: Int =
        DevicePerformance.budget(context).maxConcurrentEncoders

    /** Live encoder permits, bounding concurrent MediaCodec encoders across all cameras. */
    private val encoderPermits = java.util.concurrent.Semaphore(maxConcurrentEncoders)

    init {
        // Anything left in staging is from a process that died mid-encode: the muxer never
        // wrote its moov atom, so the file is unplayable. Publishing it would put junk in
        // the user's folder; drop it instead of letting it accumulate.
        val orphans = store.stagingDir.listFiles()?.count { it.isFile && it.delete() } ?: 0
        if (orphans > 0) Log.w(tag, "Discarded $orphans truncated clip(s) left in staging")
    }

    companion object {
        private const val TAIL_MS = 6_000L       // keep recording this long after motion stops
        private const val MAX_CLIP_MS = 60_000L  // hard cap on a single clip

        /**
         * A failed encoder start means the device is at its codec limit; retrying on
         * the very next motion frame (up to 5/s/camera) is what turns one failure into
         * a leak storm. Back off for this long before trying that camera again.
         */
        private const val START_FAILURE_COOLDOWN_MS = 30_000L

        /** Cumulative count of recordings skipped because the encoder cap was hit. */
        val capRefusals = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * Encoders currently mid-recording. Without this, /api/diag's leak figure
         * (created - released) counts every in-flight encoder as leaked, so a healthy
         * device recording two clips reports "2 leaked" - alarming and wrong.
         */
        val activeSessions = java.util.concurrent.atomic.AtomicInteger(0)
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
        val failedAt = lastStartFailureAt[camera.id]
        if (failedAt != null && now - failedAt < START_FAILURE_COOLDOWN_MS) return

        if (!encoderPermits.tryAcquire()) {
            capRefusals.incrementAndGet()
            Log.w(tag, "Not recording ${camera.name}: already at $maxConcurrentEncoders concurrent encoders")
            return
        }

        val file = File(store.stagingDir, "${camera.id}_${now}.mp4")
        val w = if (firstFrame.width > 0) firstFrame.width else camera.detectWidth
        val h = if (firstFrame.height > 0) firstFrame.height else camera.detectHeight
        val encoder = VideoClipEncoder(w, h, camera.fps.coerceAtLeast(1), file)
        if (!encoder.start()) {
            encoderPermits.release()
            lastStartFailureAt[camera.id] = now
            Log.e(tag, "Could not start clip encoder for ${camera.name}; backing off ${START_FAILURE_COOLDOWN_MS}ms")
            return
        }
        lastStartFailureAt.remove(camera.id)
        sessions[camera.id] = Session(encoder, file, now, now)
        activeSessions.incrementAndGet()
        Log.i(tag, "Started recording clip for ${camera.name}: ${file.name}")
    }

    /**
     * Feed a frame into an active session (no-op if the camera isn't recording).
     *
     * Called on the stream's EGL frame-extraction thread, so this must never block:
     * an expired session is simply skipped here and finalized by [reapIdleSessions]
     * within ~2s. Calling [finishSession] from this path would take the shared
     * ClipRecorder monitor and block the frame loop for seconds behind a concurrent
     * encoder start/stop, stalling `lastFrameTime` past the 8s freeze watchdog and
     * reconnecting a perfectly healthy stream.
     */
    fun offerFrame(cameraId: String, bitmap: Bitmap) {
        val session = sessions[cameraId] ?: return
        val now = System.currentTimeMillis()
        val motionExpired = now - session.lastMotionAt > TAIL_MS
        val tooLong = now - session.startedAt > MAX_CLIP_MS
        if (motionExpired || tooLong) return // reaper finalizes; never block this thread
        session.encoder.encodeFrame(bitmap)
    }

    /**
     * Close out a session and hand back its staged file, or null if the encoder produced
     * nothing playable.
     *
     * Deliberately does *not* publish: moving the clip to its destination can mean copying
     * tens of megabytes to an SD card, and this method holds the same monitor as [onMotion],
     * which runs on the frame path. Callers publish outside the lock.
     */
    @Synchronized
    private fun detachSession(cameraId: String, session: Session): File? {
        if (sessions.remove(cameraId) == null) return null
        activeSessions.decrementAndGet()
        val ok = try { session.encoder.stop() } finally { encoderPermits.release() }
        return if (ok) {
            session.file
        } else {
            session.file.delete()
            Log.w(tag, "Clip for $cameraId produced no playable file; discarded")
            null
        }
    }

    /** Poll for any sessions that have gone idle and finalize them; returns finished (cameraId, ref) pairs. */
    fun reapIdleSessions(): List<Pair<String, String>> {
        val finished = mutableListOf<Pair<String, String>>()
        val now = System.currentTimeMillis()
        for ((cameraId, session) in sessions) {
            if (now - session.lastMotionAt > TAIL_MS || now - session.startedAt > MAX_CLIP_MS) {
                val staged = detachSession(cameraId, session) ?: continue
                val bytes = staged.length()
                val ref = store.publish(staged, RecordingStore.MIME_MP4)
                if (ref != null) {
                    Log.i(tag, "Finished clip ${staged.name} ($bytes bytes) -> $ref")
                    finished.add(cameraId to ref)
                }
            }
        }
        return finished
    }

    /**
     * Stop and finalize all recordings (service shutdown, memory pressure).
     *
     * Stashes locally instead of publishing: both callers are on the main thread, and
     * `onTrimMemory` at CRITICAL is the worst possible moment to copy megabytes to an
     * SD card. The clips are not lost — they land in the default location, which is
     * listed, played and swept exactly like the custom one.
     */
    fun stopAll() {
        for ((cameraId, session) in sessions.toMap()) {
            detachSession(cameraId, session)?.let { store.stashLocally(it) }
        }
    }

    /** Writes a JPEG for an event thumbnail; returns its ref (path or document URI). */
    fun saveEventSnapshot(cameraId: String, label: String, bitmap: Bitmap): String? {
        val name = "${cameraId}_${label}_${System.currentTimeMillis()}.jpg"
        return store.write(name, RecordingStore.MIME_JPEG) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
    }
}
