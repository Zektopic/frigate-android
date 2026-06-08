package com.zektopic.frigate.mqtt

import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

/**
 * Lightweight MQTT 3.1.1 client for Frigate Android — no external dependency.
 *
 * Publishes detection events to MQTT for Home Assistant / external automation
 * integration, matching Frigate's MQTT event topics:
 *   frigate/events        → JSON detection payloads
 *   frigate/available     → LWT (Last Will and Testament) status
 *   frigate/<camera>/<label>/state → individual object states
 */
class MqttClient(
    private val host: String = "localhost",
    private val port: Int = 1883,
    private val clientId: String = "frigate-android-${UUID.randomUUID().toString().take(8)}",
    private val username: String? = null,
    private val password: String? = null,
    private val useTls: Boolean = false,
    private val topicPrefix: String = "frigate"
) {
    private val tag = "MqttClient"

    // Connection state
    private var socket: Socket? = null
    private var outputStream: OutputStreamWriter? = null
    private var inputStream: BufferedReader? = null
    private val isConnected = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    // Coroutine scope for background keepalive
    private val mqttScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepAliveJob: Job? = null
    private var reconnectJob: Job? = null

    // Subscriptions
    private val subscriptions = ConcurrentHashMap<String, (String) -> Unit>()

    // Callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((String) -> Unit)? = null

    // MQTT fixed headers
    companion object {
        const val CONNECT = 0x10
        const val CONNACK = 0x20
        const val PUBLISH = 0x30
        const val SUBSCRIBE = 0x82
        const val SUBACK = 0x90
        const val PINGREQ = 0xC0
        const val PINGRESP = 0xD0
        const val DISCONNECT = 0xE0

        private const val PROTOCOL_LEVEL = 4 // MQTT 3.1.1
        private const val KEEPALIVE_SECS = 30
    }

    fun connect() {
        if (isConnected.get()) return
        isRunning.set(true)
        mqttScope.launch {
            connectInternal()
        }
    }

    private fun connectInternal() {
        try {
            Log.i(tag, "Connecting to MQTT broker at $host:$port (TLS=$useTls)...")

            val rawSocket = if (useTls) {
                createTlsSocket()
            } else {
                Socket(host, port)
            }

            rawSocket.soTimeout = 10000
            socket = rawSocket
            outputStream = OutputStreamWriter(rawSocket.getOutputStream(), "ISO-8859-1")
            inputStream = BufferedReader(InputStreamReader(rawSocket.getInputStream(), "ISO-8859-1"))

            // Send CONNECT packet
            sendConnect()

            // Read CONNACK
            val connack = readPacket()
            if (connack != null && connack[0] == CONNACK.toByte() && connack.size >= 4) {
                val returnCode = connack[3].toInt() and 0xFF
                if (returnCode == 0) {
                    isConnected.set(true)
                    Log.i(tag, "MQTT connected successfully (client: $clientId)")
                    onConnected?.invoke()

                    // Start keepalive pings
                    startKeepalive()

                    // Resubscribe to existing topics
                    for (topic in subscriptions.keys) {
                        sendSubscribe(topic)
                    }
                } else {
                    Log.e(tag, "MQTT CONNACK rejected: code $returnCode")
                    onDisconnected?.invoke("CONNACK rejected: $returnCode")
                }
            } else {
                Log.e(tag, "MQTT: invalid CONNACK response")
                onDisconnected?.invoke("Invalid CONNACK")
            }
        } catch (e: Exception) {
            Log.e(tag, "MQTT connection failed: ${e.message}")
            isConnected.set(false)
            onDisconnected?.invoke(e.message ?: "Unknown error")
            scheduleReconnect()
        }
    }

    /**
     * Publish a JSON detection event matching Frigate's MQTT schema.
     */
    fun publishEvent(
        cameraId: String,
        label: String,
        confidence: Float,
        zone: String? = null,
        snapshotPath: String? = null
    ) {
        if (!isConnected.get()) return

        val timestamp = System.currentTimeMillis() / 1000
        val payload = buildString {
            append("{")
            append("\"type\":\"new\",")
            append("\"before\":{},")
            append("\"after\":{")
            append("\"id\":\"${UUID.randomUUID().toString().take(8)}\",")
            append("\"camera\":\"$cameraId\",")
            append("\"label\":\"$label\",")
            append("\"sub_label\":\"\",")
            append("\"top_score\":$confidence,")
            append("\"score\":$confidence,")
            append("\"zone\":\"${zone ?: "main"}\",")
            append("\"snapshot_path\":\"${snapshotPath ?: ""}\",")
            append("\"start_time\":$timestamp,")
            append("\"end_time\":$timestamp,")
            append("\"has_snapshot\":${snapshotPath != null},")
            append("\"has_clip\":false")
            append("}")
            append("}")
        }

        publish("$topicPrefix/events", payload)

        // Also publish to camera-specific state topic
        publish("$topicPrefix/$cameraId/$label/state", "ON")
    }

    /**
     * Publish raw message to a topic
     */
    fun publish(topic: String, message: String, retain: Boolean = false) {
        if (!isConnected.get()) return
        try {
            val topicBytes = topic.toByteArray(Charsets.UTF_8)
            val payloadBytes = message.toByteArray(Charsets.UTF_8)

            val remainingLength = 2 + topicBytes.size + payloadBytes.size
            val header = byteArrayOf((PUBLISH or (if (retain) 1 else 0)).toByte())
            val remainingBytes = encodeRemainingLength(remainingLength)

            outputStream?.let { os ->
                os.write(String(header, Charsets.ISO_8859_1))
                os.write(String(remainingBytes, Charsets.ISO_8859_1))
                // Topic length (2 bytes) + topic + payload
                os.write(topicBytes.size shr 8)
                os.write(topicBytes.size and 0xFF)
                os.write(String(topicBytes, Charsets.ISO_8859_1))
                os.write(String(payloadBytes, Charsets.ISO_8859_1))
                os.flush()
            }

            Log.d(tag, "Published to $topic: ${message.take(80)}...")
        } catch (e: Exception) {
            Log.e(tag, "Publish failed: ${e.message}")
            isConnected.set(false)
            scheduleReconnect()
        }
    }

    /**
     * Subscribe to a topic with callback
     */
    fun subscribe(topic: String, callback: (String) -> Unit) {
        subscriptions[topic] = callback
        if (isConnected.get()) {
            sendSubscribe(topic)
        }
    }

    fun unsubscribe(topic: String) {
        subscriptions.remove(topic)
    }

    fun disconnect() {
        Log.i(tag, "Disconnecting MQTT...")
        isRunning.set(false)
        keepAliveJob?.cancel()
        reconnectJob?.cancel()
        mqttScope.cancel()

        try {
            if (isConnected.get()) {
                // Send DISCONNECT
                outputStream?.write(byteArrayOf(DISCONNECT.toByte(), 0).let {
                    String(it, Charsets.ISO_8859_1)
                })
                outputStream?.flush()
            }
        } catch (_: Exception) { }

        try { socket?.close() } catch (_: Exception) { }
        isConnected.set(false)
        Log.i(tag, "MQTT disconnected")
    }

    // ---------- Internal MQTT protocol implementation ----------

    private fun sendConnect() {
        val protocolName = "MQTT".toByteArray(Charsets.UTF_8)
        val flags = (if (username != null) 0x80 else 0) or
                (if (password != null) 0x40 else 0) or
                0x02 // Clean session

        val willTopic = "$topicPrefix/available".toByteArray(Charsets.UTF_8)
        val willPayload = "offline".toByteArray(Charsets.UTF_8)

        // Calculate remaining length
        var remainingLength = 2 + protocolName.size + 1 + 1 + 2 + 2 +
                clientId.toByteArray(Charsets.UTF_8).size

        if (username != null) remainingLength += 2 + username.toByteArray(Charsets.UTF_8).size
        if (password != null) remainingLength += 2 + password.toByteArray(Charsets.UTF_8).size

        val packet = buildPacket(CONNECT) {
            // Protocol name
            writeShort(protocolName.size)
            write(protocolName)
            // Protocol level
            write(PROTOCOL_LEVEL)
            // Connect flags
            write(flags)
            // Keepalive
            writeShort(KEEPALIVE_SECS)
            // Client ID
            writeString(clientId)
            // Will topic and message
            writeString("$topicPrefix/available")
            writeString("offline")
            // Credentials
            username?.let { writeString(it) }
            password?.let { writeString(it) }
        }

        sendRaw(packet)
        Log.d(tag, "CONNECT sent")
    }

    private fun sendSubscribe(topic: String) {
        val packetId = (Math.random() * 65535).toInt() + 1
        val topicBytes = topic.toByteArray(Charsets.UTF_8)
        val qos = 0

        val packet = buildPacket(SUBSCRIBE) {
            writeShort(packetId)
            writeShort(topicBytes.size)
            write(topicBytes)
            write(qos)
        }

        sendRaw(packet)
        Log.d(tag, "SUBSCRIBE sent for: $topic")
    }

    private fun startKeepalive() {
        keepAliveJob?.cancel()
        keepAliveJob = mqttScope.launch {
            while (isRunning.get() && isConnected.get()) {
                delay(KEEPALIVE_SECS * 1000L / 2) // Ping every half keepalive
                try {
                    sendRaw(byteArrayOf(PINGREQ.toByte(), 0))
                    Log.v(tag, "PINGREQ sent")
                } catch (e: Exception) {
                    Log.w(tag, "Keepalive failed: ${e.message}")
                    isConnected.set(false)
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!isRunning.get()) return
        reconnectJob?.cancel()
        reconnectJob = mqttScope.launch {
            Log.i(tag, "Reconnecting in 5s...")
            delay(5000)
            if (!isConnected.get() && isRunning.get()) {
                connectInternal()
            }
        }
    }

    private fun readPacket(): ByteArray? {
        return try {
            val firstByte = inputStream?.read() ?: return null
            if (firstByte < 0) return null

            val packet = mutableListOf<Byte>()
            packet.add(firstByte.toByte())

            var multiplier = 1
            var remainingLength = 0
            while (true) {
                val digit = inputStream?.read() ?: break
                packet.add(digit.toByte())
                remainingLength += (digit and 0x7F) * multiplier
                multiplier *= 128
                if (digit and 0x80 == 0) break
            }

            // Read the payload
            for (i in 0 until remainingLength) {
                val b = inputStream?.read() ?: break
                packet.add(b.toByte())
            }

            packet.toByteArray()
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: Exception) {
            Log.w(tag, "Read error: ${e.message}")
            null
        }
    }

    @Synchronized
    private fun sendRaw(data: ByteArray) {
        try {
            outputStream?.write(String(data, Charsets.ISO_8859_1))
            outputStream?.flush()
        } catch (e: Exception) {
            Log.e(tag, "Send error: ${e.message}")
            isConnected.set(false)
            scheduleReconnect()
        }
    }

    private fun buildPacket(type: Int, block: PacketBuilder.() -> Unit): ByteArray {
        val builder = PacketBuilder()
        builder.block()
        val payload = builder.toByteArray()
        val remaining = payload.size
        val header = mutableListOf(type.toByte())
        header.addAll(encodeRemainingLength(remaining).toList())
        return header.toByteArray() + payload
    }

    private fun encodeRemainingLength(length: Int): ByteArray {
        val bytes = mutableListOf<Byte>()
        var x = length
        do {
            var digit = x and 0x7F
            x = x shr 7
            if (x > 0) digit = digit or 0x80
            bytes.add(digit.toByte())
        } while (x > 0)
        return bytes.toByteArray()
    }

    private fun createTlsSocket(): Socket {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        val factory = sslContext.socketFactory
        return factory.createSocket(host, port)
    }

    inner class PacketBuilder {
        private val bytes = mutableListOf<Byte>()

        fun write(data: ByteArray) { bytes.addAll(data.toList()) }
        fun write(b: Int) { bytes.add(b.toByte()) }
        fun writeShort(value: Int) {
            bytes.add((value shr 8).toByte())
            bytes.add((value and 0xFF).toByte())
        }
        fun writeString(s: String) {
            val data = s.toByteArray(Charsets.UTF_8)
            writeShort(data.size)
            write(data)
        }
        fun toByteArray() = bytes.toByteArray()
    }
}
