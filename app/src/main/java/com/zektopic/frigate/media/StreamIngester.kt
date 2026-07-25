package com.zektopic.frigate.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.media.Image
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.zektopic.frigate.data.CameraConfigEntity
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Connection state of a single camera stream, mirroring how Frigate reports
 * camera health: connecting, live, retrying (transient failure, backoff
 * reconnect scheduled) or offline (persistent failure, still retrying).
 */
enum class StreamState { CONNECTING, LIVE, RETRYING, OFFLINE }

class StreamIngester(
    private val context: Context,
    private val config: CameraConfigEntity,
    private val onFrameExtracted: (Bitmap) -> Unit,
    private val onStateChanged: (StreamState) -> Unit = {}
) {
    private val tag = "StreamIngester-${config.id}"
    @Volatile private var isIngesting = false
    private val generation = java.util.concurrent.atomic.AtomicInteger(0)

    // CameraX properties for local camera sensor
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // RTSP properties
    private var exoPlayer: androidx.media3.exoplayer.ExoPlayer? = null
    private var frameExtractionExecutor: ExecutorService? = null
    private val debugFrameCount = java.util.concurrent.atomic.AtomicInteger(0)
    private var currentRtspUrl: String = config.rtspUrl
    private var hasAttemptedFallback = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var connectionWatchdogRunnable: Runnable? = null
    private var frameWatchdogRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null
    private var retryAttempt = 0
    @Volatile private var lastFrameTime = 0L
    @Volatile private var currentState: StreamState? = null

    private fun setState(state: StreamState) {
        if (currentState == state) return
        currentState = state
        Log.i(tag, "Stream state -> $state")
        StreamDiagnostics.setState(config.id, currentRtspUrl, state.name)
        onStateChanged(state)
    }

    fun start() {
        if (isIngesting) return
        isIngesting = true
        val myGeneration = generation.incrementAndGet()
        currentRtspUrl = config.rtspUrl
        hasAttemptedFallback = false
        retryAttempt = 0
        Log.i(tag, "Starting stream ingestion for ${config.name} (gen=$myGeneration)...")
        setState(StreamState.CONNECTING)

        if (config.rtspUrl.isEmpty()) {
            // Local physical camera
            startLocalCameraIngestion()
        } else {
            // External RTSP camera
            startRealRtspIngestion(myGeneration)
        }
    }

    fun stop() {
        if (!isIngesting) return
        isIngesting = false
        Log.i(tag, "Stopping stream ingestion for ${config.name}...")

        // Stop Real RTSP ingestion (bumps generation, ending the EGL loop which
        // then tears down its own GL/surface resources on the EGL thread)
        stopProducers()

        // Stop CameraX
        cameraExecutor?.shutdown()
        cameraExecutor = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.e(tag, "Error unbinding CameraX", e)
        }
        cameraProvider = null

        setState(StreamState.OFFLINE)
    }

    /**
     * Single owner of producer teardown. Safe to call from the main thread at any
     * time: invalidates the extraction loop via [generation], cancels watchdogs and
     * pending reconnects, and releases the player. Surface/SurfaceTexture/GL
     * resources are NOT touched here — the EGL thread releases those itself when
     * its loop observes the generation change (releasing them cross-thread races
     * updateTexImage and leaks the EGL context).
     */
    private fun stopProducers() {
        generation.incrementAndGet() // Invalidate any running extraction loops
        connectionWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        connectionWatchdogRunnable = null
        frameWatchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        frameWatchdogRunnable = null
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null

        val playerToRelease = exoPlayer
        exoPlayer = null
        if (playerToRelease != null) {
            mainHandler.post {
                try {
                    playerToRelease.stop()
                    playerToRelease.release()
                } catch (e: Exception) {
                    Log.e(tag, "Error releasing ExoPlayer", e)
                }
            }
        }

        frameExtractionExecutor?.shutdown()
        frameExtractionExecutor = null
    }

    /**
     * Frigate-parity failure handling: no fake frames. Tear everything down and
     * schedule a real reconnect with exponential backoff (2s..60s). After three
     * consecutive failures the camera is reported OFFLINE (UI shows the offline
     * tile) but reconnect attempts continue indefinitely. Must be called on the
     * main thread.
     */
    private fun scheduleReconnect(reason: String) {
        if (!isIngesting) return
        stopProducers()
        retryAttempt++
        val delayMs = (2000L shl (retryAttempt - 1).coerceAtMost(5)).coerceAtMost(60_000L)
        setState(if (retryAttempt >= 3) StreamState.OFFLINE else StreamState.RETRYING)
        Log.w(tag, "Stream down ($reason). Reconnect attempt #$retryAttempt in ${delayMs}ms")
        val r = Runnable {
            if (isIngesting) {
                setState(StreamState.CONNECTING)
                startRealRtspIngestion(generation.incrementAndGet())
            }
        }
        reconnectRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun startLocalCameraIngestion() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
                        val bitmap = imageProxy.toBitmapCustom()
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
                // We assume the service provides a lifecycle owner, or we bind it using a LifecycleRegistry.
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

    private fun startRealRtspIngestion(myGeneration: Int) {
        Log.d(tag, "Connecting to real RTSP stream: $currentRtspUrl (gen=$myGeneration)")
        try {
            // Create a dedicated thread for frame extraction with its own EGL context
            frameExtractionExecutor = Executors.newSingleThreadExecutor()
            
            frameExtractionExecutor?.execute {
                // Setup EGL context on this thread
                val eglState = setupEgl(config.detectWidth, config.detectHeight)
                if (eglState == null) {
                    Log.e(tag, "Failed to setup EGL context.")
                    mainHandler.post { scheduleReconnect("EGL setup failed") }
                    return@execute
                }

                // Create GL texture for external OES (video decoder output)
                val texId = IntArray(1)
                GLES20.glGenTextures(1, texId, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId[0])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                // Create SurfaceTexture from GL texture — owned by this EGL thread
                //
                // The buffer size MUST always be set. Leaving it to the decoder was tried
                // and regressed every H.265 stream on the Qualcomm decoder: with no explicit
                // size, setOutputSurface fails with "failed to set consumer usage (BAD_INDEX)"
                // and the camera never reaches LIVE — including at 1920x1080, which the
                // hardware decoder supports. The detect size is what has always worked here;
                // the GL pass below renders into a detect-sized FBO regardless, so a larger
                // consumer buys nothing.
                val st = SurfaceTexture(texId[0])
                st.setDefaultBufferSize(config.detectWidth, config.detectHeight)
                val surface = Surface(st)

                // Compile shader program for rendering OES texture to FBO
                val program = createOesShaderProgram()
                if (program == 0) {
                    Log.e(tag, "Failed to create shader program.")
                    surface.release()
                    st.release()
                    teardownEgl(eglState)
                    mainHandler.post { scheduleReconnect("shader program creation failed") }
                    return@execute
                }

                // Create FBO for reading pixels
                val fbo = IntArray(1)
                GLES20.glGenFramebuffers(1, fbo, 0)
                val fboTex = IntArray(1)
                GLES20.glGenTextures(1, fboTex, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[0])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    config.detectWidth, config.detectHeight, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, fboTex[0], 0)

                val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    Log.e(tag, "FBO incomplete: $status.")
                    surface.release()
                    st.release()
                    teardownEgl(eglState)
                    mainHandler.post { scheduleReconnect("FBO incomplete") }
                    return@execute
                }

                val pixelBuffer = ByteBuffer.allocateDirect(config.detectWidth * config.detectHeight * 4)
                    .order(ByteOrder.nativeOrder())

                // Setup vertex data for fullscreen quad
                val vertexData = floatArrayOf(
                    -1f, -1f, 0f, 0f,
                     1f, -1f, 1f, 0f,
                    -1f,  1f, 0f, 1f,
                     1f,  1f, 1f, 1f
                )
                val vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
                    .put(vertexData)
                    .position(0)

                Log.i(tag, "EGL/GL pipeline initialized. Setting up ExoPlayer on main thread...")

                // Setup ExoPlayer on main thread
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                mainHandler.post {
                    if (!isIngesting || generation.get() != myGeneration) {
                        Log.d(tag, "Skipping stale ExoPlayer setup (gen=$myGeneration, current=${generation.get()})")
                        return@post
                    }
                    try {
                        val customSelector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                            val decoderInfos = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(
                                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                            )
                            // Rank video decoders hardware-first across every SoC vendor
                            // (Snapdragon/MediaTek/Exynos alike), demoting any that cannot
                            // handle this stream's geometry. Audio and anything else is
                            // left exactly as media3 ordered it.
                            if (mimeType.equals("video/hevc", ignoreCase = true) ||
                                mimeType.equals("video/avc", ignoreCase = true)
                            ) {
                                rankVideoDecoders(decoderInfos)
                            } else {
                                decoderInfos
                            }
                        }

                        // Asynchronous MediaCodec queueing is deliberately NOT forced here.
                        // media3's DefaultMediaCodecAdapterFactory already enables it by
                        // default on API 31+ (and conditionally on 28-30), so forcing it
                        // would be a no-op on modern devices and would override media3's
                        // deliberate exclusion of older ones — untested either way.
                        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
                            .setMediaCodecSelector(customSelector)
                            .setEnableDecoderFallback(true)

                        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context, renderersFactory).build()
                        val isDebuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        if (isDebuggable) {
                            player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger(tag))
                        }
                        // Record the decoder MediaCodec actually initialized. This is the
                        // only honest source for "which decoder is running" — the ranking
                        // is a preference, ExoPlayer's fallback may still pick another.
                        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                            override fun onVideoDecoderInitialized(
                                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                decoderName: String,
                                initializedTimestampMs: Long,
                                initializationDurationMs: Long
                            ) {
                                Log.i(tag, "Video decoder initialized: $decoderName " +
                                    "(${DecoderPolicy.vendorOf(decoderName).label}, ${initializationDurationMs}ms)")
                                StreamDiagnostics.setDecoder(config.id, decoderName)
                            }
                        })
                        exoPlayer = player
                        
                        val mediaItem = androidx.media3.common.MediaItem.fromUri(currentRtspUrl)
                        val mediaSource = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                            .setForceUseRtpTcp(true)
                            .setSocketFactory(RtspInterceptionSocketFactory(tag, currentRtspUrl))
                            .createMediaSource(mediaItem)
                        
                        player.setMediaSource(mediaSource)
                        player.setVideoSurface(surface)
                        player.volume = 0f
                        player.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ALL
                        
                        player.addListener(object : androidx.media3.common.Player.Listener {
                            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                                // Backstop for StreamProfileCache: covers H.264 and any SPS
                                // that would not parse. On a reconnect the decoder ranking
                                // then has real geometry to filter against.
                                StreamProfileCache.put(currentRtspUrl, videoSize.width, videoSize.height)
                                StreamDiagnostics.setVideoSize(config.id, videoSize.width, videoSize.height)
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                when (playbackState) {
                                    androidx.media3.common.Player.STATE_READY -> {
                                        Log.i(tag, "ExoPlayer prepared and ready, starting playback.")
                                        player.play()
                                    }
                                    androidx.media3.common.Player.STATE_ENDED -> {
                                        Log.w(tag, "ExoPlayer stream ended.")
                                        mainHandler.post { scheduleReconnect("stream ended") }
                                    }
                                }
                            }

                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                Log.e(tag, "ExoPlayer error: ${error.message} (${error.errorCodeName}).")
                                StreamDiagnostics.setError(config.id, currentRtspUrl, "${error.errorCodeName}: ${error.message}")
                                mainHandler.post {
                                    if (isIngesting) {
                                        val fallback = getFallbackRtspUrl(currentRtspUrl)
                                        if (fallback != null && !hasAttemptedFallback) {
                                            // Switch to the sub-stream URL; the reconnect path
                                            // rebuilds the player, watchdogs and lastFrameTime.
                                            Log.i(tag, "Will retry with fallback RTSP URL: $fallback")
                                            hasAttemptedFallback = true
                                            currentRtspUrl = fallback
                                            scheduleReconnect("player error, switching to fallback URL")
                                        } else {
                                            scheduleReconnect("player error: ${error.errorCodeName}")
                                        }
                                    }
                                }
                            }
                        })
 
                        lastFrameTime = 0L
                        val prepareTime = System.currentTimeMillis()
                        player.prepare()

                        // Connection watchdog. Software-decoded 2K H.265 can take well
                        // over 10s to render a first frame (decoder spin-up + GOP wait),
                        // so allow 15s to reach READY and 30s for the first frame.
                        val connectDeadlineMs = 15_000L
                        val firstFrameDeadlineMs = 30_000L
                        connectionWatchdogRunnable = Runnable {
                            if (isIngesting && generation.get() == myGeneration && lastFrameTime == 0L) {
                                val elapsed = System.currentTimeMillis() - prepareTime
                                val isReady = exoPlayer?.playbackState == androidx.media3.common.Player.STATE_READY
                                when {
                                    !isReady && elapsed >= connectDeadlineMs -> {
                                        Log.w(tag, "Connection watchdog: not READY after ${elapsed}ms.")
                                        scheduleReconnect("connection watchdog: not ready")
                                    }
                                    elapsed >= firstFrameDeadlineMs -> {
                                        Log.w(tag, "Connection watchdog: READY but no first frame after ${elapsed}ms.")
                                        scheduleReconnect("connection watchdog: no first frame")
                                    }
                                    else -> connectionWatchdogRunnable?.let { mainHandler.postDelayed(it, 2500) }
                                }
                            }
                        }
                        connectionWatchdogRunnable?.let { mainHandler.postDelayed(it, connectDeadlineMs) }

                        // Setup frame freeze watchdog
                        frameWatchdogRunnable = Runnable {
                            if (isIngesting && generation.get() == myGeneration) {
                                val now = System.currentTimeMillis()
                                if (lastFrameTime > 0L && now - lastFrameTime > 8000L) {
                                    Log.w(tag, "Frame watchdog: no frames received for 8 seconds.")
                                    scheduleReconnect("frame freeze watchdog")
                                } else {
                                    frameWatchdogRunnable?.let { mainHandler.postDelayed(it, 4000) }
                                }
                            }
                        }
                        frameWatchdogRunnable?.let { mainHandler.postDelayed(it, 8000) }

                    } catch (e: Exception) {
                        Log.e(tag, "Failed to initialize ExoPlayer.", e)
                        scheduleReconnect("ExoPlayer initialization failed")
                    }
                }

                // Frame extraction loop on this EGL thread
                val stMatrix = FloatArray(16)
                var lastProcessedTime = 0L
                var reportedLive = false
                val intervalMs = 1000L / config.fps
                val frameAvailable = java.util.concurrent.atomic.AtomicBoolean(false)

                st.setOnFrameAvailableListener({ _ ->
                    frameAvailable.set(true)
                }, android.os.Handler(android.os.Looper.getMainLooper()))

                Log.i(tag, "Starting frame extraction loop (gen=$myGeneration)...")
                while (isIngesting && generation.get() == myGeneration) {
                    if (!frameAvailable.getAndSet(false)) {
                        Thread.sleep(5) // Minimal sleep to avoid busy-wait
                        continue
                    }

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastProcessedTime < intervalMs) {
                        // Still consume the texture update even if we skip the frame
                        try { st.updateTexImage() } catch (_: Exception) {}
                        continue
                    }
                    lastProcessedTime = currentTime

                    try {
                        st.updateTexImage()
                        st.getTransformMatrix(stMatrix)

                        // Render OES texture to FBO
                        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
                        GLES20.glViewport(0, 0, config.detectWidth, config.detectHeight)
                        GLES20.glUseProgram(program)

                        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
                        val tcLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
                        val stMatLoc = GLES20.glGetUniformLocation(program, "uSTMatrix")
                        val texLoc = GLES20.glGetUniformLocation(program, "uTexture")

                        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId[0])
                        GLES20.glUniform1i(texLoc, 0)
                        GLES20.glUniformMatrix4fv(stMatLoc, 1, false, stMatrix, 0)

                        vertexBuffer.position(0)
                        GLES20.glEnableVertexAttribArray(posLoc)
                        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

                        vertexBuffer.position(2)
                        GLES20.glEnableVertexAttribArray(tcLoc)
                        GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

                        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                        // Read pixels
                        pixelBuffer.rewind()
                        GLES20.glReadPixels(0, 0, config.detectWidth, config.detectHeight,
                            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)

                        // Convert to Bitmap (glReadPixels gives bottom-up RGBA)
                        pixelBuffer.rewind()
                        val bitmap = Bitmap.createBitmap(config.detectWidth, config.detectHeight, Bitmap.Config.ARGB_8888)
                        bitmap.copyPixelsFromBuffer(pixelBuffer)

                        // Flip vertically (GL origin is bottom-left)
                        val matrix = android.graphics.Matrix()
                        matrix.preScale(1f, -1f)
                        val flipped = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
                        bitmap.recycle()

                        lastFrameTime = System.currentTimeMillis()
                        if (!reportedLive) {
                            reportedLive = true
                            mainHandler.post {
                                if (isIngesting && generation.get() == myGeneration) {
                                    retryAttempt = 0
                                    setState(StreamState.LIVE)
                                }
                            }
                        }
                        val frameNumber = debugFrameCount.incrementAndGet()
                        if (frameNumber % 30 == 1) {
                            val pixel = flipped.getPixel(flipped.width / 2, flipped.height / 2)
                            Log.d(tag, "Extracted frame #$frameNumber: ${flipped.width}x${flipped.height}, centerPixel=0x${Integer.toHexString(pixel)}")
                        }
                        onFrameExtracted(flipped)

                    } catch (e: Exception) {
                        Log.e(tag, "Error in frame extraction loop", e)
                    }
                }

                // Cleanup GL and surface resources on this EGL thread — the only
                // thread allowed to touch them (avoids racing updateTexImage)
                Log.d(tag, "Frame extraction loop ended (gen=$myGeneration). Cleaning up GL/surface resources.")
                try { st.setOnFrameAvailableListener(null) } catch (e: Exception) {}
                GLES20.glDeleteFramebuffers(1, fbo, 0)
                GLES20.glDeleteTextures(1, fboTex, 0)
                GLES20.glDeleteTextures(1, texId, 0)
                GLES20.glDeleteProgram(program)
                teardownEgl(eglState)
                try { surface.release() } catch (e: Exception) {}
                try { st.release() } catch (e: Exception) {}
            }

        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize real RTSP stream decoder.", e)
            mainHandler.post { scheduleReconnect("decoder pipeline initialization failed") }
        }
    }
 
    // Helper extension function to convert CameraX YUV_420_888 ImageProxy to Bitmap
    private fun ImageProxy.toBitmapCustom(): Bitmap? {
        return try {
            this.toBitmap()
        } catch (e: Exception) {
            Log.w(tag, "Failed to convert ImageProxy using native toBitmap(), falling back to YUV decoder: ${e.message}")
            val img = this.image ?: return null
            img.toBitmapFromYuv()
        }
    }

    private fun Image.toBitmapFromYuv(): Bitmap? {
        if (this.format != ImageFormat.YUV_420_888) {
            return null
        }
        val width = this.width
        val height = this.height
        val planes = this.planes

        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()

        val yPos = yBuffer.position()
        val uPos = uBuffer.position()
        val vPos = vBuffer.position()

        if (debugFrameCount.incrementAndGet() % 50 == 1) {
            Log.w(tag, "toBitmapFromYuv: format=${this.format}, width=$width, height=$height, " +
                    "Y: limit=${yBuffer.limit()}, capacity=${yBuffer.capacity()}, pos=${yBuffer.position()}, pixelStride=${yPlane.pixelStride}, rowStride=${yPlane.rowStride}; " +
                    "U: limit=${uBuffer.limit()}, capacity=${uBuffer.capacity()}, pos=${uBuffer.position()}, pixelStride=${uPlane.pixelStride}, rowStride=${uPlane.rowStride}; " +
                    "V: limit=${vBuffer.limit()}, capacity=${vBuffer.capacity()}, pos=${vBuffer.position()}, pixelStride=${vPlane.pixelStride}, rowStride=${vPlane.rowStride}")
        }

        // Expand limits to capacity to allow full indexing of interleaved planes
        try {
            uBuffer.limit(uBuffer.capacity())
        } catch (e: Exception) {
            Log.w(tag, "Failed to expand uBuffer limit to capacity: ${e.message}")
        }
        try {
            vBuffer.limit(vBuffer.capacity())
        } catch (e: Exception) {
            Log.w(tag, "Failed to expand vBuffer limit to capacity: ${e.message}")
        }

        val nv21 = ByteArray(width * height * 3 / 2)

        try {
            // Copy Y channel
            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride

            if (yPixelStride == 1 && yRowStride == width) {
                yBuffer.get(nv21, 0, width * height)
            } else {
                for (row in 0 until height) {
                    yBuffer.position(yPos + row * yRowStride)
                    val length = Math.min(width, yBuffer.remaining())
                    yBuffer.get(nv21, row * width, length)
                }
            }

            // Copy U and V channels into NV21 layout
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane.pixelStride

            val uvWidth = width / 2
            val uvHeight = height / 2

            val uCapacity = uBuffer.capacity()
            val vCapacity = vBuffer.capacity()

            var nvIndex = width * height
            for (row in 0 until uvHeight) {
                val uRowStart = row * uRowStride
                val vRowStart = row * vRowStride
                for (col in 0 until uvWidth) {
                    val uIndex = uPos + uRowStart + col * uPixelStride
                    val vIndex = vPos + vRowStart + col * vPixelStride

                    // Default out-of-bounds chroma to 128 (neutral gray) instead of 0 (green/cyan)
                    val uVal = if (uIndex < uCapacity) uBuffer.get(uIndex) else 128.toByte()
                    val vVal = if (vIndex < vCapacity) vBuffer.get(vIndex) else 128.toByte()

                    nv21[nvIndex++] = vVal
                    nv21[nvIndex++] = uVal
                }
            }
        } finally {
            // Restore buffer positions and limits
            try {
                yBuffer.position(yPos)
            } catch (e: Exception) {}
            try {
                uBuffer.position(uPos)
                uBuffer.limit(uLimit)
            } catch (e: Exception) {}
            try {
                vBuffer.position(vPos)
                vBuffer.limit(vLimit)
            } catch (e: Exception) {}
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    // --- EGL / GL helpers for SurfaceTexture frame extraction ---

    private data class EglState(
        val display: EGLDisplay,
        val context: EGLContext,
        val surface: EGLSurface
    )

    private fun setupEgl(width: Int, height: Int): EglState? {
        try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Log.e(tag, "eglGetDisplay failed")
                return null
            }

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Log.e(tag, "eglInitialize failed")
                return null
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                Log.e(tag, "eglChooseConfig failed")
                EGL14.eglTerminate(display)
                return null
            }

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            val context = EGL14.eglCreateContext(display, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.e(tag, "eglCreateContext failed")
                EGL14.eglTerminate(display)
                return null
            }

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
            )
            val pbufferSurface = EGL14.eglCreatePbufferSurface(display, configs[0]!!, surfaceAttribs, 0)
            if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(tag, "eglCreatePbufferSurface failed")
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                return null
            }

            if (!EGL14.eglMakeCurrent(display, pbufferSurface, pbufferSurface, context)) {
                Log.e(tag, "eglMakeCurrent failed")
                EGL14.eglDestroySurface(display, pbufferSurface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
                return null
            }

            Log.d(tag, "EGL context created successfully (version ${version[0]}.${version[1]})")
            return EglState(display, context, pbufferSurface)
        } catch (e: Exception) {
            Log.e(tag, "EGL setup exception", e)
            return null
        }
    }

    private fun teardownEgl(state: EglState) {
        try {
            EGL14.eglMakeCurrent(state.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(state.display, state.surface)
            EGL14.eglDestroyContext(state.display, state.context)
            EGL14.eglTerminate(state.display)
        } catch (e: Exception) {
            Log.e(tag, "EGL teardown exception", e)
        }
    }

    private fun createOesShaderProgram(): Int {
        val vertexShaderSrc = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            uniform mat4 uSTMatrix;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val fragmentShaderSrc = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderSrc)
        if (vertexShader == 0) return 0
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderSrc)
        if (fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            Log.e(tag, "Shader program link failed: $log")
            GLES20.glDeleteProgram(program)
            return 0
        }

        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            Log.e(tag, "Shader compile failed (type=$type): $log")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * Bridge between media3's decoder infos and the pure [DecoderPolicy].
     *
     * Reads media3's own `hardwareAccelerated`/`softwareOnly` flags rather than
     * matching on decoder names — those flags resolve to the platform APIs on
     * API 29+ and to media3's name inference below, so one code path covers
     * Qualcomm, MediaTek and Exynos identically.
     *
     * The stream geometry comes from [StreamProfileCache]; when it is not known yet
     * every candidate reports `supportsSize = null` and nothing is demoted.
     */
    private fun rankVideoDecoders(
        decoderInfos: List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo>
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        if (decoderInfos.isEmpty()) return decoderInfos

        val profile = StreamProfileCache.map[currentRtspUrl]
        val byName = decoderInfos.associateBy { it.name }

        val candidates = decoderInfos.map { info ->
            // Only query size support when we actually know the geometry. A decoder may
            // support 2560x1440@15 but not @30, so an unknown frame rate stays unknown
            // rather than being guessed high (which would wrongly demote a working decoder).
            val supportsSize: Boolean? = if (profile == null) null else try {
                val rate = if (profile.frameRate > 0.0) profile.frameRate else config.fps.toDouble()
                info.isVideoSizeAndRateSupportedV21(profile.width, profile.height, rate)
            } catch (e: Exception) {
                Log.w(tag, "Could not query size support for ${info.name}: ${e.message}")
                null
            }
            val maxInstances = try {
                info.maxSupportedInstances
            } catch (e: Exception) {
                DecoderCandidate.MAX_INSTANCES_UNKNOWN
            }
            DecoderCandidate(
                name = info.name,
                hardwareAccelerated = info.hardwareAccelerated,
                softwareOnly = info.softwareOnly,
                supportsSize = supportsSize,
                maxInstances = maxInstances
            )
        }

        val ranked = DecoderPolicy.rankCandidates(candidates, DecoderPolicy.deviceVendor())
        StreamDiagnostics.setCandidates(config.id, ranked)
        Log.i(
            tag,
            "Decoder ranking for ${profile?.let { "${it.width}x${it.height}" } ?: "unknown geometry"}: " +
                ranked.joinToString(", ") { DecoderPolicy.describe(it) }
        )

        // Map back to media3 infos, preserving the ranked order. mapNotNull is a
        // safety net only — every name came from this same list.
        return ranked.mapNotNull { byName[it.name] }
    }

    private fun getFallbackRtspUrl(originalUrl: String): String? {
        if (originalUrl.isEmpty()) return null
        return when {
            originalUrl.contains("stream=0") -> {
                originalUrl.replace("stream=0", "stream=1")
            }
            originalUrl.contains("live/ch0") -> {
                originalUrl.replace("live/ch0", "live/ch1")
            }
            originalUrl.contains("h264Preview_01_main") -> {
                originalUrl.replace("h264Preview_01_main", "h264Preview_01_sub")
            }
            originalUrl.contains("subtype=0") -> {
                originalUrl.replace("subtype=0", "subtype=1")
            }
            originalUrl.contains("Streaming/Channels/101") -> {
                originalUrl.replace("Streaming/Channels/101", "Streaming/Channels/102")
            }
            else -> null
        }
    }
}

