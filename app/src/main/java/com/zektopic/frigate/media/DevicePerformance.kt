package com.zektopic.frigate.media

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * How much work this device can actually sustain.
 *
 * The app was previously sized for whatever the developer's phone could do, which
 * on a Helio G88 tablet means: start seven 2592x1520 HEVC decoders at once, exhaust
 * the codec pool and the low-memory killer's patience, and lose every stream a few
 * minutes later. Everything here exists to pick numbers from the hardware instead.
 */
enum class DeviceTier {
    /** <3GB RAM, few cores, or Android's own low-RAM flag. Survival mode. */
    POTATO,
    /** Mid-range. The common case for a repurposed tablet acting as an NVR head. */
    MODEST,
    /** Plenty of RAM and cores; the limits here are mostly the codec pool. */
    CAPABLE
}

/**
 * Concrete caps derived from [DeviceTier] and the codec pool. Every consumer should
 * read these rather than hardcoding a constant, so a single tier change moves the
 * whole app's footprint together.
 */
data class PerformanceBudget(
    val tier: DeviceTier,
    /** Hard ceiling on simultaneously ingesting cameras. */
    val maxConcurrentStreams: Int,
    /** Hard ceiling on simultaneous clip encoders (shares the codec pool with decoders). */
    val maxConcurrentEncoders: Int,
    /** Upper bound applied on top of each camera's configured fps. */
    val detectFpsCap: Int,
    /** Delay between starting consecutive streams, so codec allocation isn't a thundering herd. */
    val streamStartStaggerMs: Long,
    /** Advertised decoder instance limit that informed [maxConcurrentStreams]. */
    val reportedDecoderInstances: Int,
    val totalRamMb: Long,
    val cores: Int
) {
    fun describe(): String =
        "tier=$tier streams=$maxConcurrentStreams encoders=$maxConcurrentEncoders " +
            "fpsCap=$detectFpsCap stagger=${streamStartStaggerMs}ms " +
            "(ram=${totalRamMb}MB cores=$cores decoderInstances=$reportedDecoderInstances)"
}

object DevicePerformance {
    private const val TAG = "DevicePerformance"

    @Volatile
    private var cached: PerformanceBudget? = null

    /**
     * The already-computed budget, or null if nothing has asked for one yet. Lets
     * diagnostics report the budget without needing a Context; by the time anything
     * can call /api/diag the service has long since computed it.
     */
    fun cachedBudget(): PerformanceBudget? = cached

    /** Cached because probing MediaCodecList is not cheap and the answer cannot change. */
    fun budget(context: Context): PerformanceBudget =
        cached ?: synchronized(this) {
            cached ?: compute(context).also {
                cached = it
                Log.i(TAG, "Performance budget: ${it.describe()}")
            }
        }

    private fun compute(context: Context): PerformanceBudget {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val cores = Runtime.getRuntime().availableProcessors()
        val lowRam = am.isLowRamDevice

        val tier = when {
            lowRam || totalRamMb < 3_000 || cores <= 4 -> DeviceTier.POTATO
            totalRamMb < 6_000 -> DeviceTier.MODEST
            else -> DeviceTier.CAPABLE
        }

        // The advertised instance limit is the closest thing to a real answer for
        // "how many decoders can I have". It is a per-codec hint rather than a
        // guarantee, so it is used as a ceiling, never as a target.
        val decoderInstances = maxDecoderInstances()

        val tierStreams = when (tier) {
            DeviceTier.POTATO -> 2
            DeviceTier.MODEST -> 4
            DeviceTier.CAPABLE -> 8
        }
        // Leave one slot for a clip encoder; encoders and decoders share one pool
        // on most SoCs, and losing every decoder to record one clip is a bad trade.
        val codecCeiling = (decoderInstances - 1).coerceAtLeast(1)

        return PerformanceBudget(
            tier = tier,
            maxConcurrentStreams = minOf(tierStreams, codecCeiling),
            maxConcurrentEncoders = when (tier) {
                DeviceTier.POTATO -> 1
                DeviceTier.MODEST -> 2
                DeviceTier.CAPABLE -> 3
            },
            detectFpsCap = when (tier) {
                DeviceTier.POTATO -> 3
                DeviceTier.MODEST -> 5
                DeviceTier.CAPABLE -> 10
            },
            streamStartStaggerMs = when (tier) {
                DeviceTier.POTATO -> 2_000L
                DeviceTier.MODEST -> 1_200L
                DeviceTier.CAPABLE -> 600L
            },
            reportedDecoderInstances = decoderInstances,
            totalRamMb = totalRamMb,
            cores = cores
        )
    }

    /**
     * Smallest advertised instance limit across the video decoders we actually use.
     * The minimum, not the maximum: a device that can run eight AVC decoders but only
     * two HEVC ones will still fall over on an all-HEVC camera set.
     */
    private fun maxDecoderInstances(): Int {
        val mimes = listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        var limit = Int.MAX_VALUE
        try {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (mime in info.supportedTypes) {
                    if (mimes.none { it.equals(mime, ignoreCase = true) }) continue
                    val instances = try {
                        info.getCapabilitiesForType(mime).maxSupportedInstances
                    } catch (e: Exception) {
                        continue
                    }
                    if (instances > 0) limit = minOf(limit, instances)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not probe decoder instance limits", e)
        }
        // A conservative default beats an optimistic one: under-using the device is
        // recoverable, exhausting the codec pool is not.
        return if (limit == Int.MAX_VALUE) 4 else limit
    }
}
