package com.zektopic.frigate.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectUrlPolicyTest {

    private val configured = "rtsp://192.168.1.9:8554/back_garden"
    private val fallback = "rtsp://192.168.1.9:8554/back_garden?video=h264"

    /** Plays one reconnect: the URL that failed, and what the ingester remembers. */
    private class Ingester(var current: String, var attemptedFallback: Boolean = false, var lastLive: String? = null)

    private fun Ingester.fail(configured: String, fallback: String?): ReconnectUrlPolicy.Decision =
        ReconnectUrlPolicy.next(configured, current, fallback, lastLive, attemptedFallback).also {
            current = it.url
            attemptedFallback = it.attemptedFallback
        }

    @Test
    fun aConfiguredUrlThatNeverWorkedTriesTheFallback() {
        val ing = Ingester(configured)
        val d = ing.fail(configured, fallback)
        assertEquals(fallback, d.url)
        assertTrue(d.attemptedFallback)
    }

    @Test
    fun aFallbackThatWentLiveIsWhereADroppedStreamReconnects() {
        // back_garden: native HEVC never plays on the tablet, the transcode does.
        val ing = Ingester(configured)
        ing.fail(configured, fallback)
        ing.lastLive = ing.current // the fallback went live
        repeat(3) { assertEquals(fallback, ing.fail(configured, fallback).url) }
    }

    @Test
    fun aFallbackThatNeverWentLiveRevertsAndStays() {
        // front_camera: go2rtc has no H.264 transcode for it, so ?video=h264 is a 404.
        val front = "rtsp://192.168.1.9:8554/front_camera"
        val ing = Ingester(front)
        assertEquals("$front?video=h264", ing.fail(front, "$front?video=h264").url)
        assertEquals(front, ing.fail(front, "$front?video=h264").url)
        repeat(3) { assertEquals(front, ing.fail(front, "$front?video=h264").url) }
    }

    @Test
    fun aConfiguredUrlThatWentLiveIsRetriedWithoutGuessing() {
        val ing = Ingester(configured, lastLive = configured)
        val d = ing.fail(configured, fallback)
        assertEquals(configured, d.url)
        assertNull("nothing to announce when reconnecting to the same URL", d.why)
    }

    @Test
    fun noFallbackMeansTheConfiguredUrlIsRetried() {
        val ing = Ingester(configured)
        assertEquals(configured, ing.fail(configured, null).url)
    }
}