// --- RTSP Connection Interception for H.265 Parameter Injection ---

/**
 * Cache of REAL H.265 parameter sets sniffed from each stream's in-band RTP
 * data, keyed by RTSP URL. Populated by [RtspInterceptionInputStream] when a
 * camera's SDP lacks sprop parameters (fallback constants get injected on the
 * first attempt and can mis-configure the decoder — e.g. 1080p CSD on a
 * 2560x1440 stream stalls output silently); consumed by maybeModifySdp on the
 * reconnect so the second attempt is configured with the stream's true
 * VPS/SPS/PPS.
 */
object SpropCache {
    val map = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()
}

/**
 * The real geometry of each stream, keyed by RTSP URL.
 *
 * [DecoderPolicy] needs the stream's width/height to ask a decoder whether it can
 * actually handle it, but media3's `MediaCodecSelector` callback only receives the
 * MIME type. So the geometry is cached out-of-band here, exactly like [SpropCache]:
 *
 *  - filled on the FIRST attempt by parsing the H.265 SPS the SDP interceptor already
 *    has (from the camera's `a=fmtp`, or sniffed in-band), and
 *  - backstopped by `onVideoSizeChanged`, which covers H.264 and any SPS that will
 *    not parse.
 *
 * Absent entry means "geometry unknown", which never demotes a decoder — the policy
 * then falls back to plain hardware-first ranking.
 */
