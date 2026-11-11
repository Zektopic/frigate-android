package com.zektopic.frigate.ai

import android.graphics.Bitmap

/** A point in frame-relative coordinates (0..1 on both axes), as Frigate stores polygons. */
data class RelPoint(val x: Float, val y: Float)

/** A motion bounding box in detect-frame pixels. */
data class MotionBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int)

/**
 * Frigate's `motion:` block. Names, defaults and ranges follow
 * `frigate/config/camera/motion.py`, so a config written for Frigate means the same
 * thing here.
 */
data class MotionConfig(
    val enabled: Boolean = true,
    /** Per-pixel luma difference (1-255) that counts as changed. Higher is less sensitive. */
    val threshold: Int = 30,
    /** Fraction of the frame that, once changed, is treated as a lighting event rather than motion. */
    val lightningThreshold: Float = 0.8f,
    /** When set, a frame changing by more than this fraction is dropped outright. */
    val skipMotionThreshold: Float? = null,
    val improveContrast: Boolean = true,
    /** Minimum changed-pixel area, in motion-frame pixels, for a region to count. */
    val contourArea: Int = 10,
    /** Weight of each new frame in the running background average. */
    val frameAlpha: Float = 0.01f,
    /** Height the frame is scaled to before analysis. */
    val frameHeight: Int = 100,
    /** Polygons that are ignored entirely (timestamps, trees, a busy road). */
    val masks: List<List<RelPoint>> = emptyList()
)

data class MotionResult(
    val boxes: List<MotionBox>,
    /** Fraction of the motion frame covered by changed regions. */
    val motionFraction: Float,
    /** True while the background model is still settling; boxes are not trustworthy yet. */
    val calibrating: Boolean
) {
    /** Motion worth acting on: something moved and the detector is not recalibrating. */
    val hasMotion: Boolean get() = boxes.isNotEmpty() && !calibrating

    companion object {
        val NONE = MotionResult(emptyList(), 0f, false)
    }
}

/**
 * Kotlin port of Frigate's `ImprovedMotionDetector` with the pixel pipeline from the
 * `frigate-motion-rs` crate: contrast stretch, mask, 3x3 Gaussian blur, difference
 * against a running-average background, threshold, cross dilate, then 4-connected
 * regions filtered by area.
 *
 * This replaces a previous-frame comparison on a 32x32 thumbnail. That version had no
 * background model, so a slow mover produced no difference between consecutive frames,
 * and a cloud passing over tripped it the same as a person did. Works on plain luma
 * bytes so it runs (and is tested) without Android.
 *
 * Not thread-safe: one instance per camera, called from one frame at a time.
 */
