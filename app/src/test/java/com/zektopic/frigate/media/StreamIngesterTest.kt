package com.zektopic.frigate.media

import androidx.media3.container.NalUnitUtil
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class StreamIngesterTest {

    @Test
    fun testNalUnitParsingOfDummySps() {
        // Test old dummy SPS that parses successfully
        val oldSpsBase64 = "QgEBAWAAAAMAAAMAAAMAAAMAeKAFAgHhaLSuwS7moKDAwBA="
        val oldSpsBytes = Base64.getDecoder().decode(oldSpsBase64)

        try {
            // This is how ExoPlayer's RtspMediaTrack calls it:
            NalUnitUtil.parseH265SpsNalUnitPayload(oldSpsBytes, 2, oldSpsBytes.size)
            println("Old SPS parsed successfully without crash.")
        } catch (e: Exception) {
            fail("Old SPS parsing crashed: ${e.message}")
        }
    }

    @Test
    fun testMaybeModifySdp_stripsAudioAndModifiesH265() {
        val inputSdp = """
            v=0
            o=- 16035 16035 IN IP4 192.168.1.33
            s=RTSP Session
            c=IN IP4 0.0.0.0
            t=0 0
            a=range:npt=now-
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H265/90000
            a=control:trackID=1
            m=audio 0 RTP/AVP 97
            a=rtpmap:97 opus/48000/2
            a=fmtp:97 control:trackID=2
        """.trimIndent()

        val result = RtspInterceptionInputStream.maybeModifySdp(inputSdp)
        
        // Assert audio media and its headers are removed
        assertFalse("Should strip m=audio", result.contains("m=audio"))
        assertFalse("Should strip rtpmap:97", result.contains("rtpmap:97"))
        
        // Assert video media is kept
        assertTrue("Should keep m=video", result.contains("m=video"))
        assertTrue("Should keep rtpmap:96", result.contains("rtpmap:96"))
        assertTrue("Should keep control:trackID=1", result.contains("trackID=1"))
        
        // Assert H265 parameters are injected
        assertTrue("Should inject sprop-vps", result.contains("sprop-vps="))
        assertTrue("Should inject sprop-sps", result.contains("sprop-sps="))
        assertTrue("Should inject sprop-pps", result.contains("sprop-pps="))

        // Assert trailing LF is present
        assertTrue("Should end with a LF", result.endsWith("\n"))
    }

    @Test
    fun testSpropSniffingFromInterleavedRtp() {
        val streamKey = "rtsp://test-host/sniff_stream"
        SpropCache.map.remove(streamKey)

        // DESCRIBE response whose SDP has an H.265 track without sprop params
        val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H265/90000\r\na=control:trackID=0\r\n"
        val sdpBytes = sdp.toByteArray(Charsets.UTF_8)
        val header = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: ${sdpBytes.size}\r\n\r\n"

        // Interleaved RTSP frame wrapping a minimal RTP v2 packet with one NAL
        fun interleaved(nal: ByteArray): ByteArray {
            val rtp = ByteArray(12)
            rtp[0] = 0x80.toByte() // RTP version 2, no CSRC/extension
            val payload = rtp + nal
            return byteArrayOf(0x24, 0x00, ((payload.size shr 8) and 0xFF).toByte(), (payload.size and 0xFF).toByte()) + payload
        }
        // H.265 NAL header: type in bits 6..1 of the first byte
        val vps = byteArrayOf((32 shl 1).toByte(), 0x01, 0x11, 0x22)
        val sps = byteArrayOf((33 shl 1).toByte(), 0x01, 0x33, 0x44, 0x55)
        val pps = byteArrayOf((34 shl 1).toByte(), 0x01, 0x66)

        val wire = header.toByteArray(Charsets.UTF_8) + sdpBytes +
            interleaved(vps) + interleaved(sps) + interleaved(pps)

        val input = RtspInterceptionInputStream(java.io.ByteArrayInputStream(wire), "test", streamKey)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (true) {
            val n = input.read(buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
        }

        val cached = SpropCache.map[streamKey]
        assertNotNull("Sniffer should cache parameter sets", cached)
        val b64 = java.util.Base64.getEncoder()
        assertEquals(b64.encodeToString(vps), cached!!["sprop-vps"])
        assertEquals(b64.encodeToString(sps), cached["sprop-sps"])
        assertEquals(b64.encodeToString(pps), cached["sprop-pps"])

        // Interleaved data must pass through byte-identical
        val tail = interleaved(vps) + interleaved(sps) + interleaved(pps)
        val outBytes = out.toByteArray()
        assertArrayEquals(tail, outBytes.copyOfRange(outBytes.size - tail.size, outBytes.size))

        // A subsequent SDP rewrite for this stream must inject the sniffed sets
        val reinjected = RtspInterceptionInputStream.maybeModifySdp(sdp, "test", streamKey)
        assertTrue("Should inject sniffed SPS", reinjected.contains(b64.encodeToString(sps)))
        SpropCache.map.remove(streamKey)
    }

    @Test
    fun h264SdpWithoutSpropIsFlaggedForInjection() {
        // Exactly what go2rtc returns for a `?video=h264` transcode; media3 rejects
        // this with "missing sprop parameter".
        val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1\r\na=control:trackID=0\r\n"
        assertTrue(RtspInterceptionInputStream.sdpNeedsSpropInjection(sdp))
        assertTrue(RtspInterceptionInputStream.sdpIsH264(sdp))
    }

    @Test
    fun h264SdpWithSpropIsLeftAlone() {
        val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0LgHtoBQBbpUA==,aM4wpIA=\r\n"
        assertFalse(RtspInterceptionInputStream.sdpNeedsSpropInjection(sdp))
    }

    @Test
    fun h264SpropIsAppendedToExistingFmtpOnceSniffed() {
        val streamKey = "rtsp://test-host/h264_stream"
        SpropCache.map[streamKey] = mapOf("h264-sps" to "AAAA", "h264-pps" to "BBBB")
        try {
            val lines = listOf(
                "m=video 0 RTP/AVP 96",
                "a=rtpmap:96 H264/90000",
                "a=fmtp:96 packetization-mode=1",
                "a=control:trackID=0"
            )
            val out = RtspInterceptionInputStream.injectH264SpropIfNeeded(lines, "test", streamKey)
            val fmtp = out.single { it.startsWith("a=fmtp:96") }
            assertTrue(fmtp.contains("sprop-parameter-sets=AAAA,BBBB"))
            assertTrue("must keep the server's own params", fmtp.contains("packetization-mode=1"))
            assertEquals("no lines added or dropped", lines.size, out.size)
        } finally {
            SpropCache.map.remove(streamKey)
        }
    }

    @Test
    fun h264FmtpLineIsCreatedWhenServerSendsNone() {
        val streamKey = "rtsp://test-host/h264_nofmtp"
        SpropCache.map[streamKey] = mapOf("h264-sps" to "AAAA", "h264-pps" to "BBBB")
        try {
            val lines = listOf("m=video 0 RTP/AVP 96", "a=rtpmap:96 H264/90000", "a=control:trackID=0")
            val out = RtspInterceptionInputStream.injectH264SpropIfNeeded(lines, "test", streamKey)
            val fmtp = out.single { it.startsWith("a=fmtp:96") }
            assertTrue(fmtp.contains("sprop-parameter-sets=AAAA,BBBB"))
        } finally {
            SpropCache.map.remove(streamKey)
        }
    }

    @Test
    fun h264BootstrapSpropIsInjectedBeforeAnythingIsSniffed() {
        // Something must be injected on the first connect or the stream can never
        // bootstrap: media3 rejects the DESCRIBE, never sends PLAY, no RTP flows, and
        // the sniffer has nothing to read.
        val streamKey = "rtsp://test-host/h264_uncached"
        SpropCache.map.remove(streamKey)
        val lines = listOf("a=rtpmap:96 H264/90000", "a=fmtp:96 packetization-mode=1")
        val out = RtspInterceptionInputStream.injectH264SpropIfNeeded(lines, "test", streamKey)
        val fmtp = out.single { it.startsWith("a=fmtp:96") }
        assertTrue("bootstrap sprop must be present", fmtp.contains("sprop-parameter-sets="))
        assertTrue("must keep the server's own params", fmtp.contains("packetization-mode=1"))
    }

    @Test
    fun sniffedH264ParametersOverrideTheBootstrapSet() {
        val streamKey = "rtsp://test-host/h264_override"
        SpropCache.map[streamKey] = mapOf("h264-sps" to "U05JRkY=", "h264-pps" to "UFBT")
        try {
            val lines = listOf("a=rtpmap:96 H264/90000", "a=fmtp:96 packetization-mode=1")
            val fmtp = RtspInterceptionInputStream
                .injectH264SpropIfNeeded(lines, "test", streamKey)
                .single { it.startsWith("a=fmtp:96") }
            assertTrue(fmtp.contains("sprop-parameter-sets=U05JRkY=,UFBT"))
        } finally {
            SpropCache.map.remove(streamKey)
        }
    }

    @Test
    fun controlBecomesAbsoluteWhenTheStreamUrlHasAQuery() {
        // media3 would otherwise build /back_garden/trackID=0?video=h264, which go2rtc
        // rejects with SETUP 400; it accepts /back_garden?video=h264/trackID=0.
        val base = "rtsp://host:8554/back_garden?video=h264"
        val out = RtspInterceptionInputStream.absolutizeControlForQueryUrls(
            listOf("a=rtpmap:96 H264/90000", "a=control:trackID=0"), base
        )
        assertEquals("a=control:$base/trackID=0", out.last())
    }

    @Test
    fun controlIsLeftAloneWithoutAQueryOrWhenAlreadyAbsolute() {
        val plain = listOf("a=control:trackID=0")
        assertEquals(plain, RtspInterceptionInputStream
            .absolutizeControlForQueryUrls(plain, "rtsp://host:8554/back_garden"))

        val absolute = listOf("a=control:rtsp://host:8554/other/trackID=9")
        assertEquals(absolute, RtspInterceptionInputStream
            .absolutizeControlForQueryUrls(absolute, "rtsp://host:8554/x?video=h264"))

        val wildcard = listOf("a=control:*")
        assertEquals(wildcard, RtspInterceptionInputStream
            .absolutizeControlForQueryUrls(wildcard, "rtsp://host:8554/x?video=h264"))
    }

    @Test
    fun h264SniffingReadsSpsAndPpsFromInterleavedRtp() {
        val streamKey = "rtsp://test-host/h264_sniff"
        SpropCache.map.remove(streamKey)

        // go2rtc's transcode SDP: H264, packetization-mode only, no parameter sets.
        val sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1\r\na=control:trackID=0\r\n"
        val sdpBytes = sdp.toByteArray(Charsets.UTF_8)
        val header = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\n" +
            "Content-Length: ${sdpBytes.size}\r\n\r\n"

        fun interleaved(nal: ByteArray): ByteArray {
            val rtp = ByteArray(12).also { it[0] = 0x80.toByte() }
            val payload = rtp + nal
            return byteArrayOf(0x24, 0x00, ((payload.size shr 8) and 0xFF).toByte(),
                (payload.size and 0xFF).toByte()) + payload
        }
        // H264 NAL header is one byte; type is the low 5 bits. 7=SPS, 8=PPS.
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x29, 0x11, 0x22)
        val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x38, 0x80.toByte())

        val wire = header.toByteArray(Charsets.UTF_8) + sdpBytes + interleaved(sps) + interleaved(pps)
        val input = RtspInterceptionInputStream(java.io.ByteArrayInputStream(wire), "test", streamKey)
        val buf = ByteArray(1024)
        while (input.read(buf, 0, buf.size) > 0) { /* drain */ }

        val cached = SpropCache.map[streamKey]
        assertNotNull("H264 sniffer should cache SPS and PPS", cached)
        val b64 = java.util.Base64.getEncoder()
        assertEquals(b64.encodeToString(sps), cached!!["h264-sps"])
        assertEquals(b64.encodeToString(pps), cached["h264-pps"])
        SpropCache.map.remove(streamKey)
    }
}
