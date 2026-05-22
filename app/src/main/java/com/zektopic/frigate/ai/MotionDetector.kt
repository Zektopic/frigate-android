package com.zektopic.frigate.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log

class MotionDetector(private val thresholdPercentage: Double = 0.02) {

    private val tag = "MotionDetector"
    private var baselineFrame: Bitmap? = null
    
    // Low-resolution sizes for super fast and low-power calculation
    private val compareWidth = 32
    private val compareHeight = 32

    /**
     * Examines a incoming frame and returns true if motion is detected compared to the baseline.
     */
    fun detectMotion(frame: Bitmap): Boolean {
        // Downscale frame to keep CPU usage extremely low
        val downscaled = Bitmap.createScaledBitmap(frame, compareWidth, compareHeight, false)

        if (baselineFrame == null) {
            baselineFrame = downscaled
            return false
        }

        var changedPixelsCount = 0
        val totalPixels = compareWidth * compareHeight
        val thresholdCount = (totalPixels * thresholdPercentage).toInt()

        // Scan pixels and compare grayscale values
        for (x in 0 until compareWidth) {
            for (y in 0 until compareHeight) {
                val currentPixel = downscaled.getPixel(x, y)
                val baselinePixel = baselineFrame!!.getPixel(x, y)

                val currentGray = getGrayscale(currentPixel)
                val baselineGray = getGrayscale(baselinePixel)

                // Pixel is marked "changed" if absolute difference exceeds tolerance
                if (Math.abs(currentGray - baselineGray) > 20) {
                    changedPixelsCount++
                }
            }
        }

        // Recycle old baseline and update to the current frame
        baselineFrame?.recycle()
        baselineFrame = downscaled

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
        baselineFrame?.recycle()
        baselineFrame = null
    }
}
