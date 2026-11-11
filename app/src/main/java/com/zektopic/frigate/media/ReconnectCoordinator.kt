package com.zektopic.frigate.media

/**
 * Spreads out reconnects when several cameras fail together.
 *
 * Each [StreamIngester] backs off on its own, which is right for one flaky camera.
 * But when the Wi-Fi drops or go2rtc restarts, every stream fails within the same
 * second, every one waits the same two seconds, and they all call
 * `MediaCodec.configure()` together. That is the thundering herd the staggered
 * startup in NvrService exists to avoid, reintroduced on every outage.
 *
 * Ported from the fork's `FfmpegRestartCoordinator`: failures of two or more cameras
 * inside [SHARED_WINDOW_MS] are one shared event, and each camera's restart is pushed
 * back by its arrival order plus a little jitter. A camera failing alone gets no
 * extra delay.
 */
class ReconnectCoordinator(
    private val random: () -> Double = Math::random
) {
    data class Plan(
        /** Delay to add on top of the camera's own backoff. */
        val staggerMs: Long,
        /** Cameras in this shared event, in the order they failed. Empty when alone. */
        val peers: List<String>
    ) {
        val shared: Boolean get() = peers.isNotEmpty()
    }

    private val lastFailureAt = HashMap<String, Long>()

    @Synchronized
    fun reportFailure(cameraId: String, nowMs: Long): Plan {
        lastFailureAt[cameraId] = nowMs
        lastFailureAt.entries.removeAll { nowMs - it.value > RETENTION_MS }

        val members = lastFailureAt.entries
            .filter { nowMs - it.value <= SHARED_WINDOW_MS }
            .sortedWith(compareBy<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
            .map { it.key }
        if (members.size < SHARED_MIN_CAMERAS) return Plan(0L, emptyList())

        val rank = members.indexOf(cameraId).coerceAtLeast(0)
        val stagger = (rank * STAGGER_STEP_MS).coerceAtMost(MAX_STAGGER_MS) +
            (random() * JITTER_MS).toLong()
        return Plan(stagger, members)
    }

    /** A camera that is live again no longer belongs to any shared event. */
    @Synchronized
    fun reportRecovered(cameraId: String) {
        lastFailureAt.remove(cameraId)
    }

    companion object {
        const val SHARED_WINDOW_MS = 30_000L
        const val SHARED_MIN_CAMERAS = 2
        const val STAGGER_STEP_MS = 2_000L
        const val MAX_STAGGER_MS = 30_000L
        const val JITTER_MS = 2_000L
        private const val RETENTION_MS = 600_000L

        /** One coordinator for the process: every ingester shares the codec pool. */
        val shared = ReconnectCoordinator()
    }
}
