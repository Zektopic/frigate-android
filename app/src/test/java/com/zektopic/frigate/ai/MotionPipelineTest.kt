package com.zektopic.frigate.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPipelineTest {

    // 160x90 detect frame analysed at 80x45, so one motion pixel is two frame pixels.
    private val frameWidth = 160
    private val frameHeight = 90
    private val config = MotionConfig(frameHeight = 45, improveContrast = false)

    private fun pipeline(config: MotionConfig = this.config) = MotionPipeline(frameWidth, frameHeight, config)

    /** A flat grey scene with an optional bright rectangle, in motion-frame pixels. */
    private fun scene(p: MotionPipeline, background: Int = 60, rect: IntArray? = null, value: Int = 220): ByteArray {
        val luma = ByteArray(p.motionWidth * p.motionHeight) { background.toByte() }
        if (rect != null) {
            for (y in rect[1] until rect[3]) for (x in rect[0] until rect[2]) {
                luma[y * p.motionWidth + x] = value.toByte()
            }
        }
        return luma
    }

    private fun settle(p: MotionPipeline, frame: ByteArray, frames: Int = 60): MotionResult {
        var last = MotionResult.NONE
        repeat(frames) { last = p.detect(frame) }
        return last
    }

    @Test
    fun aNewSceneIsCalibrationNotMotion() {
        val p = pipeline()
        val first = p.detect(scene(p))
        // The background starts black, so the first frame differs everywhere. That is
        // the detector learning the scene, and must not be reported as an event.
        assertTrue(first.calibrating)
        assertFalse(first.hasMotion)
    }

    @Test
    fun aStaticSceneSettlesAndStaysQuiet() {
        val p = pipeline()
        val settled = settle(p, scene(p))
        assertFalse(settled.calibrating)
        assertFalse(settled.hasMotion)
        assertEquals(0f, settled.motionFraction, 0f)
    }

    @Test
    fun anObjectEnteringASettledSceneIsBoxedInFramePixels() {
        val p = pipeline()
        settle(p, scene(p))

        val result = p.detect(scene(p, rect = intArrayOf(20, 10, 40, 30)))
        assertTrue(result.hasMotion)
        assertEquals(1, result.boxes.size)
        val box = result.boxes.single()
        // Blur and dilation grow the region by a pixel or two on each side; the box is
        // reported at detect resolution, twice the motion frame.
        assertTrue("x1=${box.x1}", box.x1 in 32..40)
        assertTrue("y1=${box.y1}", box.y1 in 12..20)
        assertTrue("x2=${box.x2}", box.x2 in 80..88)
        assertTrue("y2=${box.y2}", box.y2 in 60..68)
    }

    @Test
    fun aRegionSmallerThanContourAreaIsIgnored() {
        val p = pipeline(config.copy(contourArea = 200))
        settle(p, scene(p))
        // 6x6 grows to well under 200 pixels after dilation
        val result = p.detect(scene(p, rect = intArrayOf(20, 10, 26, 16)))
        assertFalse(result.hasMotion)
        assertTrue("the changed area is still measured", result.motionFraction > 0f)
    }

    @Test
    fun aChangeBelowThePixelThresholdIsIgnored() {
        val p = pipeline(config.copy(threshold = 30))
        // Long enough for the average to reach the scene. Calibration ends as soon as
        // the difference drops under the threshold, and from there the background
        // closes the remaining gap at frame_alpha (1% per frame), as upstream does.
        settle(p, scene(p), frames = 600)
        val result = p.detect(scene(p, rect = intArrayOf(20, 10, 40, 30), value = 80)) // +20 luma
        assertFalse(result.hasMotion)
    }

    @Test
    fun aMaskedAreaNeverReportsMotion() {
        val leftHalf = listOf(RelPoint(0f, 0f), RelPoint(0.5f, 0f), RelPoint(0.5f, 1f), RelPoint(0f, 1f))
        val p = pipeline(config.copy(masks = listOf(leftHalf)))
        settle(p, scene(p))

        assertFalse(p.detect(scene(p, rect = intArrayOf(5, 10, 25, 30))).hasMotion)
        // Same object on the unmasked side is seen
        assertTrue(p.detect(scene(p, rect = intArrayOf(50, 10, 70, 30))).hasMotion)
    }

    @Test
    fun aWholeFrameChangeIsTreatedAsLightingAndRecalibrates() {
        val p = pipeline()
        settle(p, scene(p))

        // Lights on: every pixel jumps at once
        val flash = p.detect(scene(p, background = 200))
        assertTrue(flash.calibrating)
        assertFalse(flash.hasMotion)

        // ...and the detector adopts the brighter scene instead of alarming forever
        val after = settle(p, scene(p, background = 200))
        assertFalse(after.calibrating)
        assertFalse(after.hasMotion)
    }

    @Test
    fun skipMotionThresholdDropsTheFrameAndStillLearnsTheNewScene() {
        val p = pipeline(config.copy(skipMotionThreshold = 0.5f))
        settle(p, scene(p))
        val flash = p.detect(scene(p, background = 200))
        assertTrue(flash.boxes.isEmpty())
        assertFalse(settle(p, scene(p, background = 200)).calibrating)
    }

    @Test
    fun aSlowMoverIsStillSeenAgainstTheBackground() {
        // The detector this replaced compared each frame with the previous one, so an
        // object creeping one pixel per frame changed too little to register.
        val p = pipeline()
        settle(p, scene(p))
        var seen = 0
        for (step in 0 until 15) {
            if (p.detect(scene(p, rect = intArrayOf(10 + step, 10, 30 + step, 30))).hasMotion) seen++
        }
        assertEquals(15, seen)
    }

    @Test
    fun disabledDetectionReportsNothing() {
        val p = pipeline(config.copy(enabled = false))
        assertEquals(MotionResult.NONE, p.detect(scene(p, background = 200)))
    }

    @Test
    fun contrastStretchKeepsALowContrastObjectVisible() {
        // A dim scene: background 20, object 45. Raw difference 25 is under the
        // threshold of 30; stretched to the frame's own range it is far above it.
        val dim = MotionConfig(frameHeight = 45, improveContrast = true)
        val p = pipeline(dim)
        val background = ByteArray(p.motionWidth * p.motionHeight) { i -> (20 + (i % 7)).toByte() }
        settle(p, background, frames = 120)
        val withObject = background.copyOf()
        for (y in 10 until 30) for (x in 20 until 40) withObject[y * p.motionWidth + x] = 45
        assertTrue(p.detect(withObject).hasMotion)
    }

    @Test
    fun pointInPolygonHandlesConcaveShapes() {
        // An L: the notch at the top right is outside
        val l = listOf(
            RelPoint(0f, 0f), RelPoint(0.5f, 0f), RelPoint(0.5f, 0.5f),
            RelPoint(1f, 0.5f), RelPoint(1f, 1f), RelPoint(0f, 1f)
        )
        assertTrue(Polygons.contains(l, 0.25f, 0.25f))
        assertTrue(Polygons.contains(l, 0.75f, 0.75f))
        assertFalse(Polygons.contains(l, 0.75f, 0.25f))
        assertFalse(Polygons.contains(l.take(2), 0.1f, 0.1f))
    }
}