class MotionPipeline(
    private val frameWidth: Int,
    private val frameHeight: Int,
    val config: MotionConfig
) {
    val motionHeight: Int = config.frameHeight.coerceIn(MIN_MOTION_HEIGHT, frameHeight.coerceAtLeast(MIN_MOTION_HEIGHT))
    val motionWidth: Int = (motionHeight.toLong() * frameWidth / frameHeight.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    private val size = motionWidth * motionHeight
    private val resizeFactor: Float = frameHeight.toFloat() / motionHeight

    private val avgFrame = FloatArray(size)
    private val work = ByteArray(size)
    private val blurTmp = ByteArray(size)
    private val blurred = ByteArray(size)
    private val diff = ByteArray(size)
    private val dilateTmp = ByteArray(size)
    private val fillStack = IntArray(size)
    private val masked: BooleanArray? = rasterizeMasks()

    // Rolling window of the 4th/96th percentile luma, so one dark frame does not swing
    // the contrast stretch. Seeded with the full range, as upstream does.
    private val contrastMin = IntArray(CONTRAST_HISTORY)
    private val contrastMax = IntArray(CONTRAST_HISTORY) { 255 }
    private var contrastIndex = 0
    private val histogram = IntArray(256)

    private var motionFrameCount = 0
    var calibrating = true
        private set

    /**
     * @param luma one byte per pixel, row-major, already scaled to
     *   [motionWidth] x [motionHeight].
     */
    fun detect(luma: ByteArray): MotionResult {
        require(luma.size >= size) { "expected $size luma bytes, got ${luma.size}" }
        if (!config.enabled) return MotionResult.NONE

        System.arraycopy(luma, 0, work, 0, size)
        if (config.improveContrast) stretchContrast(work)
        // After the contrast step, and to zero rather than skipped: the average starts
        // at zero, so a zeroed pixel never differs from it.
        masked?.let { m -> for (i in 0 until size) if (m[i]) work[i] = 0 }

        gaussianBlur3x3(work, blurred)

        val threshold = config.threshold.coerceIn(1, 255)
        for (i in 0 until size) {
            val average = avgFrame[i].let { if (it < 0f) -it else it }.plus(0.5f).toInt().coerceAtMost(255)
            val delta = (blurred[i].toInt() and 0xFF) - average
            diff[i] = if ((if (delta < 0) -delta else delta) > threshold) FOREGROUND else 0
        }
        dilateCross3x3(diff)

        val boxes = ArrayList<MotionBox>()
        val totalArea = findRegions(boxes)
        val motionFraction = totalArea.toFloat() / size

        val skip = config.skipMotionThreshold
        if (skip != null && motionFraction > skip) {
            // A storm or an IR cut: drop the frame and adopt the new scene quickly.
            // Upstream returns before updating the average here, which leaves it
            // comparing against the old scene for as long as the change lasts.
            calibrating = true
            accumulate(CALIBRATION_ALPHA)
            return MotionResult(emptyList(), motionFraction, true)
        }

        if (motionFraction < CALIBRATED_BELOW_FRACTION && boxes.size <= CALIBRATED_MAX_BOXES) {
            calibrating = false
        }
        if (motionFraction > config.lightningThreshold) calibrating = true

        val alpha = if (calibrating) CALIBRATION_ALPHA else config.frameAlpha
        if (boxes.isNotEmpty()) {
            // Only fold a changed scene into the background once the change has lasted,
            // otherwise a person standing still is learned away within a second.
            motionFrameCount++
            if (motionFrameCount >= PERSISTENT_MOTION_FRAMES) accumulate(alpha)
        } else {
            accumulate(alpha)
            motionFrameCount = 0
        }

        return MotionResult(boxes, motionFraction, calibrating)
    }

    private fun accumulate(alpha: Float) {
        val keep = 1f - alpha
        for (i in 0 until size) {
            avgFrame[i] = avgFrame[i] * keep + (blurred[i].toInt() and 0xFF) * alpha
        }
    }

    private fun stretchContrast(frame: ByteArray) {
        java.util.Arrays.fill(histogram, 0)
        for (i in 0 until size) histogram[frame[i].toInt() and 0xFF]++
        val low = percentile(0.04f)
        val high = percentile(0.96f)
        // A single-colour frame has nothing to stretch.
        if (low >= high) return

        contrastMin[contrastIndex] = low
        contrastMax[contrastIndex] = high
        contrastIndex = (contrastIndex + 1) % CONTRAST_HISTORY

        val avgMin = contrastMin.average().toFloat()
        val avgMax = contrastMax.average().toFloat()
        val range = avgMax - avgMin
        if (range <= 0f) return
        for (i in 0 until size) {
            val v = (frame[i].toInt() and 0xFF).toFloat().coerceIn(avgMin, avgMax)
            frame[i] = ((v - avgMin) / range * 255f).toInt().toByte()
        }
    }

    private fun percentile(fraction: Float): Int {
        val target = (size * fraction).toInt()
        var seen = 0
        for (value in 0..255) {
            seen += histogram[value]
            if (seen > target) return value
        }
        return 255
    }

    /** Separable [1 2 1]/4 blur; edge pixels are copied, as in motion-rs. */
    private fun gaussianBlur3x3(src: ByteArray, dst: ByteArray) {
        val w = motionWidth
        val h = motionHeight
        if (w < 3 || h < 3) {
            System.arraycopy(src, 0, dst, 0, size)
            return
        }
        for (y in 0 until h) {
            val row = y * w
            blurTmp[row] = src[row]
            blurTmp[row + w - 1] = src[row + w - 1]
            for (x in 1 until w - 1) {
                val i = row + x
                val sum = (src[i - 1].toInt() and 0xFF) + 2 * (src[i].toInt() and 0xFF) + (src[i + 1].toInt() and 0xFF)
                blurTmp[i] = ((sum + 2) / 4).toByte()
            }
        }
        System.arraycopy(blurTmp, 0, dst, 0, w)
        System.arraycopy(blurTmp, (h - 1) * w, dst, (h - 1) * w, w)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val sum = (blurTmp[i - w].toInt() and 0xFF) + 2 * (blurTmp[i].toInt() and 0xFF) + (blurTmp[i + w].toInt() and 0xFF)
                dst[i] = ((sum + 2) / 4).toByte()
            }
        }
    }

    /** Grows foreground by one pixel up/down/left/right, closing the holes a threshold leaves. */
    private fun dilateCross3x3(buf: ByteArray) {
        val w = motionWidth
        val h = motionHeight
        System.arraycopy(buf, 0, dilateTmp, 0, size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (dilateTmp[i] != 0.toByte()) continue
                val set = (x > 0 && dilateTmp[i - 1] != 0.toByte()) ||
                    (x < w - 1 && dilateTmp[i + 1] != 0.toByte()) ||
                    (y > 0 && dilateTmp[i - w] != 0.toByte()) ||
                    (y < h - 1 && dilateTmp[i + w] != 0.toByte())
                if (set) buf[i] = FOREGROUND
            }
        }
    }

    /**
     * 4-connected flood fill over [diff]. Appends a box per region whose pixel count
     * reaches `contourArea` and returns the pixel count of every region, kept or not.
     */
    private fun findRegions(out: MutableList<MotionBox>): Int {
        val w = motionWidth
        val h = motionHeight
        val minArea = config.contourArea.coerceAtLeast(0)
        var total = 0
        for (start in 0 until size) {
            if (diff[start] != FOREGROUND) continue
            var top = 0
            fillStack[top++] = start
            diff[start] = VISITED
            var area = 0
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            while (top > 0) {
                val i = fillStack[--top]
                val x = i % w
                val y = i / w
                area++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && diff[i - 1] == FOREGROUND) { diff[i - 1] = VISITED; fillStack[top++] = i - 1 }
                if (x < w - 1 && diff[i + 1] == FOREGROUND) { diff[i + 1] = VISITED; fillStack[top++] = i + 1 }
                if (y > 0 && diff[i - w] == FOREGROUND) { diff[i - w] = VISITED; fillStack[top++] = i - w }
                if (y < h - 1 && diff[i + w] == FOREGROUND) { diff[i + w] = VISITED; fillStack[top++] = i + w }
            }
            total += area
            if (area >= minArea) {
                out.add(
                    MotionBox(
                        (minX * resizeFactor).toInt(),
                        (minY * resizeFactor).toInt(),
                        ((maxX + 1) * resizeFactor).toInt().coerceAtMost(frameWidth),
                        ((maxY + 1) * resizeFactor).toInt().coerceAtMost(frameHeight)
                    )
                )
            }
        }
        return total
    }

    private fun rasterizeMasks(): BooleanArray? {
        val polygons = config.masks.filter { it.size >= 3 }
        if (polygons.isEmpty()) return null
        val out = BooleanArray(motionWidth * motionHeight)
        for (y in 0 until motionHeight) {
            val py = (y + 0.5f) / motionHeight
            for (x in 0 until motionWidth) {
                val px = (x + 0.5f) / motionWidth
                if (polygons.any { Polygons.contains(it, px, py) }) out[y * motionWidth + x] = true
            }
        }
        return out
    }

    companion object {
        private const val FOREGROUND: Byte = -1 // 0xFF
        private const val VISITED: Byte = 1
        private const val CONTRAST_HISTORY = 50
        private const val CALIBRATION_ALPHA = 0.2f
        private const val PERSISTENT_MOTION_FRAMES = 10
        private const val CALIBRATED_BELOW_FRACTION = 0.05f
        private const val CALIBRATED_MAX_BOXES = 4
        private const val MIN_MOTION_HEIGHT = 8
    }
}

