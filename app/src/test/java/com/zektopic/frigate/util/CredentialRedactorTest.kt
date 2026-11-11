package com.zektopic.frigate.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedactorTest {

    @Test
    fun masksUserAndPasswordInAStreamUrl() {
        assertEquals(
            "rtsp://*:*@192.168.1.20:554/live/ch0",
            CredentialRedactor.redact("rtsp://admin:hunter2@192.168.1.20:554/live/ch0")
        )
    }

    @Test
    fun masksUsernamesTheOriginalPatternMissed() {
        // The cases from the fork's fix: dots, percent-escapes and an empty username
        assertEquals("rtsp://*:*@cam/s", CredentialRedactor.redact("rtsp://john.doe:secret@cam/s"))
        assertEquals("rtsp://*:*@cam/s", CredentialRedactor.redact("rtsp://user%40site:secret@cam/s"))
        assertEquals("rtsp://*:*@cam/s", CredentialRedactor.redact("rtsp://:secret@cam/s"))
    }

    @Test
    fun masksAPasswordContainingAnAtSign() {
        assertEquals("rtsp://*:*@cam/s", CredentialRedactor.redact("rtsp://admin:p@ss@cam/s"))
    }

    @Test
    fun masksHttpQueryCredentials() {
        assertEquals(
            "http://cam/snap.cgi?user=*&password=*",
            CredentialRedactor.redact("http://cam/snap.cgi?user=john.doe&password=secret")
        )
    }

    @Test
    fun leavesUrlsWithoutCredentialsAlone() {
        val url = "rtsp://192.168.1.9:8554/back_garden?video=h264"
        assertEquals(url, CredentialRedactor.redact(url))
    }

    @Test
    fun masksEveryUrlInAConfig() {
        val yaml = """
            cameras:
              a:
                ffmpeg:
                  inputs:
                    - path: rtsp://u1:p1@10.0.0.1/main
                    - path: rtsp://10.0.0.9:8554/sub
              b:
                ffmpeg:
                  inputs:
                    - path: rtsp://u2:p2@10.0.0.2/main
        """.trimIndent()
        val redacted = CredentialRedactor.redact(yaml)
        assertFalse(redacted.contains("p1"))
        assertFalse(redacted.contains("p2"))
        assertTrue(redacted.contains("rtsp://*:*@10.0.0.1/main"))
        assertTrue(redacted.contains("rtsp://10.0.0.9:8554/sub"))
    }

    @Test
    fun restorePutsCredentialsBackBehindUnchangedUrls() {
        val original = "a: rtsp://u1:p1@10.0.0.1/main\nb: rtsp://u2:p2@10.0.0.2/main\n"
        val edited = CredentialRedactor.redact(original).replace("b:", "renamed:")
        assertEquals(
            "a: rtsp://u1:p1@10.0.0.1/main\nrenamed: rtsp://u2:p2@10.0.0.2/main\n",
            CredentialRedactor.restore(edited, original)
        )
    }

    @Test
    fun restoreKeepsCredentialsTheClientTyped() {
        val original = "a: rtsp://u1:p1@10.0.0.1/main\n"
        val edited = "a: rtsp://new:creds@10.0.0.1/main\n"
        assertEquals(edited, CredentialRedactor.restore(edited, original))
    }

    @Test
    fun restoreLeavesTheMaskWhenTheUrlBehindItChanged() {
        // The host changed, so there is no stored secret that belongs to this URL.
        val original = "a: rtsp://u1:p1@10.0.0.1/main\n"
        val edited = "a: rtsp://*:*@10.0.0.77/main\n"
        val restored = CredentialRedactor.restore(edited, original)
        assertEquals(edited, restored)
        assertTrue(CredentialRedactor.containsMask(restored))
    }

    @Test
    fun restoreRefusesToGuessBetweenTwoSecretsBehindOneMask() {
        // Same host and path, different credentials: both redact to the same text.
        val original = "a: rtsp://u1:p1@cam/main\nb: rtsp://u2:p2@cam/main\n"
        val restored = CredentialRedactor.restore(CredentialRedactor.redact(original), original)
        assertTrue(CredentialRedactor.containsMask(restored))
    }

    @Test
    fun restoreIsANoOpWithoutMasks() {
        val text = "a: rtsp://10.0.0.1/main\n"
        assertEquals(text, CredentialRedactor.restore(text, "a: rtsp://u:p@10.0.0.1/main\n"))
    }
}
