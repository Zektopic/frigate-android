package com.zektopic.frigate.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zektopic.frigate.data.CameraConfigEntity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class StreamIngester(
    private val context: Context,
    private val config: CameraConfigEntity,
    private val onFrameExtracted: (Bitmap) -> Unit
) {
    private val tag = "StreamIngester-${config.id}"
    private var isIngesting = false

    // CameraX properties for local camera sensor
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Real stream decoder for RTSP/HTTP streams
    private var realDecoder: RealStreamDecoder? = null

    fun start() {
        if (isIngesting) return
        isIngesting = true
        Log.i(tag, "Starting stream ingestion for ${config.name}...")

        if (config.rtspUrl.isEmpty()) {
            // Local physical camera via CameraX
            startLocalCameraIngestion()
        } else {
            // External RTSP/HTTP stream via hardware decoder
            startRemoteStreamIngestion()
        }
    }

    fun stop() {
        if (!isIngesting) return
        isIngesting = false
        Log.i(tag, "Stopping stream ingestion for ${config.name}...")

        // Stop CameraX
        cameraExecutor?.shutdown()
        cameraExecutor = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(tag, "Error unbinding CameraX", e)
        }
        cameraProvider = null

        // Stop real stream decoder
        realDecoder?.stop()
        realDecoder = null
    }

    /**
     * Start a real hardware-accelerated stream decoder for remote RTSP/HTTP streams.
     * Uses MediaCodec to decode frames and passes them to the AI pipeline.
     */
    private fun startRemoteStreamIngestion() {
        Log.d(tag, "Starting real stream decoder for: ${config.rtspUrl}")

        realDecoder = RealStreamDecoder(
            streamUrl = config.rtspUrl,
            targetWidth = config.detectWidth,
            targetHeight = config.detectHeight,
            targetFps = config.fps
        )

        realDecoder!!.setOnFrameCallback { bitmap ->
            if (isIngesting) {
                onFrameExtracted(bitmap)
            }
        }

        realDecoder!!.setOnErrorCallback { errorMsg ->
            Log.e(tag, "Stream decoder error: $errorMsg")
        }

        realDecoder!!.start()
    }

    private fun startLocalCameraIngestion() {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(tag, "Cannot start local camera ingestion: CAMERA permission not granted.")
            return
        }
        cameraExecutor = Executors.newSingleThreadExecutor()
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Configure CameraX Analyzer to extract frames at target FPS
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(config.detectWidth, config.detectHeight))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var lastProcessedTime = 0L
                val intervalMs = 1000L / config.fps

                imageAnalysis.setAnalyzer(cameraExecutor!!, ImageAnalysis.Analyzer { imageProxy ->
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProcessedTime >= intervalMs) {
                        lastProcessedTime = currentTime
                        
                        // Convert ImageProxy (YUV_420_888) to raw Bitmap for processing
                        val bitmap = imageProxy.toBitmap()
                        if (bitmap != null) {
                            // Scale to target detect sizes matching config
                            val scaledBitmap = Bitmap.createScaledBitmap(
                                bitmap,
                                config.detectWidth,
                                config.detectHeight,
                                true
                            )
                            onFrameExtracted(scaledBitmap)
                        }
                    }
                    imageProxy.close()
                })

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                
                // Note: In an Android service context, we bind this analyzer to the Service's lifecycle.
                if (context is LifecycleOwner) {
                    cameraProvider?.bindToLifecycle(
                        context,
                        cameraSelector,
                        imageAnalysis
                    )
                } else {
                    Log.w(tag, "Context is not a LifecycleOwner; local camera requires a LifecycleOwner to bind.")
                }

                Log.d(tag, "Local CameraX ingestion initialized successfully.")
            } catch (e: Exception) {
                Log.e(tag, "Failed to start local camera ingestion", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // Helper extension function to convert CameraX YUV_420_888 ImageProxy to Bitmap
    private fun ImageProxy.toBitmap(): Bitmap? {
        val image: Image = this.image ?: return null
        if (image.format != ImageFormat.YUV_420_888) {
            return null
        }
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U and V are swapped for NV21
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 90, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
}
