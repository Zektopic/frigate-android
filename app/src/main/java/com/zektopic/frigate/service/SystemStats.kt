package com.zektopic.frigate.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** One camera's throughput over a sampling interval. */
data class CameraStats(
    /** Frames extracted from the stream per second. */
    val cameraFps: Float = 0f,
    /** Frames the motion detector actually analysed per second. */
    val detectFps: Float = 0f,
    /** Frames dropped per second because the detector was still busy with the previous one. */
    val skippedFps: Float = 0f,
    /** Mean motion-detection time per frame, in milliseconds. */
    val motionMs: Float = 0f
)

/** A point on the System page's graphs. */
data class StatsSample(
    val timestamp: Long,
    /** This process's CPU use as a share of all cores, 0-100. */
    val cpuPercent: Float,
    /** Device memory in use, 0-100. */
    val memoryPercent: Float,
    /** This process's Java heap in use, in MB. */
    val heapMb: Float,
    /** Mean motion-detection time across cameras, in milliseconds. */
    val motionMs: Float,
    val cameras: Map<String, CameraStats>
)

/**
 * Turns the pipeline's running counters into per-interval rates.
 *
 * The frame path only increments counters; all arithmetic happens on the sampler, so
 * measuring costs the pipeline three atomic adds per frame.
 */
class StatsCollector(
    private val cores: Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
) {
    private class Counters {
        val processed = AtomicLong()
        val skipped = AtomicLong()
        val motionNanos = AtomicLong()
    }

    private data class Snapshot(val frames: Long, val processed: Long, val skipped: Long, val motionNanos: Long)

    private val counters = ConcurrentHashMap<String, Counters>()
    private var previous = HashMap<String, Snapshot>()
    private var lastSampleAt = 0L
    private var lastCpuMs = 0L

    fun onFrameProcessed(cameraId: String, motionNanos: Long) {
        val c = counters.getOrPut(cameraId) { Counters() }
        c.processed.incrementAndGet()
        c.motionNanos.addAndGet(motionNanos)
    }

    fun onFrameSkipped(cameraId: String) {
        counters.getOrPut(cameraId) { Counters() }.skipped.incrementAndGet()
    }

    /**
     * @param frames cumulative frames extracted per camera
     * @param cpuTimeMs cumulative CPU time of this process
     * @return null on the first call, which only establishes a baseline
     */
    fun sample(
        nowMs: Long,
        cameraIds: Collection<String>,
        frames: (String) -> Long,
        cpuTimeMs: Long,
        memoryPercent: Float,
        heapMb: Float
    ): StatsSample? {
        val current = HashMap<String, Snapshot>()
        for (id in cameraIds) {
            val c = counters[id]
            current[id] = Snapshot(
                frames(id),
                c?.processed?.get() ?: 0L,
                c?.skipped?.get() ?: 0L,
                c?.motionNanos?.get() ?: 0L
            )
        }
        counters.keys.retainAll(cameraIds.toSet())

        val elapsedMs = nowMs - lastSampleAt
        val first = lastSampleAt == 0L || elapsedMs <= 0L
        val result = if (first) null else {
            val seconds = elapsedMs / 1000f
            var totalNanos = 0L
            var totalProcessed = 0L
            val perCamera = LinkedHashMap<String, CameraStats>()
            for (id in cameraIds) {
                val now = current.getValue(id)
                // A camera restarted since the last sample has counters that went
                // backwards; treat it as starting from zero rather than a negative rate.
                val before = previous[id]?.takeIf { it.frames <= now.frames && it.processed <= now.processed }
                    ?: Snapshot(now.frames, now.processed, now.skipped, now.motionNanos)
                val processed = now.processed - before.processed
                val nanos = now.motionNanos - before.motionNanos
                totalNanos += nanos
                totalProcessed += processed
                perCamera[id] = CameraStats(
                    cameraFps = (now.frames - before.frames) / seconds,
                    detectFps = processed / seconds,
                    skippedFps = (now.skipped - before.skipped).coerceAtLeast(0) / seconds,
                    motionMs = if (processed > 0) nanos / processed / 1_000_000f else 0f
                )
            }
            StatsSample(
                timestamp = nowMs,
                cpuPercent = (100f * (cpuTimeMs - lastCpuMs) / (elapsedMs * cores)).coerceIn(0f, 100f),
                memoryPercent = memoryPercent,
                heapMb = heapMb,
                motionMs = if (totalProcessed > 0) totalNanos / totalProcessed / 1_000_000f else 0f,
                cameras = perCamera
            )
        }
        previous = current
        lastSampleAt = nowMs
        lastCpuMs = cpuTimeMs
        return result
    }

    companion object {
        /** Samples kept for the graphs: three minutes at [SAMPLE_INTERVAL_MS]. */
        const val HISTORY = 90
        const val SAMPLE_INTERVAL_MS = 2_000L
    }
}
