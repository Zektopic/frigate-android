package com.zektopic.frigate.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.util.Log

object HevcDecoderChecker {
    private const val TAG = "HevcDecoderChecker"
    private const val HEVC_MIME = "video/hevc"

    private val hevcUrlIndicators = listOf(".hevc", ".h265", ".mkv")

    private var cachedResult: Boolean? = null
    private var cachedDecoderInfo: String? = null

    fun isHevcContent(url: String): Boolean {
        val lower = url.lowercase()
        return hevcUrlIndicators.any { lower.contains(it) }
    }

    fun isHevcDecodingSupported(): Boolean {
        cachedResult?.let { return it }
        val result = queryDecoders()
        cachedResult = result
        return result
    }

    fun getHevcDecoderInfo(): String {
        cachedDecoderInfo?.let { return it }
        queryDecoders()
        return cachedDecoderInfo ?: "No HEVC decoder information available"
    }

    fun logHevcCapabilities() {
        val supported = isHevcDecodingSupported()
        Log.i(TAG, "HEVC/H.265 decoding supported: $supported")
        Log.i(TAG, "Decoder details: ${getHevcDecoderInfo()}")
    }

    private fun queryDecoders(): Boolean {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val hevcDecoders = mutableListOf<String>()
        var anySupported = false

        for (codecInfo in codecList.codecInfos) {
            if (codecInfo.isEncoder) continue
            val supportedTypes = codecInfo.supportedTypes
            if (supportedTypes.contains(HEVC_MIME)) {
                anySupported = true
                val name = codecInfo.name
                val isHardware = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    codecInfo.isHardwareAccelerated
                } else {
                    val lowerName = name.lowercase()
                    !(lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.") || lowerName.contains(".sw.") || lowerName.endsWith(".sw"))
                }
                val isVendor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    codecInfo.isVendor
                } else {
                    val lowerName = name.lowercase()
                    !(lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android."))
                }
                val isSoftwareOnly = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    codecInfo.isSoftwareOnly
                } else {
                    val lowerName = name.lowercase()
                    lowerName.startsWith("omx.google.") || lowerName.startsWith("c2.android.") || lowerName.contains(".sw.") || lowerName.endsWith(".sw")
                }
                val entry = buildString {
                    append("  $name")
                    append(" [hw_accel=$isHardware")
                    append(", vendor=$isVendor")
                    append(", sw_only=$isSoftwareOnly]")
                    try {
                        val capabilities = codecInfo.getCapabilitiesForType(HEVC_MIME)
                        val maxInstances = capabilities.maxSupportedInstances
                        append(", max_instances=$maxInstances")
                    } catch (_: Exception) { }
                }
                hevcDecoders.add(entry)
                Log.i(TAG, "Found HEVC decoder: $entry")
            }
        }

        if (!anySupported) {
            Log.w(TAG, "No HEVC/H.265 decoders found on this device.")
            cachedDecoderInfo = "No HEVC decoders available"
        } else {
            cachedDecoderInfo = "HEVC decoders (${hevcDecoders.size}):\n${hevcDecoders.joinToString("\n")}"
        }
        return anySupported
    }
}
