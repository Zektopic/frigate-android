package com.zektopic.frigate.data

import org.yaml.snakeyaml.Yaml

object YamlConfigParser {
    
    /**
     * Parses Frigate YAML configuration into a list of CameraConfigEntity
     */
    fun parseConfig(yamlString: String): List<CameraConfigEntity> {
        val yaml = Yaml()
        val parsed = yaml.load<Map<String, Any>>(yamlString) ?: return emptyList()
        val camerasMap = parsed["cameras"] as? Map<*, *> ?: return emptyList()
        
        val cameraConfigs = mutableListOf<CameraConfigEntity>()
        
        for ((key, value) in camerasMap) {
            val cameraId = key.toString()
            val camMap = value as? Map<*, *> ?: continue
            
            // Standardize format: front_camera -> Front Camera
            val defaultName = cameraId.replace("_", " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
            val name = camMap["name"]?.toString() ?: defaultName
            
            // Extract RTSP url from ffmpeg configuration
            val ffmpegMap = camMap["ffmpeg"] as? Map<*, *>
            val inputs = ffmpegMap?.get("inputs") as? List<*>
            var rtspUrl = ""
            if (inputs != null && inputs.isNotEmpty()) {
                val firstInput = inputs[0] as? Map<*, *>
                rtspUrl = firstInput?.get("path")?.toString() ?: ""
            }
            
            // Extract detect resolution & fps
            val detectMap = camMap["detect"] as? Map<*, *>
            val detectWidth = (detectMap?.get("width") as? Number)?.toInt() ?: 640
            val detectHeight = (detectMap?.get("height") as? Number)?.toInt() ?: 360
            val fps = (detectMap?.get("fps") as? Number)?.toInt() ?: 5
            
            // Extract motion threshold (Frigate maps this to double/percent)
            val motionMap = camMap["motion"] as? Map<*, *>
            val motionThreshold = (motionMap?.get("threshold") as? Number)?.toDouble()?.div(100.0) ?: 0.02
            
            // Extract enabled flag (default true)
            val isEnabled = (camMap["enabled"] as? Boolean) ?: true

            cameraConfigs.add(
                CameraConfigEntity(
                    id = cameraId,
                    name = name,
                    rtspUrl = rtspUrl,
                    isEnabled = isEnabled,
                    detectWidth = detectWidth,
                    detectHeight = detectHeight,
                    fps = fps,
                    motionThreshold = motionThreshold
                )
            )
        }
        
        return cameraConfigs
    }
}