object StreamProfileCache {
    data class VideoProfile(val width: Int, val height: Int, val frameRate: Double = 0.0)

    val map = java.util.concurrent.ConcurrentHashMap<String, VideoProfile>()

    /** Record geometry for [streamKey]. Ignores nonsense values so we never demote on garbage. */
    fun put(streamKey: String, width: Int, height: Int, frameRate: Double = 0.0) {
        if (streamKey.isEmpty() || width <= 0 || height <= 0) return
        val existing = map[streamKey]
        if (existing != null && existing.width == width && existing.height == height) return
        map[streamKey] = VideoProfile(width, height, frameRate)
        Log.i("StreamProfileCache", "Stream geometry for $streamKey: ${width}x$height")
    }

    /**
     * Parse an H.265 SPS NAL (raw, no start code) and cache the resolution it declares.
     * Returns true when the SPS parsed and geometry was recorded.
     */
    fun putFromH265Sps(streamKey: String, spsNal: ByteArray): Boolean {
        // Skip the 2-byte H.265 NAL header — parseH265SpsNalUnitPayload wants the payload,
        // which is how ExoPlayer's own RtspMediaTrack calls it.
        if (streamKey.isEmpty() || spsNal.size <= 2) return false
        return try {
            val sps = androidx.media3.container.NalUnitUtil
                .parseH265SpsNalUnitPayload(spsNal, 2, spsNal.size)
            put(streamKey, sps.width, sps.height)
            sps.width > 0 && sps.height > 0
        } catch (e: Exception) {
            Log.w("StreamProfileCache", "Could not parse H.265 SPS for $streamKey: ${e.message}")
            false
        }
    }
}

