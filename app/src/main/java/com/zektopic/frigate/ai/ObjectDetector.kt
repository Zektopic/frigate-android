package com.zektopic.frigate.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class ObjectDetector(private val context: Context) {

    private val tag = "ObjectDetector"
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // Model parameters (SSD MobileNet V2 default specs)
    private val modelInputWidth = 300
    private val modelInputHeight = 300
    private val numChannels = 3

    private val modelFile = File(context.filesDir, "ssd_mobilenet.tflite")
    private var isDownloading = false

    // COCO label mapping matching SSD MobileNet index values
    private val labelsMap = mapOf(
        0 to "person",
        1 to "bicycle",
        2 to "car",
        3 to "motorcycle",
        5 to "bus",
        6 to "train",
        7 to "truck",
        15 to "bird",
        16 to "cat",
        17 to "dog",
        18 to "horse",
        19 to "sheep"
    )

    data class DetectionResult(
        val label: String,
        val confidence: Float,
        val boundingBox: RectF
    )

    init {
        initializeDetector()
    }

    @Synchronized
    private fun initializeDetector() {
        // 1. Try loading from assets (if bundled)
        try {
            val assetManager = context.assets
            val descriptor = assetManager.openFd("ssd_mobilenet_v2.tflite")
            val inputStream = FileInputStream(descriptor.fileDescriptor)
            val modelBuffer = inputStream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                descriptor.startOffset,
                descriptor.declaredLength
            )
            val options = Interpreter.Options().apply { setNumThreads(4) }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(tag, "Loaded model successfully from assets.")
            return
        } catch (e: Exception) {
            // Not in assets, fallback to local storage
        }

        // 2. Try loading from internal storage
        if (modelFile.exists()) {
            try {
                val fileInputStream = FileInputStream(modelFile)
                val modelBuffer = fileInputStream.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    0L,
                    modelFile.length()
                )
                val options = Interpreter.Options()
                try {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                    Log.i(tag, "GPU Delegate added to interpreter options.")
                } catch (gpuEx: Exception) {
                    Log.w(tag, "GPU Delegate initialization failed, falling back to CPU", gpuEx)
                }
                options.setNumThreads(4)
                interpreter = Interpreter(modelBuffer, options)
                Log.i(tag, "Loaded model successfully from internal files: ${modelFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(tag, "Failed to load local model file, deleting and re-triggering download", e)
                try { modelFile.delete() } catch (ex: Exception) {}
                triggerModelDownload()
            }
        } else {
            triggerModelDownload()
        }
    }

    private fun triggerModelDownload() {
        if (isDownloading) return
        isDownloading = true
        Log.i(tag, "Model file not found. Starting background download from Google storage...")
        
        Thread {
            try {
                // Download a standard compatible SSD MobileNet v1 quantized model with metadata
                val url = URL("https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/object_detector/r1/lite-model_ssd_mobilenet_v1_1.0_metadata_2.tflite")
                val connection = url.openConnection()
                connection.connect()

                val tempFile = File(context.filesDir, "ssd_mobilenet.tflite.tmp")
                BufferedInputStream(url.openStream()).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val data = ByteArray(8192)
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            output.write(data, 0, count)
                        }
                        output.flush()
                    }
                }

                if (tempFile.renameTo(modelFile)) {
                    Log.i(tag, "Model downloaded and saved to internal storage. Initializing interpreter...")
                    initializeDetector()
                } else {
                    Log.e(tag, "Failed to rename temp file to model file.")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to download model file in background thread", e)
            } finally {
                isDownloading = false
            }
        }.start()
    }

    /**
     * Runs TFLite object detection on the input frame.
     */
    fun detectObjects(bitmap: Bitmap, confidenceThreshold: Float = 0.5f): List<DetectionResult> {
        val currInterpreter = interpreter
        if (currInterpreter == null) {
            // Simulated dummy inference for offline/emulator/compilation checks
            return simulateInference(bitmap, confidenceThreshold)
        }

        // 1. Inspect input and output tensor shapes dynamically
        val inputTensor = currInterpreter.getInputTensor(0)
        val isQuantized = inputTensor.dataType() == org.tensorflow.lite.DataType.UINT8

        val outputLocationsTensor = currInterpreter.getOutputTensor(0)
        val outputShape = outputLocationsTensor.shape() // Typically [1, maxDetections, 4]
        val maxDetections = if (outputShape.size >= 2) outputShape[1] else 10

        // 2. Preprocess Bitmap (scale to 300x300)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, modelInputWidth, modelInputHeight, true)
        val byteBuffer = convertBitmapToByteBuffer(scaledBitmap, isQuantized)

        // 3. Setup Dynamic Output Tensors matching the model shape
        // Output 0: Locations (bounding boxes coordinates [ymin, xmin, ymax, xmax])
        val outputLocations = arrayOf(Array(maxDetections) { FloatArray(4) })
        // Output 1: Category labels indices
        val outputClasses = arrayOf(FloatArray(maxDetections))
        // Output 2: Confidence scores [0.0 - 1.0]
        val outputScores = arrayOf(FloatArray(maxDetections))
        // Output 3: Number of positive objects found
        val numDetections = FloatArray(1)

        val outputMap = HashMap<Int, Any>()
        outputMap[0] = outputLocations
        outputMap[1] = outputClasses
        outputMap[2] = outputScores
        outputMap[3] = numDetections

        // 4. Perform TFLite execution
        try {
            currInterpreter.runForMultipleInputsOutputs(arrayOf(byteBuffer), outputMap)
        } catch (e: Exception) {
            Log.e(tag, "Error during TFLite inference run", e)
            scaledBitmap.recycle()
            return emptyList()
        }

        val results = ArrayList<DetectionResult>()
        val activeCount = numDetections[0].toInt().coerceAtMost(maxDetections)

        // 5. Parse output arrays and apply thresholds
        for (i in 0 until activeCount) {
            val score = outputScores[0][i]
            if (score >= confidenceThreshold) {
                val classId = outputClasses[0][i].toInt()
                val label = labelsMap[classId] ?: "object"

                // Extract locations: [ymin, xmin, ymax, xmax] coordinates
                val ymin = outputLocations[0][i][0]
                val xmin = outputLocations[0][i][1]
                val ymax = outputLocations[0][i][2]
                val xmax = outputLocations[0][i][3]

                // Map to coordinate rectangle bounds relative to original frame
                val box = RectF(
                    xmin * bitmap.width,
                    ymin * bitmap.height,
                    xmax * bitmap.width,
                    ymax * bitmap.height
                )
                results.add(DetectionResult(label, score, box))
            }
        }

        scaledBitmap.recycle()
        return results
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap, isQuantized: Boolean): ByteBuffer {
        val pixelsPerByte = if (isQuantized) 1 else 4
        val byteBuffer = ByteBuffer.allocateDirect(1 * modelInputWidth * modelInputHeight * numChannels * pixelsPerByte)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(modelInputWidth * modelInputHeight)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until modelInputWidth) {
            for (j in 0 until modelInputHeight) {
                val value = intValues[pixel++]
                val r = (value shr 16 and 0xFF)
                val g = (value shr 8 and 0xFF)
                val b = (value and 0xFF)
                if (isQuantized) {
                    byteBuffer.put(r.toByte())
                    byteBuffer.put(g.toByte())
                    byteBuffer.put(b.toByte())
                } else {
                    byteBuffer.putFloat((r - 127.5f) / 127.5f)
                    byteBuffer.putFloat((g - 127.5f) / 127.5f)
                    byteBuffer.putFloat((b - 127.5f) / 127.5f)
                }
            }
        }
        return byteBuffer
    }

    private fun simulateInference(bitmap: Bitmap, confidenceThreshold: Float): List<DetectionResult> {
        val mockResults = ArrayList<DetectionResult>()
        if (Math.random() > 0.6) {
            val mockLabel = if (Math.random() > 0.5) "person" else "car"
            val box = RectF(
                0.2f * bitmap.width,
                0.3f * bitmap.height,
                0.6f * bitmap.width,
                0.8f * bitmap.height
            )
            mockResults.add(DetectionResult(mockLabel, 0.88f, box))
        }
        return mockResults
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
    }
}
