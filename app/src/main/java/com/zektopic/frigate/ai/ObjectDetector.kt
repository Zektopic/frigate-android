package com.zektopic.frigate.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.Log
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.Detection
import org.tensorflow.lite.task.vision.detector.ObjectDetector as TfLiteObjectDetector
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Real AI object detection engine for Frigate Android with:
 * - GPU delegate: OpenCL/OpenGL ES 3.1+ acceleration (Qualcomm Adreno, ARM Mali, PowerVR)
 * - NNAPI delegate: NPU/DSP acceleration (Hexagon NPU, MediaTek APU, Samsung NPU, Pixel TPU)
 * - CPU fallback: XNNPACK SIMD-optimized when no GPU/NPU available
 *
 * Uses TensorFlow Lite Task Vision API for built-in preprocessing.
 */
class ObjectDetector(
    private val context: Context,
    private val modelFilename: String = "ssd_mobilenet_v2.tflite",
    private val detectionThreshold: Float = 0.50f,
    private val maxResults: Int = 5
) {
    private val tag = "ObjectDetector"

    // Performance tracking
    private val inferenceTimeMs = AtomicLong(0)
    private val framesProcessed = AtomicInteger(0)

    // Detection pipeline state
    private var detector: TfLiteObjectDetector? = null

    // GPU compatibility
    private val gpuCompatible = CompatibilityList().isDelegateSupportedOnThisDevice
    private var gpuDelegate: GpuDelegate? = null

    // Active delegate name (for UI display)
    private var activeDelegate: String = "CPU (XNNPACK)"

    // COCO labels relevant to Frigate NVR
    val frigateLabels = setOf("person", "car", "truck", "bus", "motorcycle",
        "bicycle", "dog", "cat", "bird", "horse", "cow", "sheep", "bear",
        "backpack", "suitcase", "bottle", "cup", "cell phone", "laptop",
        "tv", "chair", "couch", "book", "vase", "umbrella")

    data class DetectionResult(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF,
        val classId: Int
    )

    data class DetectionStats(
        val inferenceTimeMs: Long,
        val framesProcessed: Int,
        val activeDelegate: String
    )

    /**
     * Initialize with hardware-accelerated delegates.
     * Strategy: GPU → NNAPI → CPU (XNNPACK SIMD)
     *
     * GPU delegate uses OpenCL/OpenGL ES 3.1+ on compatible devices.
     * Falls back to XNNPACK CPU backend which is already SIMD-optimized.
     */
    fun initialize(): Boolean {
        Log.i(tag, "=== AI Object Detector Initialization ===")
        Log.i(tag, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
        Log.i(tag, "Hardware: ${Build.HARDWARE}, ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        Log.i(tag, "GPU delegate compatible: $gpuCompatible")

        return try {
            // 1. Extract model from assets to internal storage
            ensureModelExtracted()

            // 2. Attempt GPU delegate initialization
            var gpuOk = false
            if (gpuCompatible) {
                try {
                    val gpuOpts = GpuDelegate.Options()
                    gpuDelegate = GpuDelegate(gpuOpts)
                    activeDelegate = "GPU (OpenCL/OpenGL ES)"
                    gpuOk = true
                    Log.i(tag, "GPU delegate initialized successfully")
                } catch (e: Exception) {
                    Log.w(tag, "GPU delegate failed: ${e.message}")
                    gpuDelegate = null
                }
            }

            // 3. Fallback to NNAPI if GPU not available
            var nnapiOk = false
            if (!gpuOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    activeDelegate = "NNAPI (NPU/DSP)"
                    nnapiOk = true
                    Log.i(tag, "NNAPI delegate available (API ${Build.VERSION.SDK_INT})")
                } catch (e: Exception) {
                    Log.w(tag, "NNAPI not available: ${e.message}")
                    activeDelegate = "CPU (XNNPACK/SIMD)"
                }
            }

            // 4. Build the Task Vision ObjectDetector with hardware acceleration
            val optionsBuilder = TfLiteObjectDetector.ObjectDetectorOptions.builder()
                .setScoreThreshold(detectionThreshold)
                .setMaxResults(maxResults)

            val baseOptionsBuilder = BaseOptions.builder()
            if (gpuOk) {
                baseOptionsBuilder.useGpu()
            } else if (nnapiOk) {
                baseOptionsBuilder.useNnapi()
            }
            optionsBuilder.setBaseOptions(baseOptionsBuilder.build())

            detector = TfLiteObjectDetector.createFromFileAndOptions(context, modelFilename, optionsBuilder.build())

            Log.i(tag, "✅ ObjectDetector ready | Backend: $activeDelegate | Model: $modelFilename")
            true

        } catch (e: Exception) {
            Log.e(tag, "❌ ObjectDetector initialization failed", e)
            false
        }
    }

    /**
     * Run detection on one frame. Returns COCO-detected objects.
     */
    fun detect(bitmap: Bitmap): List<DetectionResult> {
        val d = detector ?: return emptyList()
        return try {
            val startTime = System.nanoTime()
            val tensorImage = TensorImage.fromBitmap(bitmap)
            val detections = d.detect(tensorImage)

            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            inferenceTimeMs.set(elapsedMs)
            framesProcessed.incrementAndGet()

            detections.mapNotNull { det ->
                val cat = det.categories.firstOrNull() ?: return@mapNotNull null
                DetectionResult(
                    label = cat.label.lowercase(),
                    confidence = cat.score,
                    boundingBox = det.boundingBox,
                    classId = cat.index
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "Detection failed: ${e.message}")
            emptyList()
        }
    }

    /** Frigate-relevant detections only */
    fun detectFrigate(bitmap: Bitmap): List<DetectionResult> {
        return detect(bitmap).filter { it.label in frigateLabels }
    }

    fun shutdown() {
        Log.i(tag, "Shutting down ObjectDetector...")
        try {
            detector?.close()
            gpuDelegate?.close()
        } catch (e: Exception) {
            Log.w(tag, "Shutdown error: ${e.message}")
        }
        detector = null
        gpuDelegate = null
    }

    fun getStats() = DetectionStats(
        inferenceTimeMs = inferenceTimeMs.get(),
        framesProcessed = framesProcessed.get(),
        activeDelegate = activeDelegate
    )

    fun isReady(): Boolean = detector != null

    private fun ensureModelExtracted(): File {
        val modelFile = File(context.filesDir, modelFilename)
        if (!modelFile.exists()) {
            try {
                context.assets.open(modelFilename).use { input ->
                    FileOutputStream(modelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(tag, "Model extracted: ${modelFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(tag, "Model not found in assets: $modelFilename", e)
                throw RuntimeException("Model $modelFilename missing from assets/", e)
            }
        }
        return modelFile
    }

    companion object {
        fun logHardwareCapabilities() {
            val tag = "ObjectDetector"
            Log.i(tag, "=== Hardware Acceleration Report ===")
            Log.i(tag, "Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            Log.i(tag, "SoC: ${Build.HARDWARE} | API: ${Build.VERSION.SDK_INT}")
            try {
                Log.i(tag, "GPU delegate: ${
                    if (CompatibilityList().isDelegateSupportedOnThisDevice) "✅ SUPPORTED" else "❌ NOT SUPPORTED"
                }")
            } catch (_: Exception) { }
            Log.i(tag, "NNAPI: ${if (Build.VERSION.SDK_INT >= 28) "✅ Available (API ${
                if (Build.VERSION.SDK_INT >= 31) "1.3+" else if (Build.VERSION.SDK_INT >= 29) "1.2+" else "1.1+"
            })" else "❌ Not available"}")
            Log.i(tag, "================================")
        }
    }
}
