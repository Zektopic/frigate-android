package com.zektopic.frigate

import android.content.Context
import android.graphics.Bitmap
import com.zektopic.frigate.ai.ObjectDetector
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class ObjectDetectorTest {

    private lateinit var context: Context
    private lateinit var objectDetector: ObjectDetector

    @Before
    fun setUp() {
        context = mock(Context::class.java)
        // Instantiate using mock context; loads mock/simulated pipeline since assets are empty in test runtime
        objectDetector = ObjectDetector(context)
    }

    @Test
    fun testDetectorReturnsStructuredOutputs() {
        val mockBitmap = mock(Bitmap::class.java)
        
        // Exercise detection runs
        val results = objectDetector.detectObjects(mockBitmap, confidenceThreshold = 0.5f)
        
        // Assert outputs are structured correctly (even when falling back to simulated inference block)
        assertNotNull("Detection results should never be null", results)
        for (result in results) {
            assertNotNull("Detected label should not be null", result.label)
            assert(result.confidence in 0.0f..1.0f) { "Confidence score ${result.confidence} is out of bounds [0.0, 1.0]" }
            assertNotNull("Bounding box coordinates should exist", result.boundingBox)
        }
    }
}