/**
 * Read-only per-camera stream diagnostics, surfaced by the embedded web server's
 * /api/diag endpoint. Purely observational — written from StreamIngester's existing
 * state/error callbacks so we can inspect stuck cameras (e.g. which H.265 parameter
 * sets were sniffed, which decoder was actually chosen, the last decoder error) over
 * `adb forward` even on devices that suppress app logcat.
 */
object StreamDiagnostics {
    data class Entry(
        @Volatile var state: String = "UNKNOWN",
        @Volatile var lastError: String? = null,
        @Volatile var url: String = "",
        @Volatile var updatedAt: Long = 0L,
        /** Decoder MediaCodec actually initialized, reported by AnalyticsListener. */
        @Volatile var decoderName: String? = null,
        @Volatile var decoderVendor: String? = null,
        @Volatile var hardwareAccelerated: Boolean? = null,
        /** Every candidate the policy ranked, best-first, with its advertised instance cap. */
        @Volatile var candidates: List<DecoderCandidate> = emptyList(),
        @Volatile var videoWidth: Int = 0,
        @Volatile var videoHeight: Int = 0
    )
    val byCamera = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun setState(cameraId: String, url: String, state: String) {
        val e = byCamera.getOrPut(cameraId) { Entry() }
        e.state = state; e.url = url; e.updatedAt = System.currentTimeMillis()
    }

