package com.zektopic.frigate.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// RTSP connection test
// ---------------------------------------------------------------------------

data class RtspTestResult(val reachable: Boolean, val message: String)

/**
 * Minimal RTSP probe: TCP connect + DESCRIBE, report reachability and the
 * advertised video codec. Credentials are left to the player (a 401 still
 * proves the camera is alive).
 */
suspend fun testRtspConnection(url: String): RtspTestResult = withContext(Dispatchers.IO) {
    try {
        val uri = java.net.URI(url.trim())
        if (!uri.scheme.equals("rtsp", ignoreCase = true) || uri.host.isNullOrEmpty()) {
            return@withContext RtspTestResult(false, "Not a valid rtsp:// URL")
        }
        val port = if (uri.port > 0) uri.port else 554
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(uri.host, port), 5000)
            socket.soTimeout = 5000
            val requestUrl = buildString {
                append("rtsp://").append(uri.host)
                if (uri.port > 0) append(":").append(uri.port)
                append(uri.rawPath ?: "")
                if (!uri.rawQuery.isNullOrEmpty()) append("?").append(uri.rawQuery)
            }
            socket.getOutputStream().apply {
                write("DESCRIBE $requestUrl RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\nUser-Agent: FrigateAndroid\r\n\r\n".toByteArray())
                flush()
            }

            val input = socket.getInputStream()
            val response = StringBuilder()
            val buf = ByteArray(8192)
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline && response.length < 32_768) {
                val n = try { input.read(buf) } catch (e: java.net.SocketTimeoutException) { break }
                if (n <= 0) break
                response.append(String(buf, 0, n, Charsets.ISO_8859_1))
                if (response.contains("\r\n\r\n") && response.contains("a=rtpmap")) break
                if (response.contains("\r\n\r\n") && !response.startsWith("RTSP/1.0 200")) break
            }

            val text = response.toString()
            val statusLine = text.lineSequence().firstOrNull() ?: ""
            return@withContext when {
                statusLine.contains(" 200") -> {
                    val codecs = Regex("a=rtpmap:\\d+ ([A-Za-z0-9-]+)/")
                        .findAll(text).map { it.groupValues[1] }.distinct().joinToString(", ")
                    RtspTestResult(true, if (codecs.isNotEmpty()) "Camera reachable — codec: $codecs" else "Camera reachable")
                }
                statusLine.contains(" 401") ->
                    RtspTestResult(true, "Camera reachable — requires authentication (credentials in the URL will be used)")
                statusLine.isNotEmpty() ->
                    RtspTestResult(false, "Camera answered: ${statusLine.trim()}")
                else ->
                    RtspTestResult(false, "Connected, but no RTSP response (is this an RTSP server?)")
            }
        }
    } catch (e: Exception) {
        RtspTestResult(false, "Connection failed: ${e.message ?: e.javaClass.simpleName}")
    }
}
