package com.zektopic.frigate.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectCoordinatorTest {

    // No jitter, so delays are exact
    private fun coordinator(jitter: Double = 0.0) = ReconnectCoordinator { jitter }

    @Test
    fun aCameraFailingAloneIsNotDelayed() {
        val plan = coordinator().reportFailure("front", 1_000)
        assertFalse(plan.shared)
        assertEquals(0L, plan.staggerMs)
    }

    @Test
    fun camerasFailingTogetherRestartInArrivalOrder() {
        val c = coordinator()
        c.reportFailure("front", 1_000)
        val second = c.reportFailure("garden", 1_200)
        val third = c.reportFailure("kitchen", 1_400)

        assertTrue(second.shared)
        assertEquals(listOf("front", "garden"), second.peers)
        assertEquals(2_000L, second.staggerMs)
        assertEquals(4_000L, third.staggerMs)
    }

    @Test
    fun aCameraFailingAgainQueuesBehindThoseStillWaiting() {
        val c = coordinator()
        c.reportFailure("front", 1_000)
        c.reportFailure("garden", 1_200)
        // front's first reconnect failed too; garden is still older than this report
        val again = c.reportFailure("front", 3_500)
        assertEquals(listOf("garden", "front"), again.peers)
        assertEquals(2_000L, again.staggerMs)
    }

    @Test
    fun failuresFurtherApartThanTheWindowAreUnrelated() {
        val c = coordinator()
        c.reportFailure("front", 1_000)
        val later = c.reportFailure("garden", 1_000 + ReconnectCoordinator.SHARED_WINDOW_MS + 1)
        assertFalse(later.shared)
        assertEquals(0L, later.staggerMs)
    }

    @Test
    fun aRecoveredCameraLeavesTheEvent() {
        val c = coordinator()
        c.reportFailure("front", 1_000)
        c.reportRecovered("front")
        assertFalse(c.reportFailure("garden", 2_000).shared)
    }

    @Test
    fun theStaggerIsCapped() {
        val c = coordinator()
        var last = ReconnectCoordinator.Plan(0, emptyList())
        for (i in 0 until 40) last = c.reportFailure("cam%02d".format(i), 1_000L + i)
        assertEquals(ReconnectCoordinator.MAX_STAGGER_MS, last.staggerMs)
    }

    @Test
    fun jitterIsAddedOnTopOfTheSlot() {
        val c = coordinator(jitter = 0.5)
        c.reportFailure("front", 1_000)
        assertEquals(2_000L + ReconnectCoordinator.JITTER_MS / 2, c.reportFailure("garden", 1_100).staggerMs)
    }
}
