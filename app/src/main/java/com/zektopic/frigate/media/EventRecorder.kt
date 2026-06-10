package com.zektopic.frigate.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import android.util.Size
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real event recorder with ring-buffer pre-recording (Frigate-style).
 *
 * Continuously caches the last N seconds of video frames in memory.
 * When an event triggers, writes the pre-buffer + event to a proper MP4 file
 * using MediaCodec H.264 encoding + MediaMuxer muxing.
 *
 * This mirrors Frigate's approach:
 * - Pre-capture: always keep last 5-10s of frames
 * - Event: record pre-buffer + post-buffer to MP4
 * - Snapshots: save high-quality JPEG at detection moment
 */
class EventRecorder(
    private val context: Context,
    private val cameraId: String,
    private val preBufferSeconds: Int = 5,
    private val postBufferSeconds: Int = 3,
    private val targetBitrate: Int = 1_000_000 // 1 Mbps for H.264
) {
    private val tag = "EventRecorder-$cameraId"
    private val isRecording = AtomicBoolean(false)

    // Frame ring buffer for pre-recording
    private val ringBuffer = ConcurrentLinkedQueue<FrameBuffer>()
    private var lastBufferTime = 0L
    private val maxBufferFrames = preBufferSeconds * 15 // 15 FPS estimate

    // Recording state
    private var currentRecording: ActiveRecording? = null
    private val recordedEvents = AtomicInteger(0)

    // Storage
    private val recordingsDir: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "frigate_recordings/$cameraId").apply {
            mkdirs()
        }
        dir
    }
    private val snapshotsDir: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "frigate_snapshots/$cameraId").apply {
            mkdirs()
        }
        dir
    }

    data class FrameBuffer(
        val bitmap: Bitmap,
        val timestampMs: Long,
        val ptsUs: Long
    )

    data class RecordingMetadata(
        val cameraId: String,
        val eventType: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val filePath: String,
        val fileSizeBytes: Long,
        val durationMs: Long
    )

    fun start() {
        isRecording.set(true)
        Log.i(tag, "Event recorder started (pre-buffer: ${preBufferSeconds}s, post: ${postBufferSeconds}s)")
    }

    /**
     * Feed a decoded frame into the recorder.
     * Keeps the ring buffer topped up for pre-recording.
     */
    fun feedFrame(bitmap: Bitmap, timestampMs: Long = System.currentTimeMillis()) {
        if (!isRecording.get()) return

        val ptsUs = timestampMs * 1000L

        // 1. Add to ring buffer
        val frameCopy = Bitmap.createBitmap(bitmap)
        ringBuffer.offer(FrameBuffer(frameCopy, timestampMs, ptsUs))

        // Drop oldest if buffer exceeds max
        while (ringBuffer.size > maxBufferFrames) {
            ringBuffer.poll()?.bitmap?.recycle()
        }

        // 2. If actively recording an event, feed the encoder
        val recording = currentRecording
        if (recording != null && recording.isActive()) {
            recording.feedFrame(bitmap, ptsUs)
        }
    }

    /**
     * Start an event recording triggered by detection.
     * Captures pre-buffer + current frame + will capture post-buffer.
     *
     * @param eventType The detected object type (e.g. "person", "car")
     * @param confidence Detection confidence
     * @return The snapshot file immediately (for notifications), and starts clip recording
     */
    fun triggerEvent(
        eventType: String,
        confidence: Float,
        currentBitmap: Bitmap
    ): Pair<String?, String?> { // (snapshotPath, clipPathStarted)

        Log.i(tag, "Event triggered: $eventType (${String.format("%.0f%%", confidence * 100)})")
        recordedEvents.incrementAndGet()

        // 1. Save snapshot immediately
        val snapshotFile = saveSnapshot(eventType, currentBitmap)

        // 2. Start clip recording with pre-buffer
        val clipFile = startClipRecording(eventType)

        return Pair(snapshotFile, clipFile)
    }

    /**
     * Stop current event recording and finalize the clip.
     * Call this when the motion/detection ends.
     */
    fun stopEvent(): RecordingMetadata? {
        val recording = currentRecording ?: return null
        if (!recording.isActive()) return null

        currentRecording = null
        val metadata = recording.stop()

        if (metadata != null) {
            Log.i(tag, "Event recording saved: ${metadata.filePath} " +
                    "(${String.format("%.1f", metadata.durationMs / 1000.0)}s, " +
                    "${metadata.fileSizeBytes / 1024}KB)")
        }

        return metadata
    }

    /**
     * Save a snapshot JPEG at the moment of detection.
     */
    private fun saveSnapshot(eventType: String, bitmap: Bitmap): String? {
        val timestamp = System.currentTimeMillis()
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
        val filename = "${cameraId}_${eventType}_${dateStr}.jpg"
        val file = File(snapshotsDir, filename)

        return try {
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            Log.d(tag, "Snapshot saved: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(tag, "Failed to save snapshot", e)
            null
        }
    }

    /**
     * Start an MP4 clip recording using MediaCodec H.264 encoder + MediaMuxer.
     * Includes the ring buffer frames as pre-recording.
     */
    private fun startClipRecording(eventType: String): String? {
        try {
            val timestamp = System.currentTimeMillis()
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
            val filename = "${cameraId}_${eventType}_${dateStr}.mp4"
            val file = File(recordingsDir, filename)

            // Use a reference frame from ring buffer for dimensions
            val refFrame = ringBuffer.peek() ?: return null
            val width = refFrame.bitmap.width
            val height = refFrame.bitmap.height

            val recording = ActiveRecording(
                file = file,
                width = width,
                height = height,
                bitrate = targetBitrate
            )

            if (!recording.initialize()) return null

            // Write ring buffer frames (pre-recording)
            val ringFrames = ArrayList(ringBuffer)
            for (frame in ringFrames) {
                if (!recording.feedFrame(frame.bitmap, frame.ptsUs)) break
            }

            currentRecording = recording
            return file.absolutePath

        } catch (e: Exception) {
            Log.e(tag, "Failed to start clip recording", e)
            return null
        }
    }

    fun stop() {
        Log.i(tag, "Event recorder stopping...")
        isRecording.set(false)
        stopEvent()
        // Clear ring buffer
        while (ringBuffer.isNotEmpty()) {
            ringBuffer.poll()?.bitmap?.recycle()
        }
    }

    fun getRecordedEventCount(): Int = recordedEvents.get()

    /**
     * Enforce retention: delete files older than retentionDays
     */
    fun enforceRetention(retentionDays: Float) {
        val cutoffMs = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000).toLong()

        for (dir in listOf(recordingsDir, snapshotsDir)) {
            val files = dir.listFiles() ?: continue
            var deleted = 0
            for (file in files) {
                if (file.lastModified() < cutoffMs) {
                    if (file.delete()) deleted++
                }
            }
            if (deleted > 0) {
                Log.i(tag, "Retention: deleted $deleted files from ${dir.name}")
            }
        }
    }

    /**
     * Active recording managed by MediaCodec H.264 encoder + MediaMuxer.
     *
     * Uses hardware encoder via MediaCodec with MediaMuxer container.
     * Falls back gracefully if encoder fails.
     */
    class ActiveRecording(
        private val file: File,
        private val width: Int,
        private val height: Int,
        private val bitrate: Int = 1_000_000,
        private val framerate: Int = 15,
        private val keyFrameInterval: Int = 2 // Keyframe every 2 seconds
    ) {
        private val tag = "ActiveRecording"
        private val isActive = AtomicBoolean(true)
        private var mediaCodec: MediaCodec? = null
        private var mediaMuxer: MediaMuxer? = null
        private var videoTrackIndex = -1
        private var muxerStarted = false
        private var frameCount = 0
        private var startTimeMs = System.currentTimeMillis()
        private var inputSurface: android.view.Surface? = null

        fun initialize(): Boolean {
            return try {
                // 1. Configure H.264 encoder
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, framerate)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameInterval)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    }
                }

                // 2. Create encoder (prefer hardware encoder)
                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                // 3. Create muxer
                val muxer = MediaMuxer(
                    file.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )

                // 4. Get input surface for feeding frames
                inputSurface = encoder.createInputSurface()
                mediaCodec = encoder
                mediaMuxer = muxer

                encoder.start()

                Log.i(tag, "Event recording initialized: ${file.name} " +
                        "(${width}x${height} @ ${framerate}fps, ${bitrate / 1000}kbps). Muxer will start dynamically on first frame output.")
                true

            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize recording encoder", e)
                cleanup()
                false
            }
        }

        fun feedFrame(bitmap: Bitmap, ptsUs: Long): Boolean {
            val codec = mediaCodec ?: return false
            if (!isActive.get()) return false

            return try {
                // Use the input surface to feed frames
                // Since we configured with COLOR_FormatSurface, we feed via the surface's canvas
                val surface = inputSurface ?: return false
                val canvas = surface.lockCanvas(null)
                if (canvas != null) {
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                    surface.unlockCanvasAndPost(canvas)
                }

                frameCount++

                // Poll for encoded output
                drainEncoder()

                true
            } catch (e: Exception) {
                Log.w(tag, "Error feeding frame: ${e.message}")
                false
            }
        }

        fun stop(): RecordingMetadata? {
            isActive.set(false)
            Log.i(tag, "Stopping recording... ($frameCount frames)")

            try {
                // Signal EOS
                mediaCodec?.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.w(tag, "Error signaling EOS: ${e.message}")
            }

            // Drain remaining frames
            drainEncoder()

            cleanup()

            val endTimeMs = System.currentTimeMillis()
            val durationMs = endTimeMs - startTimeMs

            return RecordingMetadata(
                cameraId = "",
                eventType = "",
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                filePath = file.absolutePath,
                fileSizeBytes = file.length(),
                durationMs = durationMs
            )
        }

        private fun drainEncoder() {
            val codec = mediaCodec ?: return
            val muxer = mediaMuxer ?: return

            val bufferInfo = MediaCodec.BufferInfo()
            var drained = true

            while (drained && isActive.get()) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        drained = false
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            val newFormat = codec.outputFormat
                            videoTrackIndex = muxer.addTrack(newFormat)
                            muxer.start()
                            muxerStarted = true
                            Log.i(tag, "MediaMuxer started dynamically with format: $newFormat")
                        }
                    }
                    outputIndex >= 0 -> {
                        if (muxerStarted) {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val outputBuffer = codec.getOutputBuffer(outputIndex)
                                if (outputBuffer != null) {
                                    muxer.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        }

        private fun cleanup() {
            try {
                mediaCodec?.stop()
                mediaCodec?.release()
            } catch (e: Exception) {
                Log.w(tag, "Encoder cleanup error: ${e.message}")
            }
            mediaCodec = null

            try {
                if (muxerStarted) {
                    mediaMuxer?.stop()
                }
                mediaMuxer?.release()
            } catch (e: Exception) {
                Log.w(tag, "Muxer cleanup error: ${e.message}")
            }
            mediaMuxer = null
            muxerStarted = false
        }

        fun isActive(): Boolean = isActive.get()
    }
}
