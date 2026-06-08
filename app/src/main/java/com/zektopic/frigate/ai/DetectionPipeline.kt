package com.zektopic.frigate.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.zektopic.frigate.ai.ObjectDetector.DetectionResult
import com.zektopic.frigate.data.CameraConfigEntity
import com.zektopic.frigate.data.EventEntity
import com.zektopic.frigate.data.NvrDao
import com.zektopic.frigate.media.ClipRecorder
import com.zektopic.frigate.notification.AlertManager
import com.zektopic.frigate.ui.zones.GeometryEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/**
 * Complete Frigate-style detection pipeline for Android:
 *
 * Frame → Motion Pre-filter (gatekeeper) → AI Object Detection → Event Trigger → Notification
 *                                                   ↓
 *                                           Zone Filtering
 *                                                   ↓
 *                                           Recording (Clip + Snapshot)
 *
 * This mirrors Frigate's architecture with motion as the energy-efficient gatekeeper
 * for AI inference, conserving battery on mobile devices.
 */
class DetectionPipeline(
    private val context: Context,
    private val nvrDao: NvrDao
) {
    private val tag = "DetectionPipeline"

    // Component instances
    private var objectDetector: ObjectDetector? = null
    private val clipRecorder = ClipRecorder(context, nvrDao)
    private val alertManager = AlertManager(context)
    private val motionDetectors = ConcurrentHashMap<String, MotionDetector>()
    private val lastDetectionTime = ConcurrentHashMap<String, Long>()

    // Pipeline state
    private val pipelineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isRunning = AtomicBoolean(false)
    private var detectorInitialized = false

    // Configuration
    private var failOpenMode = true // If AI fails, fall back to motion-only events

    // Throttling
    private val detectionCooldownMs = 2000L
    private val eventCooldownMs = 5000L

    // Performance tracking
    data class PipelineStats(
        val totalFramesProcessed: Long = 0,
        val motionTriggered: Long = 0,
        val aiInvocations: Long = 0,
        val objectsDetected: Long = 0,
        val eventsCreated: Long = 0,
        val aiInferenceMs: Long = 0,
        val activeDelegate: String = "N/A",
        val aiEnabled: Boolean = false,
        val aiReady: Boolean = false
    )

    private val mutableStats = MutableStats()
    class MutableStats {
        var totalFramesProcessed = 0L
        var motionTriggered = 0L
        var aiInvocations = 0L
        var objectsDetected = 0L
        var eventsCreated = 0L
    }

    /**
     * Initialize the detection pipeline including AI model loading.
     */
    fun initialize(): Boolean {
        Log.i(tag, "=== Initializing Frigate Detection Pipeline ===")
        isRunning.set(true)

        ObjectDetector.logHardwareCapabilities()

        // Initialize AI Object Detector
        try {
            val detector = ObjectDetector(context)
            val initOk = detector.initialize()
            if (initOk) {
                objectDetector = detector
                detectorInitialized = true
                Log.i(tag, "AI Object Detector initialized and ready")
            } else {
                Log.w(tag, "AI init failed, using motion-only fallback")
                detectorInitialized = false
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception initializing AI detector", e)
            detectorInitialized = false
        }

        if (!detectorInitialized && failOpenMode) {
            Log.i(tag, "Running in MOTION-ONLY fallback mode (fail-open)")
        }

        Log.i(tag, "=== Detection Pipeline Initialized ===")
        return true
    }

    /**
     * Process a single frame through the full pipeline.
     * Motion pre-filter (gatekeeper) → AI Detection → Event.
     */
    suspend fun processFrame(
        cameraConfig: CameraConfigEntity,
        frame: Bitmap,
        currentZones: List<List<PointF>> = emptyList()
    ): List<DetectionResult> {
        if (!isRunning.get()) return emptyList()

        val cameraId = cameraConfig.id
        val currentTime = System.currentTimeMillis()
        mutableStats.totalFramesProcessed++

        // Step 1: Motion pre-filter (gatekeeper — saves battery)
        val motionDetector = getOrCreateMotionDetector(cameraConfig)
        val hasMotion = motionDetector.detectMotion(frame)

        if (!hasMotion) return emptyList()
        mutableStats.motionTriggered++

        // Step 2: AI detection (only on motion frames)
        if (detectorInitialized) {
            val detector = objectDetector ?: return emptyList()

            // Rate limit AI per camera
            val lastAi = lastDetectionTime[cameraId] ?: 0L
            if (currentTime - lastAi < detectionCooldownMs) return emptyList()
            lastDetectionTime[cameraId] = currentTime

            mutableStats.aiInvocations++

            val results = detector.detectFrigate(frame)

            if (results.isNotEmpty()) {
                mutableStats.objectsDetected += results.size
                Log.d(tag, "Camera $cameraId: Detected ${results.size} object(s): " +
                        results.joinToString { "${it.label} (${String.format("%.0f%%", it.confidence * 100)})" })

                // Step 3: Zone filtering
                val filteredResults = if (currentZones.isNotEmpty()) {
                    filterByZones(results, currentZones, frame.width, frame.height)
                } else {
                    results
                }

                // Step 4: Event creation (rate-limited)
                if (filteredResults.isNotEmpty()) {
                    createEvent(cameraId, filteredResults, frame)
                }

                return filteredResults
            }
        } else {
            // Motion-only fallback (fail-open mode)
            val lastMotion = lastDetectionTime[cameraId] ?: 0L
            if (currentTime - lastMotion >= eventCooldownMs) {
                lastDetectionTime[cameraId] = currentTime
                createMotionEvent(cameraId, frame)
            }
        }

        return emptyList()
    }

    fun getStats(): PipelineStats {
        val aiStats = objectDetector?.getStats()
        return PipelineStats(
            totalFramesProcessed = mutableStats.totalFramesProcessed,
            motionTriggered = mutableStats.motionTriggered,
            aiInvocations = mutableStats.aiInvocations,
            objectsDetected = mutableStats.objectsDetected,
            eventsCreated = mutableStats.eventsCreated,
            aiInferenceMs = aiStats?.inferenceTimeMs ?: 0,
            activeDelegate = aiStats?.activeDelegate ?: "Motion-Only",
            aiEnabled = detectorInitialized,
            aiReady = objectDetector?.isReady() ?: false
        )
    }

    fun shutdown() {
        Log.i(tag, "Shutting down Detection Pipeline...")
        isRunning.set(false)
        pipelineScope.cancel()
        objectDetector?.shutdown()
        objectDetector = null
        motionDetectors.clear()
        lastDetectionTime.clear()
    }

    fun isAiReady(): Boolean = detectorInitialized && (objectDetector?.isReady() ?: false)

    private fun getOrCreateMotionDetector(config: CameraConfigEntity): MotionDetector {
        return motionDetectors.getOrPut(config.id) {
            MotionDetector(thresholdPercentage = config.motionThreshold)
        }
    }

    /** Filter detections by active zones */
    private fun filterByZones(
        results: List<DetectionResult>,
        zones: List<List<PointF>>,
        frameWidth: Int,
        frameHeight: Int
    ): List<DetectionResult> {
        return results.filter { detection ->
            val centerX = detection.boundingBox.centerX() * frameWidth
            val centerY = detection.boundingBox.centerY() * frameHeight

            zones.any { zone ->
                GeometryEngine.isPointInPolygon(PointF(centerX, centerY), zone)
            }
        }
    }

    /** Create AI-based event with alert */
    private suspend fun createEvent(
        cameraId: String,
        results: List<DetectionResult>,
        frame: Bitmap
    ) {
        val currentTime = System.currentTimeMillis()

        val lastEvent = lastDetectionTime[cameraId] ?: 0L
        if (currentTime - lastEvent < eventCooldownMs) return
        lastDetectionTime[cameraId] = currentTime

        val primaryDetection = results.first()
        val labels = results.map { it.label }.distinct()
        val maxConfidence = results.maxOf { it.confidence }

        // 1. Save snapshot and trigger event recording (with pre-buffer)
        val (snapshotPath, clipPath) = clipRecorder.triggerEventRecording(
            cameraId = cameraId,
            eventType = primaryDetection.label,
            confidence = maxConfidence,
            currentFrame = frame
        )

        // 2. Create event record in database
        val event = EventEntity(
            cameraId = cameraId,
            label = labels.joinToString(","),
            confidence = maxConfidence,
            timestamp = currentTime,
            snapshotPath = snapshotPath,
            videoPath = clipPath,
            zone = "Main Zone"
        )

        nvrDao.insertEvent(event)

        // 3. Push notification
        val snapshotFile = snapshotPath?.let { File(it) }
        alertManager.postDetectionAlert(
            cameraId = cameraId,
            label = primaryDetection.label,
            confidence = maxConfidence,
            snapshotFile = snapshotFile
        )

        Log.i(tag, "Event: ${labels.joinToString()} on $cameraId " +
                "(conf=${String.format("%.1f%%", maxConfidence * 100)})")
    }

    /** Motion-only fallback event */
    private suspend fun createMotionEvent(cameraId: String, frame: Bitmap) {
        val currentTime = System.currentTimeMillis()
        val (snapshotPath, clipPath) = clipRecorder.triggerEventRecording(
            cameraId = cameraId,
            eventType = "motion",
            confidence = 1.0f,
            currentFrame = frame
        )

        val event = EventEntity(
            cameraId = cameraId,
            label = "motion",
            confidence = 1.0f,
            timestamp = currentTime,
            snapshotPath = snapshotPath,
            videoPath = clipPath,
            zone = "Main Zone"
        )
        nvrDao.insertEvent(event)
        val snapshotFile = snapshotPath?.let { File(it) }
        alertManager.postDetectionAlert(cameraId = cameraId, label = "Motion", confidence = 1.0f, snapshotFile = snapshotFile)
    }

    /** Draw detection bounding boxes on a frame */
    fun drawDetections(frame: Bitmap, results: List<DetectionResult>): Bitmap {
        val canvas = Canvas(frame)
        val boxPaint = Paint().apply {
            color = android.graphics.Color.argb(200, 0, 229, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        val labelPaint = Paint().apply {
            color = android.graphics.Color.argb(230, 0, 229, 255)
            style = Paint.Style.FILL
            textSize = 14f
            isAntiAlias = true
            isFakeBoldText = true
        }
        val bgPaint = Paint().apply {
            color = android.graphics.Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }

        for (result in results) {
            val bbox = result.boundingBox
            val scaledRect = RectF(
                bbox.left * frame.width,
                bbox.top * frame.height,
                bbox.right * frame.width,
                bbox.bottom * frame.height
            )
            canvas.drawRect(scaledRect, boxPaint)

            val label = "${result.label.uppercase()} ${String.format("%.0f%%", result.confidence * 100)}"
            val textWidth = labelPaint.measureText(label)

            canvas.drawRect(scaledRect.left, scaledRect.top - 22f,
                scaledRect.left + textWidth + 8f, scaledRect.top, bgPaint)
            canvas.drawText(label, scaledRect.left + 4f, scaledRect.top - 6f, labelPaint)
        }
        return frame
    }
}
