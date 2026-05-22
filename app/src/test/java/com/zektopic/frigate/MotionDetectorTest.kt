package com.zektopic.frigate

import android.graphics.Bitmap
import android.graphics.Color
import com.zektopic.frigate.ai.MotionDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class MotionDetectorTest {

    private lateinit var motionDetector: MotionDetector

    @Before
    fun setUp() {
        // Set motion change threshold to 1% for sensitive checks
        motionDetector = MotionDetector(thresholdPercentage = 0.01)
    }

    @Test
    fun testNoMotionDetectedForIdenticalFrames() {
        val width = 32
        val height = 32
        
        // Create mock bitmap that returns standard values
        val mockBitmap = mock<Bitmap> {
            on { getPixel(any(), any()) } doReturn Color.BLACK
        }
        
        // The first frame establishes the baseline, always returning false
        val firstResult = motionDetector.detectMotion(mockBitmap)
        assertFalse("First frame should serve as baseline and return false", firstResult)

        // The second frame has identical pixels, should return false (no motion)
        val secondResult = motionDetector.detectMotion(mockBitmap)
        assertFalse("Identical consecutive frames should report no motion", secondResult)
    }
}