object Polygons {
    /** Even-odd point-in-polygon test; [polygon] and the point share one coordinate space. */
    fun contains(polygon: List<RelPoint>, x: Float, y: Float): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * Feeds camera frames to a [MotionPipeline], building it lazily from the first frame's
 * real size so boxes come back in that frame's pixels.
 */
class MotionDetector(private val config: MotionConfig = MotionConfig()) {

    private var pipeline: MotionPipeline? = null
    private var pixels = IntArray(0)
    private var luma = ByteArray(0)

    fun detect(frame: Bitmap): MotionResult {
        if (!config.enabled || frame.width <= 0 || frame.height <= 0) return MotionResult.NONE
        val p = pipeline?.takeIf { it.fits(frame) }
            ?: MotionPipeline(frame.width, frame.height, config).also {
                pipeline = it
                sourceWidth = frame.width
                sourceHeight = frame.height
                pixels = IntArray(it.motionWidth * it.motionHeight)
                luma = ByteArray(it.motionWidth * it.motionHeight)
            }

        // Nearest-neighbour, like upstream's INTER_NEAREST: the blur that follows does
        // the smoothing, and filtering here would cost more than the whole pipeline.
        val scaled = Bitmap.createScaledBitmap(frame, p.motionWidth, p.motionHeight, false)
        scaled.getPixels(pixels, 0, p.motionWidth, 0, 0, p.motionWidth, p.motionHeight)
        if (scaled !== frame) scaled.recycle()

        for (i in pixels.indices) {
            val c = pixels[i]
            // Integer BT.601 luma: (77 R + 150 G + 29 B) / 256
            luma[i] = ((77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8).toByte()
        }
        return p.detect(luma)
    }

    private var sourceWidth = 0
    private var sourceHeight = 0

    private fun MotionPipeline.fits(frame: Bitmap): Boolean =
        sourceWidth == frame.width && sourceHeight == frame.height

    /** True until the background model has settled after a start or a lighting change. */
    val isCalibrating: Boolean get() = pipeline?.calibrating ?: true
}
