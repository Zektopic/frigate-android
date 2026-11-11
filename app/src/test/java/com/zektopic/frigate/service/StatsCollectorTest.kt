package com.zektopic.frigate.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StatsCollectorTest {

    private val cameras = listOf("front")

    @Test
    fun theFirstSampleOnlySetsABaseline() {
        val c = StatsCollector(cores = 4)
        assertNull(c.sample(1_000, cameras, { 10 }, cpuTimeMs = 500, memoryPercent = 40f, heapMb = 64f))
    }

    @Test
    fun ratesAreDeltasOverTheInterval() {
        val c = StatsCollector(cores = 4)
        c.sample(1_000, cameras, { 100 }, cpuTimeMs = 1_000, memoryPercent = 40f, heapMb = 64f)

        repeat(10) { c.onFrameProcessed("front", motionNanos = 3_000_000) }
        repeat(4) { c.onFrameSkipped("front") }

        // 2 s later: 20 more frames, 800 ms of CPU across 4 cores = 10 %
        val sample = c.sample(3_000, cameras, { 120 }, cpuTimeMs = 1_800, memoryPercent = 41f, heapMb = 70f)
        assertNotNull(sample)
        val front = sample!!.cameras.getValue("front")
        assertEquals(10f, front.cameraFps, 0.001f)
        assertEquals(5f, front.detectFps, 0.001f)
        assertEquals(2f, front.skippedFps, 0.001f)
        assertEquals(3f, front.motionMs, 0.001f)
        assertEquals(10f, sample.cpuPercent, 0.001f)
        assertEquals(3f, sample.motionMs, 0.001f)
        assertEquals(41f, sample.memoryPercent, 0f)
    }

    @Test
    fun aRestartedCameraDoesNotProduceANegativeRate() {
        val c = StatsCollector(cores = 4)
        c.sample(1_000, cameras, { 5_000 }, 0, 0f, 0f)
        // The stream restarted and its frame counter started over
        val sample = c.sample(3_000, cameras, { 12 }, 0, 0f, 0f)!!
        assertEquals(0f, sample.cameras.getValue("front").cameraFps, 0f)
    }

    @Test
    fun anIdleCameraReportsZeroDetectionTimeNotNaN() {
        val c = StatsCollector(cores = 4)
        c.sample(1_000, cameras, { 0 }, 0, 0f, 0f)
        val sample = c.sample(3_000, cameras, { 0 }, 0, 0f, 0f)!!
        assertEquals(0f, sample.cameras.getValue("front").motionMs, 0f)
        assertEquals(0f, sample.motionMs, 0f)
    }
}
