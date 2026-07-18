package com.zektopic.frigate.media

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes a sequence of [Bitmap] frames into a standard H.264 MP4 file.
 *
 * Uses a MediaCodec AVC encoder fed through its input Surface via a private
 * EGL/GLES2 context (the reliable, device-portable path — CPU colour-format
 * conversion is avoided entirely). All GL and codec work runs on a dedicated
 * thread; [encodeFrame] is safe to call from any thread and copies nothing it
 * doesn't own.
 */
class VideoClipEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val outputFile: File
) {
    private val tag = "VideoClipEncoder"

    private lateinit var encoder: MediaCodec
    private lateinit var inputSurface: Surface
    private lateinit var muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()

    // EGL state for the encoder input surface
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private lateinit var vertexBuffer: java.nio.FloatBuffer

    private val thread = HandlerThread("clip-encoder-${outputFile.name}").apply { start() }
    private val handler = android.os.Handler(thread.looper)

    @Volatile private var started = false
    @Volatile private var frameCount = 0
    private var startPtsNanos = 0L

    fun start(): Boolean {
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        handler.post {
            try {
                val bitRate = (width * height * fps * 0.15).toInt().coerceIn(500_000, 6_000_000)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceAtLeast(1))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = encoder.createInputSurface()
                encoder.start()

                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                setupEgl()
                setupGl()
                started = true
                result.complete(true)
            } catch (e: Exception) {
                Log.e(tag, "Failed to start encoder", e)
                result.complete(false)
            }
        }
        return try { result.get(3, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { false }
    }

    /** Queue a frame for encoding. The bitmap is read on the encoder thread; do not recycle it before this returns. */
    fun encodeFrame(bitmap: Bitmap) {
        if (!started || bitmap.isRecycled) return
        // Copy so the caller can recycle/reuse its bitmap freely
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return
        handler.post {
            if (!started) { copy.recycle(); return@post }
            try {
                drainEncoder(false)
                if (startPtsNanos == 0L) startPtsNanos = System.nanoTime()
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, copy, 0)
                drawQuad()
                val pts = System.nanoTime() - startPtsNanos
                android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, pts)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                frameCount++
            } catch (e: Exception) {
                Log.e(tag, "encodeFrame error", e)
            } finally {
                copy.recycle()
            }
        }
    }

    /** Finalize the MP4. Returns true if a playable file with at least one frame was written. */
    fun stop(): Boolean {
        if (!started) { quitThread(); return false }
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        handler.post {
            var ok = false
            try {
                if (frameCount > 0) {
                    encoder.signalEndOfInputStream()
                    drainEncoder(true)
                    ok = muxerStarted
                }
            } catch (e: Exception) {
                Log.e(tag, "stop/drain error", e)
            } finally {
                releaseAll()
                started = false
                result.complete(ok && outputFile.exists() && outputFile.length() > 0)
            }
        }
        val ok = try { result.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { false }
        quitThread()
        return ok
    }

    // --- encoder drain ---
    private fun drainEncoder(endOfStream: Boolean) {
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    val encoded = encoder.getOutputBuffer(outIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    // --- EGL / GL helpers ---
    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfig, 0)
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        val surfAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], inputSurface, surfAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    private fun setupGl() {
        val vs = """
            attribute vec4 aPos; attribute vec2 aTex; varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = aTex; }
        """.trimIndent()
        val fs = """
            precision mediump float; varying vec2 vTex; uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vTex); }
        """.trimIndent()
        program = GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
        }
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // Fullscreen quad, texture flipped vertically (bitmap origin is top-left, GL is bottom-left)
        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f,  1f, 1f, 0f
        )
        vertexBuffer = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(verts).also { it.position(0) }
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun drawQuad() {
        val posLoc = GLES20.glGetAttribLocation(program, "aPos")
        val texLoc = GLES20.glGetAttribLocation(program, "aTex")
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun releaseAll() {
        try { if (muxerStarted) muxer.stop() } catch (e: Exception) {}
        try { muxer.release() } catch (e: Exception) {}
        try { encoder.stop() } catch (e: Exception) {}
        try { encoder.release() } catch (e: Exception) {}
        try { inputSurface.release() } catch (e: Exception) {}
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
        } catch (e: Exception) {}
    }

    private fun quitThread() {
        try { thread.quitSafely() } catch (e: Exception) {}
    }
}
