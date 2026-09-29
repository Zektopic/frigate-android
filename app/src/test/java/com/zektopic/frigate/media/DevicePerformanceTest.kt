package com.zektopic.frigate.media

import org.junit.Assert.assertEquals
import org.junit.Test

class DevicePerformanceTest {

    // The tablet's real numbers: MODEST tier, 32 advertised decoder instances.
    private val tablet = PerformanceBudget(
        tier = DeviceTier.MODEST,
        maxConcurrentStreams = 4,
        maxConcurrentEncoders = 2,
        detectFpsCap = 5,
        streamStartStaggerMs = 1_200L,
        reportedDecoderInstances = 32,
        totalRamMb = 3814,
        cores = 8
    )

    @Test
    fun withoutAnOverrideTheBudgetDecides() {
        assertEquals(4, DevicePerformance.effectiveMaxStreams(tablet, null))
    }

    @Test
    fun anOverrideCanRaiseOrLowerTheCap() {
        assertEquals(5, DevicePerformance.effectiveMaxStreams(tablet, 5))
        assertEquals(2, DevicePerformance.effectiveMaxStreams(tablet, 2))
    }

    @Test
    fun anOverrideNeverTakesTheLastCodecSlot() {
        // One decoder slot stays free for a clip encoder, as in the budget itself.
        assertEquals(31, DevicePerformance.effectiveMaxStreams(tablet, 99))
        val tightPool = tablet.copy(reportedDecoderInstances = 3)
        assertEquals(2, DevicePerformance.effectiveMaxStreams(tightPool, 5))
        assertEquals(1, DevicePerformance.effectiveMaxStreams(tablet.copy(reportedDecoderInstances = 1), 5))
    }
}