    fun setError(cameraId: String, url: String, error: String) {
        val e = byCamera.getOrPut(cameraId) { Entry() }
        e.lastError = error; e.url = url; e.updatedAt = System.currentTimeMillis()
    }

    /** The decoder MediaCodec actually initialized — never a guess. */
    fun setDecoder(cameraId: String, decoderName: String) {
        val e = byCamera.getOrPut(cameraId) { Entry() }
        e.decoderName = decoderName
        e.decoderVendor = DecoderPolicy.vendorOf(decoderName).label
        // Prefer the flags media3 gave us for this exact decoder; fall back to null
        // (unknown) rather than inventing a value from the name.
        e.hardwareAccelerated = e.candidates.firstOrNull { it.name == decoderName }
            ?.let { it.hardwareAccelerated && !it.softwareOnly }
        e.updatedAt = System.currentTimeMillis()
    }

    /** The ranked candidate list, recorded so /api/diag can show instance caps. */
    fun setCandidates(cameraId: String, candidates: List<DecoderCandidate>) {
        val e = byCamera.getOrPut(cameraId) { Entry() }
        e.candidates = candidates
        e.updatedAt = System.currentTimeMillis()
    }

    fun setVideoSize(cameraId: String, width: Int, height: Int) {
        val e = byCamera.getOrPut(cameraId) { Entry() }
        e.videoWidth = width; e.videoHeight = height
        e.updatedAt = System.currentTimeMillis()
    }
}

