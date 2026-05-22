package com.zektopic.frigate

import android.graphics.Bitmap
import android.graphics.Color
import com.zektopic.frigate.ai.MotionDetector
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any

class MotionDetectorTest {

    private lateinit var motionDetector: MotionDetector

    @Before
    fun setUp() {
        motionDetector = MotionDetector(thresholdPercentage = 0.01)
    }

    @Test
    fun testNoMotionDetectedForIdenticalFrames() {
        val mockBitmap = mock(Bitmap::class.java)
        
        mockStatic(Bitmap::class.java).use { mockedBitmap ->
            mockedBitmap.`when`<Bitmap> { 
                Bitmap.createScaledBitmap(
                    org.mockito.ArgumentMatchers.any(Bitmap::class.java), 
                    org.mockito.ArgumentMatchers.anyInt(), 
                    org.mockito.ArgumentMatchers.anyInt(), 
                    org.mockito.ArgumentMatchers.anyBoolean()
                ) 
            }.thenReturn(mockBitmap)

            mockStatic(Color::class.java).use { mockedColor ->
                mockedColor.`when`<Int> { Color.red(org.mockito.ArgumentMatchers.anyInt()) }.thenReturn(0)
                mockedColor.`when`<Int> { Color.green(org.mockito.ArgumentMatchers.anyInt()) }.thenReturn(0)
                mockedColor.`when`<Int> { Color.blue(org.mockito.ArgumentMatchers.anyInt()) }.thenReturn(0)

                // First frame establishes the baseline
                val firstResult = motionDetector.detectMotion(mockBitmap)
                assertFalse("First frame should serve as baseline and return false", firstResult)

                // Second frame has identical pixels (all 0 in mock)
                val secondResult = motionDetector.detectMotion(mockBitmap)
                assertFalse("Identical consecutive frames should report no motion", secondResult)
            }
        }
    }
}
