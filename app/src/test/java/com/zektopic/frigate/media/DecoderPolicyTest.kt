package com.zektopic.frigate.media

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the pure decoder ranking policy.
 *
 * [DecoderPolicy.rankCandidates] is deliberately free of Android types so the whole
 * selection policy is testable on the JVM — real `MediaCodecInfo`/`CodecCapabilities`
 * cannot be constructed here.
 */
class DecoderPolicyTest {

    private fun hw(
        name: String,
        supportsSize: Boolean? = null,
        maxInstances: Int = DecoderCandidate.MAX_INSTANCES_UNKNOWN
    ) = DecoderCandidate(name, hardwareAccelerated = true, softwareOnly = false, supportsSize = supportsSize, maxInstances = maxInstances)

    private fun sw(name: String, supportsSize: Boolean? = null) =
        DecoderCandidate(name, hardwareAccelerated = false, softwareOnly = true, supportsSize = supportsSize)

    // --- vendor classification (labeling / tie-breaking only) ---

    @Test
    fun vendorOf_classifiesAllThreeSocFamilies() {
        assertEquals(DecoderVendor.QUALCOMM, DecoderPolicy.vendorOf("c2.qti.hevc.decoder"))
        assertEquals(DecoderVendor.QUALCOMM, DecoderPolicy.vendorOf("OMX.qcom.video.decoder.hevc"))
        assertEquals(DecoderVendor.MEDIATEK, DecoderPolicy.vendorOf("c2.mtk.hevc.decoder"))
        assertEquals(DecoderVendor.MEDIATEK, DecoderPolicy.vendorOf("OMX.MTK.VIDEO.DECODER.HEVC"))
        assertEquals(DecoderVendor.EXYNOS, DecoderPolicy.vendorOf("c2.exynos.hevc.decoder"))
        assertEquals(DecoderVendor.EXYNOS, DecoderPolicy.vendorOf("OMX.Exynos.HEVC.Decoder"))
    }

    @Test
    fun vendorOf_doesNotTreatOmxSecAsExynosHardware() {
        // media3's MediaCodecUtil lists "omx.sec." among its software-only prefixes.
        // Classifying it as Exynos here would promote codecs media3 demotes on purpose.
        assertNotEquals(DecoderVendor.EXYNOS, DecoderPolicy.vendorOf("OMX.SEC.hevc.dec"))
    }

    // --- core ranking ---

    @Test
    fun ranksHardwareAboveSoftware() {
        val ranked = DecoderPolicy.rankCandidates(
            listOf(sw("c2.android.hevc.decoder"), hw("c2.qti.hevc.decoder"))
        )
        assertEquals("c2.qti.hevc.decoder", ranked.first().name)
    }

    @Test
    fun demotesHardwareThatRejectsTheStreamSize_belowWorkingSoftware() {
        // The real 2560x1440 case: the low_latency decoder is offered but throws in
        // native_configure. It must rank below software rather than be tried first.
        val ranked = DecoderPolicy.rankCandidates(
            listOf(
                hw("c2.qti.hevc.decoder.low_latency", supportsSize = false),
                sw("c2.android.hevc.decoder", supportsSize = true)
            )
        )
        assertEquals("c2.android.hevc.decoder", ranked.first().name)
        assertEquals("c2.qti.hevc.decoder.low_latency", ranked.last().name)
    }

    @Test
    fun prefersHardwareThatSupportsTheSizeOverHardwareThatDoesNot() {
        val ranked = DecoderPolicy.rankCandidates(
            listOf(
                hw("c2.qti.hevc.decoder.low_latency", supportsSize = false),
                hw("c2.qti.hevc.decoder", supportsSize = true)
            )
        )
        assertEquals("c2.qti.hevc.decoder", ranked.first().name)
    }

    @Test
    fun confirmedSizeSupportOutranksUnknownSizeSupport() {
        val ranked = DecoderPolicy.rankCandidates(
            listOf(hw("c2.mtk.hevc.decoder"), hw("c2.qti.hevc.decoder", supportsSize = true))
        )
        assertEquals("c2.qti.hevc.decoder", ranked.first().name)
    }

    // --- the two hard guarantees ---

    @Test
    fun neverDropsCandidates_evenWhenAllRejectTheSize() {
        // If filtering emptied the list, ExoPlayer would fail with a different and
        // less recoverable error than it does today. Demote, never remove.
        val input = listOf(
            hw("c2.qti.hevc.decoder", supportsSize = false),
            sw("c2.android.hevc.decoder", supportsSize = false)
        )
        val ranked = DecoderPolicy.rankCandidates(input)
        assertEquals(input.size, ranked.size)
        assertEquals(input.map { it.name }.toSet(), ranked.map { it.name }.toSet())
    }

