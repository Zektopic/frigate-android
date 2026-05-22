package com.zektopic.frigate.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

class MotionDetector(private val thresholdPercentage: Double = 0.02) {

    private val tag = "MotionDetector"
    private var baselineGrayscale: IntArray? = null
    
    // Low-resolution sizes for super fast and low-power calculation
    private val compareWidth = 32
    private val compareHeight = 32

    /**
     * Examines an incoming frame and returns true if motion is detected compared to the baseline.
     */
    fun detectMotion(frame: Bitmap): Boolean {
        // Downscale frame to keep CPU usage extremely low
        val downscaled = Bitmap.createScaledBitmap(frame, compareWidth, compareHeight, false)

        val totalPixels = compareWidth * compareHeight
        val currentPixels = IntArray(totalPixels)
        downscaled.getPixels(currentPixels, 0, compareWidth, 0, 0, compareWidth, compareHeight)
        
        // Compute grayscale array for the current frame
        val currentGrayscale = IntArray(totalPixels)
        for (i in 0 until totalPixels) {
            currentGrayscale[i] = getGrayscale(currentPixels[i])
        }

        // Recycle the downscaled bitmap immediately since we have all its pixels
        downscaled.recycle()

        val baseline = baselineGrayscale
        if (baseline == null) {
            baselineGrayscale = currentGrayscale
            return false
        }

        var changedPixelsCount = 0
        val thresholdCount = (totalPixels * thresholdPercentage).toInt()

        // Compare grayscale values
        for (i in 0 until totalPixels) {
            val currentGray = currentGrayscale[i]
            val baselineGray = baseline[i]

            // Pixel is marked "changed" if absolute difference exceeds tolerance
            if (Math.abs(currentGray - baselineGray) > 20) {
                changedPixelsCount++
            }
        }

        // Update the baseline
        baselineGrayscale = currentGrayscale

        val motionDetected = changedPixelsCount >= thresholdCount
        if (motionDetected) {
            Log.v(tag, "Motion detected! Changed pixels: $changedPixelsCount / $totalPixels (Threshold: $thresholdCount)")
        }

        return motionDetected
    }

    private fun getGrayscale(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // Standard luminance calculation coefficients
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    fun reset() {
        baselineGrayscale = null
    }
}