class RtspInterceptionSocketFactory(
    private val tag: String,
    private val streamKey: String = ""
) : javax.net.SocketFactory() {
    private val delegate = javax.net.SocketFactory.getDefault()

    override fun createSocket(): java.net.Socket {
        return RtspInterceptionSocket(delegate.createSocket(), tag, streamKey)
    }

    override fun createSocket(host: String?, port: Int): java.net.Socket {
        return RtspInterceptionSocket(delegate.createSocket(host, port), tag, streamKey)
    }

    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket {
        return RtspInterceptionSocket(delegate.createSocket(host, port, localHost, localPort), tag, streamKey)
    }

    override fun createSocket(address: java.net.InetAddress?, port: Int): java.net.Socket {
        return RtspInterceptionSocket(delegate.createSocket(address, port), tag, streamKey)
    }

    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket {
        return RtspInterceptionSocket(delegate.createSocket(address, port, localAddress, localPort), tag, streamKey)
    }
}

class RtspInterceptionSocket(
    private val delegate: java.net.Socket,
    private val tag: String,
    private val streamKey: String = ""
) : java.net.Socket() {
    override fun connect(endpoint: java.net.SocketAddress?) {
        try {
            delegate.connect(endpoint, 4000)
        } catch (e: Exception) {
            Log.e(tag, "Socket connect failed: ${e.message}")
            throw e
        }
    }
 
    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
        val enforcedTimeout = if (timeout <= 0 || timeout > 4000) 4000 else timeout
        try {
            delegate.connect(endpoint, enforcedTimeout)
        } catch (e: Exception) {
            Log.e(tag, "Socket connect with timeout failed: ${e.message}")
            throw e
        }
    }

    override fun bind(bindpoint: java.net.SocketAddress?) {
        delegate.bind(bindpoint)
    }

    override fun getInetAddress(): java.net.InetAddress? = delegate.inetAddress
    override fun getLocalAddress(): java.net.InetAddress? = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): java.net.SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): java.net.SocketAddress? = delegate.localSocketAddress
    override fun getChannel() = delegate.channel

    override fun getInputStream(): java.io.InputStream {
        return RtspInterceptionInputStream(delegate.inputStream, tag, streamKey)
    }

    override fun getOutputStream(): java.io.OutputStream {
        return object : java.io.FilterOutputStream(delegate.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) {
                // RTSP requests are written as one buffer each; interleaved
                // binary frames start with '$' (0x24) — only log the text ones
                if (len in 1..4096 && b[off] != 0x24.toByte()) {
                    val text = String(b, off, len, java.nio.charset.StandardCharsets.US_ASCII)
                    if (text.firstOrNull()?.isLetter() == true) {
                        Log.d(tag, "RTSP >>> ${text.trimEnd()}")
                    }
                }
                out.write(b, off, len)
            }
        }
    }

    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) { delegate.setSoLinger(on, linger) }
    override fun getSoLinger(): Int = delegate.soLinger
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun close() { delegate.close() }
    override fun shutdownInput() { delegate.shutdownInput() }
    override fun shutdownOutput() { delegate.shutdownOutput() }
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
}