    @Test
    fun resultIsAlwaysAPermutationOfTheInput() {
        val input = listOf(
            sw("c2.android.avc.decoder"),
            hw("c2.qti.avc.decoder", supportsSize = true),
            hw("c2.qti.avc.decoder.low_latency", supportsSize = false),
            hw("c2.mtk.avc.decoder")
        )
        val ranked = DecoderPolicy.rankCandidates(input, DecoderVendor.QUALCOMM)
        assertEquals(input.size, ranked.size)
        assertEquals(input.sortedBy { it.name }, ranked.sortedBy { it.name })
    }

    @Test
    fun unknownSizeSupportNeverDemotes() {
        // Before the stream geometry is observed every candidate has supportsSize=null.
        // Ranking must then fall back to plain hardware-first, with nothing demoted.
        val ranked = DecoderPolicy.rankCandidates(
            listOf(sw("c2.android.hevc.decoder"), hw("c2.qti.hevc.decoder"), hw("c2.mtk.hevc.decoder"))
        )
        assertTrue(ranked.take(2).all { it.hardwareAccelerated })
        assertEquals("c2.android.hevc.decoder", ranked.last().name)
    }

    @Test
    fun emptyAndSingletonInputsArePassedThrough() {
        assertTrue(DecoderPolicy.rankCandidates(emptyList()).isEmpty())
        val one = listOf(hw("c2.qti.hevc.decoder"))
        assertEquals(one, DecoderPolicy.rankCandidates(one))
    }

    // --- tie-breaking ---

    @Test
    fun breaksTiesTowardsThisDevicesSocVendor() {
        val candidates = listOf(hw("c2.mtk.hevc.decoder"), hw("c2.qti.hevc.decoder"))
        assertEquals(
            "c2.qti.hevc.decoder",
            DecoderPolicy.rankCandidates(candidates, DecoderVendor.QUALCOMM).first().name
        )
        assertEquals(
            "c2.mtk.hevc.decoder",
            DecoderPolicy.rankCandidates(candidates, DecoderVendor.MEDIATEK).first().name
        )
        assertEquals(
            "c2.exynos.hevc.decoder",
            DecoderPolicy.rankCandidates(
                listOf(hw("c2.qti.hevc.decoder"), hw("c2.exynos.hevc.decoder")),
                DecoderVendor.EXYNOS
            ).first().name
        )
    }

    @Test
    fun vendorTieBreakNeverOutranksSizeSupport() {
        // A same-vendor decoder that cannot handle the stream must still lose to a
        // foreign-vendor decoder that can.
        val ranked = DecoderPolicy.rankCandidates(
            listOf(
                hw("c2.qti.hevc.decoder", supportsSize = false),
                hw("c2.mtk.hevc.decoder", supportsSize = true)
            ),
            DecoderVendor.QUALCOMM
        )
        assertEquals("c2.mtk.hevc.decoder", ranked.first().name)
    }

    @Test
    fun preservesMedia3OrderingWithinATier() {
        // Input arrives sorted by media3's format-support scoring; a stable sort must
        // keep that order where this policy has no reason to override it.
        val input = listOf(hw("c2.qti.hevc.decoder"), hw("c2.qti.hevc.decoder.secondary"))
        val ranked = DecoderPolicy.rankCandidates(input, DecoderVendor.QUALCOMM)
        assertEquals(input.map { it.name }, ranked.map { it.name })
    }

    // --- applies to H.264 as well as HEVC (the old selector was HEVC-only) ---

    @Test
    fun ranksAvcDecodersToo() {
        val ranked = DecoderPolicy.rankCandidates(
            listOf(sw("c2.android.avc.decoder"), hw("c2.qti.avc.decoder", supportsSize = true))
        )
        assertEquals("c2.qti.avc.decoder", ranked.first().name)
    }

    @Test
    fun vendorTieBreakIsSkippedWhenTheDeviceSocIsUnknown() {
        // deviceVendor() returns UNKNOWN on unrecognised SoCs; ranking must then fall
        // back to media3's original ordering rather than preferring an arbitrary vendor.
        val input = listOf(hw("c2.mtk.hevc.decoder"), hw("c2.qti.hevc.decoder"))
        assertEquals(
            input.map { it.name },
            DecoderPolicy.rankCandidates(input, DecoderVendor.UNKNOWN).map { it.name }
        )
    }

    // --- diagnostics ---

    @Test
    fun describeReportsVendorKindAndSizeSupport() {
        assertEquals(
            "c2.qti.hevc.decoder (Qualcomm, HW)",
            DecoderPolicy.describe(hw("c2.qti.hevc.decoder", supportsSize = true))
        )
        assertTrue(
            DecoderPolicy.describe(hw("c2.qti.hevc.decoder.low_latency", supportsSize = false))
                .contains("size-unsupported")
        )
        assertTrue(DecoderPolicy.describe(hw("c2.mtk.hevc.decoder")).contains("size-unknown"))
    }

    @Test
    fun maxInstancesIsCarriedThroughForDiagnostics() {
        val ranked = DecoderPolicy.rankCandidates(
            listOf(sw("c2.android.hevc.decoder"), hw("c2.qti.hevc.decoder", supportsSize = true, maxInstances = 6))
        )
        assertEquals(6, ranked.first().maxInstances)
    }
}
