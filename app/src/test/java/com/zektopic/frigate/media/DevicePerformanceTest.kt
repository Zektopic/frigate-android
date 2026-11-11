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

    @Test
    fun lowResourcePinsTheCheapestTier() {
        val pinned = DevicePerformance.lowResourceBudget(tablet)
        assertEquals(DeviceTier.POTATO, pinned.tier)
        assertEquals(2, pinned.maxConcurrentStreams)
        assertEquals(1, pinned.maxConcurrentEncoders)
        assertEquals(3, pinned.detectFpsCap)
        assertEquals(2_000L, pinned.streamStartStaggerMs)
        // What the device is does not change, only what is asked of it
        assertEquals(tablet.reportedDecoderInstances, pinned.reportedDecoderInstances)
        assertEquals(tablet.totalRamMb, pinned.totalRamMb)
    }

    @Test
    fun lowResourceNeverRaisesALimit() {
        // A pool so small the device was already below the POTATO stream count
        val tight = tablet.copy(maxConcurrentStreams = 1, reportedDecoderInstances = 2)
        assertEquals(1, DevicePerformance.lowResourceBudget(tight).maxConcurrentStreams)
    }

    @Test
    fun aTierNeverTakesTheLastCodecSlot() {
        assertEquals(8, DevicePerformance.tierBudget(DeviceTier.CAPABLE, 32, 8_000, 8).maxConcurrentStreams)
        assertEquals(3, DevicePerformance.tierBudget(DeviceTier.CAPABLE, 4, 8_000, 8).maxConcurrentStreams)
        assertEquals(1, DevicePerformance.tierBudget(DeviceTier.CAPABLE, 1, 8_000, 8).maxConcurrentStreams)
    }
}
