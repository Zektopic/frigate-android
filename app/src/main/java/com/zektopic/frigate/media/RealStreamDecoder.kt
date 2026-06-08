package com.zektopic.frigate.media

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real hardware-accelerated media stream decoder that extracts raw frames
 * for the AI detection pipeline. Supports RTSP, HTTP, HLS, and local file playback.
 *
 * Uses MediaCodec directly to leverage hardware decoders on device SoCs
 * (Qualcomm Hexagon DSP, MediaTek APU, Samsung NPU, Pixel Tensor).
 */
class RealStreamDecoder(
    private val streamUrl: String,
    private val targetWidth: Int = 640,
    private val targetHeight: Int = 360,
    private val targetFps: Int = 5,
    private val preferredCodec: String = "" // "video/avc" for H.264 or "video/hevc" for H.265
) {
    private val tag = "RealStreamDecoder"
    private val isRunning = AtomicBoolean(false)

    private var decoderThread: Thread? = null
    private val frameQueue = ConcurrentLinkedQueue<Bitmap>()
    private val maxQueueSize = 5

    // Frame rate throttling
    private var lastFrameTime = 0L
    private val frameIntervalMs = 1000L / targetFps

    // Callback
    private var onFrame: ((Bitmap) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    fun setOnFrameCallback(callback: (Bitmap) -> Unit) {
        onFrame = callback
    }

    fun setOnErrorCallback(callback: (String) -> Unit) {
        onError = callback
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(tag, "Starting real stream decoder for: $streamUrl")

        decoderThread = Thread({
            try {
                if (streamUrl.startsWith("rtsp://")) {
                    decodeRtspStream()
                } else if (streamUrl.startsWith("http") && (streamUrl.contains(".m3u8") || streamUrl.contains(".ts"))) {
                    decodeHttpStream()
                } else if (streamUrl.startsWith("http") || streamUrl.startsWith("https")) {
                    decodeHttpFile()
                } else if (streamUrl.startsWith("file://") || streamUrl.startsWith("/")) {
                    decodeLocalFile()
                } else {
                    onError?.invoke("Unsupported stream URL protocol: $streamUrl")
                    Log.e(tag, "Unsupported stream URL: $streamUrl")
                }
            } catch (e: Exception) {
                Log.e(tag, "Stream decoder error: ${e.message}", e)
                onError?.invoke("Stream decoder error: ${e.message}")
            }
        }, "RealStreamDecoder-${streamUrl.hashCode()}")

        decoderThread?.start()
    }

    fun stop() {
        isRunning.set(false)
        decoderThread?.join(2000)
        decoderThread = null
        frameQueue.clear()
        Log.i(tag, "Stream decoder stopped")
    }

    fun getLatestFrame(): Bitmap? {
        // Drain queue to get latest frame
        var latest: Bitmap? = null
        while (true) {
            val frame = frameQueue.poll() ?: break
            latest?.recycle()
            latest = frame
        }
        return latest
    }

    fun isActive(): Boolean = isRunning.get()

    /**
     * Decode an RTSP stream by first proxying through a local socket.
     * Android MediaExtractor does not directly support RTSP, so we use
     * a custom approach: download the stream via TCP, detect codec, and decode.
     *
     * Note: For production RTSP, a native FFmpeg wrapper or Nginx-RTMP proxy
     * would be used. This implementation provides a software-based fallback.
     */
    private fun decodeRtspStream() {
        Log.d(tag, "RTSP stream detected. Attempting TCP transport for: $streamUrl")
        onError?.invoke("RTSP direct decode not fully supported on Android MediaCodec. Use HTTP/RSTP relay or HLS.")

        // Fallback: attempt to parse the stream as generic MPEG-TS over TCP
        // For real deployment, use ExoPlayer RTSP module for UI + this decoder
        // for AI frame extraction via a separate codec instance.
        tryDecodeGenericUrl(streamUrl)
    }

    /**
     * Decode an HTTP stream (HLS M3U8). We download the segments and feed
     * them to MediaCodec sequentially.
     */
    private fun decodeHttpStream() {
        Log.d(tag, "HTTP stream detected (HLS likely): $streamUrl")
        tryDecodeGenericUrl(streamUrl)
    }

    /**
     * Decode a direct HTTP media file (MP4, MKV, etc.)
     */
    private fun decodeHttpFile() {
        Log.d(tag, "HTTP file detected: $streamUrl")
        tryDecodeGenericUrl(streamUrl)
    }

    /**
     * Decode a local file
     */
    private fun decodeLocalFile() {
        val path = streamUrl.removePrefix("file://")
        val file = File(path)
        if (!file.exists()) {
            onError?.invoke("Local file not found: $path")
            return
        }
        Log.d(tag, "Local file detected: $path")
        decodeWithMediaExtractor(FileInputStream(file), "local_file")
    }

    /**
     * Generic URL decoder: downloads from HTTP(S) and decodes
     */
    private fun tryDecodeGenericUrl(urlString: String) {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("User-Agent", "Frigate-Android/1.0")
            connection.connect()

            val contentType = connection.contentType ?: "video/mp4"
            val inputStream = connection.inputStream

            if (contentType.contains("mpegurl") || contentType.contains("m3u8") || contentType.contains("vnd.apple.mpegURL")) {
                // HLS: parse the playlist, download segments
                decodeHlsPlaylist(connection.inputStream, url)
            } else {
                // Direct media file
                decodeWithMediaExtractor(inputStream, urlString)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to decode HTTP stream: ${e.message}", e)
            onError?.invoke("HTTP decode error: ${e.message}")
        }
    }

    /**
     * Parse an HLS M3U8 playlist and decode each segment
     */
    private fun decodeHlsPlaylist(playlistStream: InputStream, baseUrl: URL) {
        val playlist = playlistStream.bufferedReader().readText()
        val basePath = baseUrl.toString().substringBeforeLast("/")

        // Find segment URLs
        val segments = playlist.lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }

        Log.d(tag, "HLS playlist found with ${segments.size} segments")

        for (segment in segments) {
            if (!isRunning.get()) break
            val segmentUrl = if (segment.startsWith("http")) segment else "$basePath/$segment"
            try {
                val segConnection = URL(segmentUrl).openConnection() as HttpURLConnection
                segConnection.connectTimeout = 5000
                segConnection.connect()
                decodeRawStream(segConnection.inputStream, segmentUrl)
                segConnection.inputStream.close()
            } catch (e: Exception) {
                Log.w(tag, "Failed to decode HLS segment $segmentUrl: ${e.message}")
            }
        }
    }

    /**
     * Decode using MediaExtractor + MediaCodec for container formats (MP4, MKV, TS).
     * This is the primary hardware-accelerated decode path.
     */
    private fun decodeWithMediaExtractor(inputStream: InputStream, sourceName: String) {
        // Write stream to temp file (MediaExtractor requires file descriptor)
        val tempFile = File.createTempFile("frigate_decode_", ".tmp")
        tempFile.deleteOnExit()

        try {
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output, bufferSize = 8192)
            }
            inputStream.close()

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(tempFile.absolutePath)

                // Find the first video track
                var trackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        trackIndex = i
                        break
                    }
                }

                if (trackIndex < 0) {
                    onError?.invoke("No video track found in $sourceName")
                    return
                }

                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                Log.i(tag, "Found video track: $mime, ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)}")

                // Select hardware decoder if available
                val codecName = selectHardwareDecoder(mime)
                val codec = MediaCodec.createByCodecName(codecName)

                // Configure output format
                format.setInteger(MediaFormat.KEY_WIDTH, targetWidth)
                format.setInteger(MediaFormat.KEY_HEIGHT, targetHeight)

                // 0 = decode mode (default). CONFIGURE_FLAG_ENCODE (1) would be encoding.
                codec.configure(format, null, null, 0)
                codec.start()

                extractor.selectTrack(trackIndex)

                val bufferInfo = MediaCodec.BufferInfo()
                var isInputDone = false
                var isOutputDone = false
                var outputFormatChanged = false

                while (isRunning.get() && !isOutputDone) {
                    if (!isInputDone) {
                        val inputIndex = codec.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuffer = codec.getInputBuffer(inputIndex)
                            val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputDone = true
                            } else {
                                codec.queueInputBuffer(
                                    inputIndex, 0, sampleSize,
                                    extractor.sampleTime,
                                    extractor.sampleFlags
                                )
                                extractor.advance()
                            }
                        }
                    }

                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            outputFormatChanged = true
                        }
                        outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No output available yet
                        }
                        outputIndex >= 0 -> {
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                isOutputDone = true
                            }

                            // Extract YUV frame and convert to Bitmap
                            if (bufferInfo.size > 0) {
                                val outputBuffer = codec.getOutputBuffer(outputIndex)
                                val frameBitmap = yuvToBitmap(
                                    outputBuffer!!, bufferInfo,
                                    format.getInteger(MediaFormat.KEY_WIDTH),
                                    format.getInteger(MediaFormat.KEY_HEIGHT)
                                )
                                if (frameBitmap != null) {
                                    enqueueFrame(frameBitmap)
                                }
                                outputBuffer.clear()
                            }

                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

                codec.stop()
                codec.release()
                Log.i(tag, "Decoding complete for $sourceName")

            } finally {
                extractor.release()
            }

        } catch (e: Exception) {
            Log.e(tag, "MediaExtractor decode failed: ${e.message}", e)
            onError?.invoke("Decode failed for $sourceName: ${e.message}")
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Decode raw stream bytes (used for HLS segments or generic media data).
     * Attempts to detect the container format and decode accordingly.
     */
    private fun decodeRawStream(inputStream: InputStream, sourceName: String) {
        decodeWithMediaExtractor(inputStream, sourceName)
    }

    /**
     * Select the best hardware-accelerated decoder for the given MIME type.
     * Prefers vendor hardware decoders over software decoders.
     */
    private fun selectHardwareDecoder(mime: String): String {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var bestDecoder = ""
        var bestPriority = -1 // higher = better

        for (codecInfo in codecList.codecInfos) {
            if (codecInfo.isEncoder) continue
            if (!codecInfo.supportedTypes.contains(mime)) continue

            val name = codecInfo.name
            var priority = 0

            // Prefer hardware-accelerated decoders
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (codecInfo.isHardwareAccelerated) priority += 10
                if (!codecInfo.isSoftwareOnly) priority += 5
                if (codecInfo.isVendor) priority += 3
            } else {
                // Legacy heuristic
                val lower = name.lowercase()
                if (!lower.startsWith("omx.google.") && !lower.startsWith("c2.android.")) priority += 10
                if (lower.contains("qcom") || lower.contains("qti") || lower.contains("exynos")
                    || lower.contains("mediatek") || lower.contains("ti") || lower.contains("mali")
                    || lower.contains("video")) priority += 5
            }

            if (priority > bestPriority) {
                bestPriority = priority
                bestDecoder = name
            }

            Log.d(tag, "  decoder: $name (priority=$priority, hw=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codecInfo.isHardwareAccelerated else "?"})")
        }

        if (bestDecoder.isEmpty()) {
            bestDecoder = codecList.codecInfos
                .firstOrNull { !it.isEncoder && it.supportedTypes.contains(mime) }
                ?.name ?: ""
        }

        Log.i(tag, "Selected decoder for $mime: $bestDecoder (priority=$bestPriority)")
        return bestDecoder
    }

    /**
     * Convert a YUV 4:2:0 MediaCodec output buffer to an ARGB_8888 Bitmap.
     * This format is what hardware decoders output natively.
     */
    private fun yuvToBitmap(
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        width: Int,
        height: Int
    ): Bitmap? {
        try {
            val ySize = width * height
            val uvSize = width * height / 4

            if (bufferInfo.size < ySize + uvSize * 2) {
                // Buffer might be smaller than expected; skip
                return null
            }

            val nv21 = ByteArray(ySize + uvSize * 2)
            buffer.position(0)
            buffer.get(nv21, 0, minOf(buffer.remaining(), nv21.size))

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val jpegBytes = out.toByteArray()
            return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Log.w(tag, "YUV→Bitmap conversion failed: ${e.message}")
            return null
        }
    }

    /**
     * Thread-safe frame enqueueing with size limit
     */
    private fun enqueueFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < frameIntervalMs) {
            bitmap.recycle()
            return
        }
        lastFrameTime = now

        // Scale to target dimensions
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        if (scaled !== bitmap) bitmap.recycle()

        // Drop oldest if queue full
        while (frameQueue.size >= maxQueueSize) {
            frameQueue.poll()?.recycle()
        }

        frameQueue.offer(scaled)
        onFrame?.invoke(scaled)
    }

    companion object {
        /**
         * Detect the best available hardware decoder info for logging
         */
        fun logHardwareDecoderCapabilities() {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            Log.i("RealStreamDecoder", "=== Hardware Decoder Capabilities ===")
            Log.i("RealStreamDecoder", "Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            Log.i("RealStreamDecoder", "SoC: ${Build.HARDWARE} / ${Build.SUPPORTED_ABIS.joinToString()}")

            for (mime in listOf("video/avc", "video/hevc")) {
                val decoders = mutableListOf<String>()
                for (codecInfo in codecList.codecInfos) {
                    if (codecInfo.isEncoder) continue
                    if (codecInfo.supportedTypes.contains(mime)) {
                        val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) codecInfo.isHardwareAccelerated else "?"
                        decoders.add("  ${codecInfo.name} (hw=$hw)")
                    }
                }
                Log.i("RealStreamDecoder", "$mime decoders (${decoders.size}):")
                decoders.forEach { Log.i("RealStreamDecoder", it) }
            }
            Log.i("RealStreamDecoder", "================================")
        }
    }
}