class RtspInterceptionInputStream(
    private val upstream: java.io.InputStream,
    private val tag: String,
    private val streamKey: String = ""
) : java.io.InputStream() {
    private var buffer = ByteArray(0)
    private var bufferPos = 0
    private var bufferLimit = 0
    @Volatile private var isInterceptionDone = false
    // While true, keep parsing interleaved frames after the SDP so in-band
    // VPS/SPS/PPS NALs can be captured into SpropCache (frames pass through
    // unmodified). Cleared once all three are found.
    @Volatile private var sniffForSprop = false
    @Volatile private var seenInterleaved = false
    private val sniffedParams = HashMap<String, String>()

    // Keep parsing framed messages until interleaved media starts (so the
    // RTSP SETUP/PLAY responses are visible in the log) and while sniffing.
    private val passthrough: Boolean
        get() = isInterceptionDone && !sniffForSprop && seenInterleaved

    override fun read(): Int {
        if (bufferPos < bufferLimit) {
            return buffer[bufferPos++].toInt() and 0xFF
        }
        if (passthrough) {
            return upstream.read()
        }
        fillBuffer()
        if (bufferPos < bufferLimit) {
            return buffer[bufferPos++].toInt() and 0xFF
        }
        return -1
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (bufferPos < bufferLimit) {
            val available = bufferLimit - bufferPos
            val toCopy = Math.min(len, available)
            System.arraycopy(buffer, bufferPos, b, off, toCopy)
            bufferPos += toCopy
            return toCopy
        }
        if (passthrough) {
            return upstream.read(b, off, len)
        }
        fillBuffer()
        if (bufferPos < bufferLimit) {
            val available = bufferLimit - bufferPos
            val toCopy = Math.min(len, available)
            System.arraycopy(buffer, bufferPos, b, off, toCopy)
            bufferPos += toCopy
            return toCopy
        }
        return -1
    }

    override fun available(): Int {
        return (bufferLimit - bufferPos) + upstream.available()
    }

    override fun close() {
        upstream.close()
    }

    private fun fillBuffer() {
        bufferPos = 0
        bufferLimit = 0
        buffer = ByteArray(0)

        // Read one byte to inspect if it's interleaved data
        val firstByte = upstream.read()
        if (firstByte == -1) return

        if (firstByte == 0x24) { // '$'
            seenInterleaved = true
            val channel = upstream.read()
            if (channel == -1) {
                buffer = byteArrayOf(0x24)
                bufferLimit = 1
                return
            }
            val len1 = upstream.read()
            if (len1 == -1) {
                buffer = byteArrayOf(0x24, channel.toByte())
                bufferLimit = 2
                return
            }
            val len2 = upstream.read()
            if (len2 == -1) {
                buffer = byteArrayOf(0x24, channel.toByte(), len1.toByte())
                bufferLimit = 3
                return
            }
            val packetLen = ((len1 and 0xFF) shl 8) or (len2 and 0xFF)
            val packetBytes = ByteArray(4 + packetLen)
            packetBytes[0] = 0x24
            packetBytes[1] = channel.toByte()
            packetBytes[2] = len1.toByte()
            packetBytes[3] = len2.toByte()

            var readOffset = 4
            var remaining = packetLen
            while (remaining > 0) {
                val read = upstream.read(packetBytes, readOffset, remaining)
                if (read == -1) break
                readOffset += read
                remaining -= read
            }
            if (sniffForSprop) {
                try {
                    sniffRtpPacket(packetBytes, readOffset)
                } catch (e: Exception) {
                    Log.w(tag, "sprop sniffing failed on RTP packet: ${e.message}")
                }
            }
            buffer = packetBytes
            bufferLimit = readOffset
            return
        }

        // It's a text-based RTSP message
        val headerStream = java.io.ByteArrayOutputStream()
        headerStream.write(firstByte)
        
        while (true) {
            val lineStream = java.io.ByteArrayOutputStream()
            val ok = readLine(upstream, lineStream)
            val lineBytes = lineStream.toByteArray()
            headerStream.write(lineBytes)
            if (!ok) {
                break
            }
            val lineStr = String(lineBytes, java.nio.charset.StandardCharsets.US_ASCII).trim()
            if (lineStr.isEmpty()) {
                break
            }
        }

        val headerBytes = headerStream.toByteArray()
        val headerStr = String(headerBytes, java.nio.charset.StandardCharsets.UTF_8)
        Log.d(tag, "RTSP <<< ${headerStr.trimEnd()}")

        var contentType: String? = null
        var contentLength = 0
        val headerLines = headerStr.split("\r\n", "\n")
        for (line in headerLines) {
            val lower = line.toLowerCase(java.util.Locale.US)
            if (lower.startsWith("content-type:")) {
                contentType = line.substring("content-type:".length).trim()
            } else if (lower.startsWith("content-length:")) {
                val lenStr = line.substring("content-length:".length).trim()
                contentLength = lenStr.toIntOrNull() ?: 0
            }
        }

        val bodyBytes = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var bodyRead = 0
            while (bodyRead < contentLength) {
                val read = upstream.read(bytes, bodyRead, contentLength - bodyRead)
                if (read == -1) break
                bodyRead += read
            }
            if (bodyRead < contentLength) {
                bytes.copyOf(bodyRead)
            } else {
                bytes
            }
        } else {
            ByteArray(0)
        }

        if (contentType != null && contentType.toLowerCase(java.util.Locale.US).contains("application/sdp")) {
            val sdp = String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8)
            Log.d(tag, "Intercepted SDP Description:\n$sdp")

            val modifiedSdp = maybeModifySdp(sdp, tag, streamKey)
            isInterceptionDone = true

            // If the camera's SDP lacked sprop parameters and we have no real ones
            // cached yet, the fallback constants were injected — sniff the true
            // in-band parameter sets from the RTP data for the reconnect attempt.
            if (streamKey.isNotEmpty() &&
                sdpNeedsSpropInjection(sdp) &&
                !SpropCache.map.containsKey(streamKey)
            ) {
                sniffForSprop = true
                Log.i(tag, "SDP lacked H.265 sprop parameters; sniffing in-band parameter sets for $streamKey")
            }
            
            if (modifiedSdp != sdp) {
                Log.d(tag, "Modified SDP Description:\n$modifiedSdp")
                val newBodyBytes = modifiedSdp.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                val newHeaders = rewriteContentLength(headerLines, newBodyBytes.size)
                val newHeaderBytes = newHeaders.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                
                val finalBytes = ByteArray(newHeaderBytes.size + newBodyBytes.size)
                System.arraycopy(newHeaderBytes, 0, finalBytes, 0, newHeaderBytes.size)
                System.arraycopy(newBodyBytes, 0, finalBytes, newHeaderBytes.size, newBodyBytes.size)
                
                buffer = finalBytes
                bufferLimit = finalBytes.size
                return
            }
        }

        val finalBytes = ByteArray(headerBytes.size + bodyBytes.size)
        System.arraycopy(headerBytes, 0, finalBytes, 0, headerBytes.size)
        System.arraycopy(bodyBytes, 0, finalBytes, headerBytes.size, bodyBytes.size)
        buffer = finalBytes
        bufferLimit = finalBytes.size
    }

    private fun readLine(stream: java.io.InputStream, out: java.io.ByteArrayOutputStream): Boolean {
        while (true) {
            val b = stream.read()
            if (b == -1) return false
            out.write(b)
            if (b == '\n'.code) {
                return true
            }
        }
    }

    /**
     * Inspect an interleaved RTSP frame ('$' | channel | 16-bit len | payload)
     * and capture H.265 VPS/SPS/PPS NAL units from channel-0 RTP packets.
     */
    private fun sniffRtpPacket(packet: ByteArray, length: Int) {
        if (length < 18) return
        val channel = packet[1].toInt() and 0xFF
        if (channel != 0) return // video RTP is on the first interleaved channel

        val rtpStart = 4
        val b0 = packet[rtpStart].toInt() and 0xFF
        if ((b0 shr 6) != 2) return // not RTP version 2
        val csrcCount = b0 and 0x0F
        var payloadStart = rtpStart + 12 + csrcCount * 4
        if ((b0 and 0x10) != 0) { // header extension present
            if (payloadStart + 4 > length) return
            val extWords = ((packet[payloadStart + 2].toInt() and 0xFF) shl 8) or
                (packet[payloadStart + 3].toInt() and 0xFF)
            payloadStart += 4 + extWords * 4
        }
        if (payloadStart + 2 > length) return

        when (val nalType = (packet[payloadStart].toInt() shr 1) and 0x3F) {
            32, 33, 34 -> storeParamSet(nalType, packet.copyOfRange(payloadStart, length))
            48 -> { // aggregation packet: 2-byte AP header, then [16-bit size][NALU]...
                var pos = payloadStart + 2
                while (pos + 2 <= length) {
                    val size = ((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                    pos += 2
                    if (size <= 0 || pos + size > length) break
                    val t = (packet[pos].toInt() shr 1) and 0x3F
                    if (t == 32 || t == 33 || t == 34) {
                        storeParamSet(t, packet.copyOfRange(pos, pos + size))
                    }
                    pos += size
                }
            }
        }
    }

    private fun storeParamSet(nalType: Int, nal: ByteArray) {
        val key = when (nalType) {
            32 -> "sprop-vps"
            33 -> "sprop-sps"
            else -> "sprop-pps"
        }
        if (sniffedParams.containsKey(key)) return
        sniffedParams[key] = java.util.Base64.getEncoder().encodeToString(nal)
        Log.i(tag, "Sniffed in-band $key (${nal.size} bytes) from RTP stream")
        // The SPS declares the stream's real resolution — cache it so the decoder
        // ranking can drop decoders that cannot handle this geometry.
        if (nalType == 33) {
            StreamProfileCache.putFromH265Sps(streamKey, nal)
        }
        if (sniffedParams.size == 3) {
            SpropCache.map[streamKey] = sniffedParams.toMap()
            sniffForSprop = false
            Log.i(tag, "All H.265 parameter sets captured for $streamKey; real sprop will be injected on the next connect")
        }
    }

    private fun rewriteContentLength(headerLines: List<String>, newBodySize: Int): String {
        val newLines = mutableListOf<String>()
        for (line in headerLines) {
            val lower = line.lowercase(java.util.Locale.US)
            if (lower.startsWith("content-length:")) {
                newLines.add("Content-Length: $newBodySize")
            } else {
                newLines.add(line)
            }
        }
        val cleanedLines = newLines.filter { it.isNotEmpty() }
        return cleanedLines.joinToString("\r\n") + "\r\n\r\n"
    }

    companion object {
        /**
         * True when the SDP advertises an H.265 track but provides no sprop-sps
         * parameter — i.e. maybeModifySdp will have to inject parameter sets.
         */
        internal fun sdpNeedsSpropInjection(sdp: String): Boolean {
            val lower = sdp.lowercase(java.util.Locale.US)
            return lower.contains("h265") && !lower.contains("sprop-sps")
        }

        internal fun maybeModifySdp(sdp: String, tag: String = "RtspInterception", streamKey: String = ""): String {
            // 1. Strip any audio media description blocks
            val filteredLines = mutableListOf<String>()
            var inAudioBlock = false
            val lines = sdp.split("\r\n", "\n")
            for (line in lines) {
                val lowerLine = line.lowercase(java.util.Locale.US)
                if (lowerLine.startsWith("m=audio")) {
                    inAudioBlock = true
                } else if (lowerLine.startsWith("m=")) {
                    inAudioBlock = false
                }
                if (!inAudioBlock) {
                    filteredLines.add(line)
                }
            }

            val h265PayloadTypes = mutableListOf<String>()
            for (line in filteredLines) {
                if (line.startsWith("a=rtpmap:") && line.lowercase(java.util.Locale.US).contains("h265")) {
                    val pt = line.substring("a=rtpmap:".length).split(" ")[0].trim()
                    h265PayloadTypes.add(pt)
                }
            }

            val rawResult = if (h265PayloadTypes.isEmpty()) {
                filteredLines.joinToString("\r\n")
            } else {
                val fmtpFound = mutableSetOf<String>()
                val fmtpLines = mutableMapOf<String, String>() // pt -> line
                for (line in filteredLines) {
                    for (pt in h265PayloadTypes) {
                        if (line.startsWith("a=fmtp:$pt")) {
                            fmtpFound.add(pt)
                            fmtpLines[pt] = line
                        }
                    }
                }

                // Media3's RTSP H.265 support requires sprop parameters, so when a
                // camera omits them something must be injected. Prefer REAL
                // parameter sets previously sniffed from this stream's RTP data
                // (SpropCache); fall back to known-good 1080p sets on the first
                // attempt only — a mismatched CSD can silently stall the decoder,
                // which the watchdog turns into a reconnect that then uses the
                // sniffed values.
                val cached = if (streamKey.isNotEmpty()) SpropCache.map[streamKey] else null
                if (cached != null) {
                    Log.i(tag, "Using sniffed sprop parameters for $streamKey")
                }
                val dummyVps = cached?.get("sprop-vps") ?: "QAEMAf//AWAAAAMAsAAAAwAAAwCWrAk="
                val dummySps = cached?.get("sprop-sps") ?: "QgEBAWAAAAMAsAAAAwAAAwCWoAPAgBEHy+u5MkupSCgwMBdoUJQ="
                val dummyPps = cached?.get("sprop-pps") ?: "RAHA4w8CCEA="

                val newLines = mutableListOf<String>()
                for (line in filteredLines) {
                    var lineProcessed = false
                    for (pt in h265PayloadTypes) {
                        if (line.startsWith("a=rtpmap:$pt ")) {
                            newLines.add(line)
                            lineProcessed = true
                            if (!fmtpFound.contains(pt)) {
                                val newFmtp = "a=fmtp:$pt sprop-vps=$dummyVps;sprop-sps=$dummySps;sprop-pps=$dummyPps"
                                newLines.add(newFmtp)
                                Log.d(tag, "Injected complete fmtp line for payload type $pt: $newFmtp")
                            }
                            break
                        } else if (line.startsWith("a=fmtp:$pt")) {
                            lineProcessed = true
                            
                            // Parse parameters
                            val ptSpaceIndex = line.indexOf(' ')
                            val prefix = if (ptSpaceIndex != -1) line.substring(0, ptSpaceIndex) else line
                            val paramsPart = if (ptSpaceIndex != -1) line.substring(ptSpaceIndex + 1).trim() else ""
                            
                            val paramPairs = paramsPart.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                            val paramMap = LinkedHashMap<String, String>()
                            for (pair in paramPairs) {
                                val idx = pair.indexOf('=')
                                if (idx != -1) {
                                    val key = pair.substring(0, idx).trim().lowercase(java.util.Locale.US)
                                    val value = pair.substring(idx + 1).trim()
                                    paramMap[key] = value
                                } else {
                                    paramMap[pair.trim().lowercase(java.util.Locale.US)] = ""
                                }
                            }

                            // The camera supplied its own sprop-sps — parse the real stream
                            // geometry out of it so the very first connect can already rank
                            // decoders by whether they support this resolution.
                            paramMap["sprop-sps"]?.let { spsB64 ->
                                try {
                                    StreamProfileCache.putFromH265Sps(
                                        streamKey, java.util.Base64.getDecoder().decode(spsB64)
                                    )
                                } catch (e: Exception) {
                                    Log.w(tag, "Could not decode SDP sprop-sps: ${e.message}")
                                }
                            }

                            var modified = false
                            if (!paramMap.containsKey("sprop-vps")) {
                                paramMap["sprop-vps"] = dummyVps
                                modified = true
                            }
                            if (!paramMap.containsKey("sprop-sps")) {
                                paramMap["sprop-sps"] = dummySps
                                modified = true
                            }
                            if (!paramMap.containsKey("sprop-pps")) {
                                paramMap["sprop-pps"] = dummyPps
                                modified = true
                            }

                            if (modified) {
                                val newParamsPart = paramMap.map { (k, v) -> if (v.isNotEmpty()) "$k=$v" else k }.joinToString(";")
                                val modifiedFmtp = "$prefix $newParamsPart"
                                newLines.add(modifiedFmtp)
                                Log.d(tag, "Modified existing fmtp line for payload type $pt: $modifiedFmtp")
                            } else {
                                newLines.add(line)
                            }
                            break
                        }
                    }
                    if (!lineProcessed) {
                        newLines.add(line)
                    }
                }
                newLines.joinToString("\r\n")
            }

            var result = rawResult
            if (result.isNotEmpty() && !result.endsWith("\n")) {
                result += "\r\n"
            }
            return result
        }
    }
}
