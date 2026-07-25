package com.zektopic.frigate.media

import android.os.Build

/**
 * Which SoC vendor a MediaCodec decoder belongs to.
 *
 * This is used for two narrow jobs only — **labeling** decoders in diagnostics
 * and **tie-breaking** between several hardware candidates. It is deliberately
 * NOT how hardware is detected: media3's [androidx.media3.exoplayer.mediacodec.MediaCodecInfo]
 * already exposes `hardwareAccelerated`/`softwareOnly`, which resolve to the
 * platform APIs on API 29+ and to media3's own name inference below that. Those
 * flags select Qualcomm, MediaTek and Exynos hardware identically, so there is
 * one mechanism here rather than three per-vendor code paths.
 */
enum class DecoderVendor(val label: String) {
    QUALCOMM("Qualcomm"),
    MEDIATEK("MediaTek"),
    EXYNOS("Exynos"),
    OTHER("Other"),
    UNKNOWN("Unknown")
}

/**
 * A decoder under consideration, flattened to plain values.
 *
 * The real [android.media.MediaCodecInfo.CodecCapabilities] cannot be constructed
 * in a JVM unit test, so the policy is expressed over this holder and
 * [DecoderPolicy.rankCandidates] stays a pure function. The adapter that maps
 * real media3 decoder infos into candidates lives in `StreamIngester`.
 *
 * @param supportsSize whether the decoder advertises support for the stream's
 *   resolution/frame rate. `null` means unknown — the stream geometry has not
 *   been observed yet, or the query threw. Unknown never demotes a candidate.
 * @param maxInstances concurrent instances the decoder advertises, or
 *   [MAX_INSTANCES_UNKNOWN]. Recorded for diagnostics; it does not affect ranking.
 */
data class DecoderCandidate(
    val name: String,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val supportsSize: Boolean? = null,
    val maxInstances: Int = MAX_INSTANCES_UNKNOWN
) {
    val vendor: DecoderVendor get() = DecoderPolicy.vendorOf(name)

    /** True when the decoder is known to be unable to handle the stream geometry. */
    val rejectsSize: Boolean get() = supportsSize == false

    companion object {
        const val MAX_INSTANCES_UNKNOWN = -1
    }
}

/**
 * Hardware-first decoder ranking, shared by every camera stream.
 *
 * Replaces the old inline selector that string-matched `OMX.google.`/`c2.android.`
 * for HEVC only. Two things changed and both matter:
 *
 *  1. Hardware detection now comes from media3's own flags rather than name
 *     prefixes, so it covers Snapdragon, MediaTek and Exynos uniformly.
 *  2. Candidates that provably cannot decode the stream's resolution are pushed
 *     to the back. On the test device `c2.qti.hevc.decoder.low_latency` is offered
 *     for a 2560x1440 stream and throws IllegalArgumentException in native_configure;
 *     ExoPlayer then falls all the way back to software, which cannot do 2K.
 */
object DecoderPolicy {

    private val QUALCOMM_PREFIXES = listOf("c2.qti.", "omx.qcom.")
    private val MEDIATEK_PREFIXES = listOf("c2.mtk.", "omx.mtk.")

    // Exynos deliberately excludes "omx.sec." — media3's MediaCodecUtil lists that
    // prefix among its *software-only* names, so treating it as vendor hardware
    // would promote codecs media3 intentionally demotes.
    private val EXYNOS_PREFIXES = listOf("c2.exynos.", "omx.exynos.")

    private val SOFTWARE_PREFIXES = listOf("c2.android.", "omx.google.", "c2.google.", "omx.ffmpeg.")

    /** Classify a decoder by name. Labeling and tie-breaking only — never hardware detection. */
    fun vendorOf(name: String): DecoderVendor {
        val n = name.lowercase(java.util.Locale.US)
        return when {
            QUALCOMM_PREFIXES.any { n.startsWith(it) } -> DecoderVendor.QUALCOMM
            MEDIATEK_PREFIXES.any { n.startsWith(it) } -> DecoderVendor.MEDIATEK
            EXYNOS_PREFIXES.any { n.startsWith(it) } -> DecoderVendor.EXYNOS
            SOFTWARE_PREFIXES.any { n.startsWith(it) } -> DecoderVendor.OTHER
            else -> DecoderVendor.OTHER
        }
    }

    /**
     * Best guess at the SoC vendor of the device we are running on, used only to
     * break ties between equally-ranked hardware decoders.
     */
    fun deviceVendor(): DecoderVendor {
        val soc = buildString {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append(' ')
            }
            append(Build.HARDWARE).append(' ').append(Build.BOARD)
        }.lowercase(java.util.Locale.US)

        return when {
            // "qti" (Qualcomm Technologies Inc) is what SOC_MANUFACTURER reports on
            // e.g. SM6225/bengal, where ro.hardware is the friendlier "qcom".
            soc.contains("qualcomm") || soc.contains("snapdragon") || soc.contains("qcom") ||
                soc.contains("qti") || soc.contains("kona") || soc.contains("lahaina") -> DecoderVendor.QUALCOMM
            soc.contains("mediatek") || soc.contains("mtk") || soc.contains("dimensity") ||
                soc.contains("helio") -> DecoderVendor.MEDIATEK
            soc.contains("exynos") || soc.contains("samsung") || soc.contains("universal") -> DecoderVendor.EXYNOS
            else -> DecoderVendor.UNKNOWN
        }
    }

    /**
     * Preference tier, lower sorts first:
     *
     *  0 — hardware, known to support the stream geometry
     *  1 — hardware, geometry support unknown
     *  2 — software, not known to reject the geometry
     *  3 — anything that explicitly rejects the geometry
     *
     * A rejecting decoder is demoted, never dropped: if every candidate failed the
     * size query, returning an empty list would make ExoPlayer fail with a
     * different (and less recoverable) error than it does today.
     */
    private fun tierOf(c: DecoderCandidate): Int {
        if (c.rejectsSize) return 3
        val isHardware = c.hardwareAccelerated && !c.softwareOnly
        return when {
            isHardware && c.supportsSize == true -> 0
            isHardware -> 1
            else -> 2
        }
    }

    /**
     * Rank decoders best-first.
     *
     * The input is expected to arrive already sorted by media3's format-support
     * scoring; the sort below is stable, so that ordering survives within a tier
     * and this policy only overrides it where it has a reason to.
     *
     * Guarantees, both relied upon by callers:
     *  - the result is a permutation of the input — same size, nothing dropped
     *  - an unknown ([DecoderCandidate.supportsSize] == null) geometry never demotes
     */
    fun rankCandidates(
        candidates: List<DecoderCandidate>,
        deviceVendor: DecoderVendor = DecoderVendor.UNKNOWN
    ): List<DecoderCandidate> {
        if (candidates.size < 2) return candidates
        return candidates.sortedWith(
            compareBy<DecoderCandidate> { tierOf(it) }
                // Within a tier, prefer the decoder from this device's own SoC vendor.
                .thenBy { if (deviceVendor != DecoderVendor.UNKNOWN && it.vendor == deviceVendor) 0 else 1 }
        )
    }

    /** Compact `name (Vendor, HW)` label for logs and diagnostics. */
    fun describe(c: DecoderCandidate): String {
        val kind = if (c.hardwareAccelerated && !c.softwareOnly) "HW" else "SW"
        val size = when (c.supportsSize) {
            true -> ""
            false -> ", size-unsupported"
            null -> ", size-unknown"
        }
        return "${c.name} (${c.vendor.label}, $kind$size)"
    }
}
