package com.zektopic.frigate.media

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * go2rtc 1.9 answers a SETUP for `trackID=1` with `interleaved=2-3` whatever the
 * client asked for, while Media3 keeps listening on the pair it requested. These
 * tests replay that exchange through [RtspInterceptionInputStream] and check what
 * Media3 would read.
 */
class RtspChannelRemapTest {

    private val sniffKey = "rtsp://test/remap-sniff"

    @After
    fun clearCaches() {
        SpropCache.map.remove(sniffKey)
        StreamProfileCache.map.remove(sniffKey)
    }

    // go2rtc's SDP for a camera with audio: the audio track comes first.
    private val audioFirstSdp = listOf(
        "v=0",
        "o=- 1 1 IN IP4 0.0.0.0",
        "s=go2rtc/1.9.10",
        "c=IN IP4 0.0.0.0",
        "t=0 0",
        "m=audio 0 RTP/AVP 96",
        "a=rtpmap:96 OPUS/48000/2",
        "a=recvonly",
        "a=control:trackID=0",
        "m=video 0 RTP/AVP 97",
        "a=rtpmap:97 H265/90000",
        "a=recvonly",
        "a=control:trackID=1",
        ""
    ).joinToString("\r\n")

    private fun text(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun describe(cseq: Int) = text(
        "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Type: application/sdp\r\n" +
            "Content-Length: ${audioFirstSdp.length}\r\n\r\n$audioFirstSdp"
    )

    private fun setupRequest(cseq: Int, pair: String) =
        "SETUP rtsp://192.168.1.9:8554/front_camera/trackID=1 RTSP/1.0\r\nCSeq: $cseq\r\n" +
            "Transport: RTP/AVP/TCP;unicast;interleaved=$pair\r\n\r\n"

    private fun setupReply(cseq: Int, pair: String) = text(
        "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n" +
            "Transport: RTP/AVP/TCP;unicast;interleaved=$pair\r\nSession: 12345678\r\n\r\n"
    )

    private fun ok(cseq: Int) = text("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nSession: 12345678\r\n\r\n")

    private fun frame(channel: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0x24, channel.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()) + payload

    /** An RTP packet (12-byte header, version 2) carrying [nal] as a single NAL unit. */
    private fun rtp(seq: Int, nal: ByteArray): ByteArray =
        byteArrayOf(0x80.toByte(), 97, (seq shr 8).toByte(), seq.toByte(), 0, 0, 0, 1, 0, 0, 0, 2) + nal

    private fun payload(seed: Int, size: Int) = ByteArray(size) { (seed * 31 + it).toByte() }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Reads everything, [chunk] bytes at a time (1 exercises read()). */
    private fun drain(stream: RtspInterceptionInputStream, chunk: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (chunk == 1) {
            while (true) {
                val b = stream.read()
                if (b == -1) break
                out.write(b)
            }
        } else {
            val buf = ByteArray(chunk)
            while (true) {
                val n = stream.read(buf, 0, chunk)
                if (n == -1) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** Splits a server byte stream into its RTSP messages and interleaved frames. */
    private sealed class Item {
        data class Message(val text: String) : Item()
        data class Frame(val channel: Int, val payload: List<Byte>) : Item()
    }

    private fun parse(bytes: ByteArray): List<Item> {
        val items = mutableListOf<Item>()
        var i = 0
        while (i < bytes.size) {
            if (bytes[i] == 0x24.toByte()) {
                val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                items += Item.Frame(bytes[i + 1].toInt() and 0xFF, bytes.copyOfRange(i + 4, i + 4 + len).toList())
                i += 4 + len
            } else {
                val all = String(bytes, i, bytes.size - i, Charsets.US_ASCII)
                val headerEnd = all.indexOf("\r\n\r\n") + 4
                val length = Regex("Content-Length: (\\d+)").find(all.substring(0, headerEnd))
                    ?.groupValues?.get(1)?.toInt() ?: 0
                items += Item.Message(all.substring(0, headerEnd + length))
                i += headerEnd + length
            }
        }
        return items
    }

    private fun audioFirstSession(): ByteArray = concat(
        describe(2),
        setupReply(3, "2-3"),
        ok(4), // PLAY
        frame(2, payload(1, 1400)),
        frame(3, payload(2, 52)), // RTCP
        frame(2, payload(3, 900)),
        ok(5), // keep-alive reply between frames
        frame(2, payload(4, 1400)),
        frame(3, payload(5, 28))
    )

    private fun streamWithSetup(server: ByteArray, requested: String, key: String = ""): RtspInterceptionInputStream {
        val map = InterleavedChannelMap()
        map.onRequest(setupRequest(3, requested))
        return RtspInterceptionInputStream(ByteArrayInputStream(server), "test", key, map)
    }

    @Test
    fun framesSentOnTheServersChannelsArriveOnTheRequestedOnes() {
        for (chunk in listOf(1, 3, 7, 4096)) {
            val items = parse(drain(streamWithSetup(audioFirstSession(), "0-1"), chunk))
            val frames = items.filterIsInstance<Item.Frame>()
            assertEquals("chunk=$chunk", listOf(0, 1, 0, 0, 1), frames.map { it.channel })
            assertEquals("chunk=$chunk: payloads untouched",
                listOf(payload(1, 1400), payload(2, 52), payload(3, 900), payload(4, 1400), payload(5, 28))
                    .map { it.toList() },
                frames.map { it.payload })
        }
    }

    @Test
    fun setupReplyIsRewrittenToTheRequestedPairAndOtherMessagesPassThrough() {
        val messages = parse(drain(streamWithSetup(audioFirstSession(), "0-1"), 64))
            .filterIsInstance<Item.Message>().map { it.text }
        assertEquals(4, messages.size)
        assertTrue(messages[0].contains("m=video")) // DESCRIBE body survives the SDP rewrite
        assertFalse(messages[0].contains("m=audio"))
        assertTrue(messages[1].contains("interleaved=0-1"))
        assertFalse(messages[1].contains("interleaved=2-3"))
        assertEquals(String(ok(4), Charsets.US_ASCII), messages[2])
        assertEquals(String(ok(5), Charsets.US_ASCII), messages[3])
    }

    @Test
    fun nothingIsRewrittenWhenTheServerHonoursTheRequest() {
        val server = concat(describe(2), setupReply(3, "0-1"), ok(4), frame(0, payload(1, 700)), frame(1, payload(2, 40)))
        val map = InterleavedChannelMap()
        map.onRequest(setupRequest(3, "0-1"))
        val out = drain(RtspInterceptionInputStream(ByteArrayInputStream(server), "test", "", map), 5)
        assertFalse(map.remapping)
        assertEquals(listOf(0, 1), parse(out).filterIsInstance<Item.Frame>().map { it.channel })
        assertTrue(parse(out).filterIsInstance<Item.Message>()[1].text.contains("interleaved=0-1"))
    }

    @Test
    fun replyIsMatchedToItsSetupByCseqNotByOrder() {
        val map = InterleavedChannelMap()
        map.onRequest(setupRequest(3, "0-1"))
        map.onRequest(setupRequest(4, "2-3"))
        // CSeq 3 failed (no Transport); CSeq 4 was moved to 6-7.
        map.onResponse("RTSP/1.0 404 Not Found\r\nCSeq: 3\r\n\r\n")
        map.onResponse("RTSP/1.0 200 OK\r\nCSeq: 4\r\nTransport: RTP/AVP/TCP;unicast;interleaved=6-7\r\n\r\n")
        assertEquals(2, map.map(6))
        assertEquals(3, map.map(7))
        assertEquals("unmapped channels are unchanged", 0, map.map(0))
        assertEquals("the first SETUP is the video", 0, map.videoRtpChannel)
    }

    @Test
    fun parameterSetsAreSniffedFromTheRemappedVideoChannel() {
        val sps = Base64.getDecoder().decode("QgEBAWAAAAMAAAMAAAMAAAMAeKAFAgHhaLSuwS7moKDAwBA=")
        val vps = byteArrayOf(0x40, 0x01, 0x0C, 0x01, 0x7F)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte(), 0x72)
        val server = concat(
            describe(2), setupReply(3, "2-3"), ok(4),
            frame(3, payload(9, 52)),
            frame(2, rtp(1, vps)), frame(2, rtp(2, sps)), frame(2, rtp(3, pps)),
            frame(2, payload(4, 600))
        )
        drain(streamWithSetup(server, "0-1", sniffKey), 11)
        val captured = SpropCache.map[sniffKey]
        assertNotNull("VPS/SPS/PPS on channel 2 should be captured once remapped to 0", captured)
        assertEquals(setOf("sprop-vps", "sprop-sps", "sprop-pps"), captured!!.keys)
        assertArrayEquals(sps, Base64.getDecoder().decode(captured["sprop-sps"]))
    }

    @Test
    fun interleavedStartReadsOnlyTheTransportHeader() {
        assertEquals(2, InterleavedChannelMap.interleavedStart("RTSP/1.0 200 OK\r\nTransport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n\r\n"))
        assertEquals(4, InterleavedChannelMap.interleavedStart("RTSP/1.0 200 OK\r\ntransport: RTP/AVP/TCP;interleaved=4\r\n\r\n"))
        assertNull(InterleavedChannelMap.interleavedStart("RTSP/1.0 200 OK\r\nCSeq: 2\r\n\r\n"))
    }
}
