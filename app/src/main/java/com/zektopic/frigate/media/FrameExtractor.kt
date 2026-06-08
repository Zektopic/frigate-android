package com.zektopic.frigate.media

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Extracts frames from an ExoPlayer instance for AI processing.
 *
 * Uses TextureView to render video and periodically grabs frames via
 * TextureView.getBitmap() at the configured FPS. This is the most
 * compatible approach across Android devices since it works with any
 * Surface-based output.
 *
 * For NPU-accelerated frame extraction, this can be optimized by using
 * ImageReader + custom MediaCodec output surface, but TextureView
 * provides universal compatibility.
 */
@UnstableApi
class FrameExtractor(
    private val player: ExoPlayer,
    private val targetFps: Int = 5
) {
    private val tag = "FrameExtractor"
    private val isRunning = AtomicBoolean(false)
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var textureView: TextureView? = null
    private val frameQueue = ConcurrentLinkedQueue<Bitmap>()
    private val maxQueueSize = 3

    // Frame throttle
    private var lastFrameTime = 0L
    private val frameIntervalMs = 1000L / targetFps

    // Callbacks
    private var onFrameCaptured: ((Bitmap) -> Unit)? = null

    // Custom Surface that renders to our TextureView
    private var customSurface: Surface? = null

    fun setOnFrameCallback(callback: (Bitmap) -> Unit) {
        onFrameCaptured = callback
    }

    /**
     * Start frame extraction. The TextureView must be attached to the view hierarchy.
     *
     * @param textureView The TextureView that ExoPlayer renders to
     */
    fun start(textureView: TextureView) {
        if (isRunning.getAndSet(true)) return
        this.textureView = textureView
        Log.i(tag, "Starting frame extraction at ${targetFps}FPS")

        handlerThread = HandlerThread("FrameExtractor-${player.hashCode()}")
        handlerThread!!.start()
        handler = Handler(handlerThread!!.looper)

        // Monitor player state and extract frames
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                Log.d(tag, "Video size: ${videoSize.width}x${videoSize.height}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(tag, "Playback state: $playbackState")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(tag, "Player error: ${error.message}", error)
            }
        })

        // Start periodic frame capture
        scheduleFrameCapture()
    }

    fun stop() {
        isRunning.set(false)
        handler?.removeCallbacksAndMessages(null)
        handlerThread?.quitSafely()
        handler = null
        handlerThread = null
        textureView = null
        frameQueue.clear()
        Log.i(tag, "Frame extraction stopped")
    }

    fun getLatestFrame(): Bitmap? {
        var latest: Bitmap? = null
        while (true) {
            val frame = frameQueue.poll() ?: break
            latest?.recycle()
            latest = frame
        }
        return latest
    }

    fun isActive(): Boolean = isRunning.get()

    private fun scheduleFrameCapture() {
        if (!isRunning.get()) return

        handler?.postDelayed({
            captureFrame()
            scheduleFrameCapture()
        }, frameIntervalMs)
    }

    private fun captureFrame() {
        val tv = textureView ?: return
        val now = System.currentTimeMillis()

        if (!tv.isAvailable) return
        if (now - lastFrameTime < frameIntervalMs) return
        lastFrameTime = now

        try {
            val bitmap = tv.bitmap
            if (bitmap != null) {
                // Scale to a reasonable size for AI processing
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    minOf(bitmap.width, 640),
                    minOf(bitmap.height, 360),
                    true
                )
                if (scaled !== bitmap) bitmap.recycle()

                // Enqueue for AI pipeline
                while (frameQueue.size >= maxQueueSize) {
                    frameQueue.poll()?.recycle()
                }
                frameQueue.offer(scaled)
                onFrameCaptured?.invoke(scaled)
            }
        } catch (e: Exception) {
            Log.w(tag, "Frame capture failed: ${e.message}")
        }
    }
}
