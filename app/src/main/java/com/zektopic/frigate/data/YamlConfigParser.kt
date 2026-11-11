package com.zektopic.frigate.data

import com.zektopic.frigate.ai.MotionConfig
import com.zektopic.frigate.ai.RelPoint
import org.yaml.snakeyaml.Yaml

object YamlConfigParser {

    /** The document root as a map, or null when the text is not a YAML mapping. */
    private fun loadRoot(yamlString: String): Map<*, *>? = try {
        Yaml().load<Any>(yamlString) as? Map<*, *>
    } catch (e: Exception) {
        null
    }

    /**
     * `android.max_concurrent_streams`: how many cameras this device ingests at once,
     * overriding the automatic [com.zektopic.frigate.media.DevicePerformance] budget.
     * App-specific (Frigate has no such key). Null when absent or not a positive number.
     */
    fun parseMaxConcurrentStreams(yamlString: String): Int? {
        val android = loadRoot(yamlString)?.get("android") as? Map<*, *> ?: return null
        return (android["max_concurrent_streams"] as? Number)?.toInt()?.takeIf { it > 0 }
    }

    /**
     * `low_resource.enabled`, the fork's "potato PC" switch. Here it pins the device
     * budget to its cheapest tier regardless of what the hardware probe decided.
     */
    fun parseLowResource(yamlString: String): Boolean {
        val section = loadRoot(yamlString)?.get("low_resource") as? Map<*, *> ?: return false
        return asBool(section["enabled"], false)
    }

    /**
     * Parses Frigate YAML configuration into a list of CameraConfigEntity.
     *
     * @throws org.yaml.snakeyaml.error.YAMLException when the text is not valid YAML,
     *   so editors can show the parser's message.
     */
    fun parseConfig(yamlString: String): List<CameraConfigEntity> {
        val parsed = Yaml().load<Any>(yamlString) as? Map<*, *> ?: return emptyList()

        // Parse global defaults from root level
        val globalDetectMap = parsed["detect"] as? Map<*, *>
        val globalWidth = (globalDetectMap?.get("width") as? Number)?.toInt() ?: 640
        val globalHeight = (globalDetectMap?.get("height") as? Number)?.toInt() ?: 360
        val globalFps = (globalDetectMap?.get("fps") as? Number)?.toInt() ?: 5

        val globalRecordMap = parsed["record"] as? Map<*, *>
        val globalRecordRetainMap = globalRecordMap?.get("retain") as? Map<*, *>
        val globalRecordRetentionDays = (globalRecordRetainMap?.get("days") as? Number)?.toFloat() ?: 3.0f

        val camerasMap = parsed["cameras"] as? Map<*, *> ?: return emptyList()

        val cameraConfigs = mutableListOf<CameraConfigEntity>()

        for ((key, value) in camerasMap) {
            val cameraId = key.toString()
            val camMap = value as? Map<*, *> ?: continue

            // Standardize format: front_camera -> Front Camera
            val defaultName = cameraId.replace("_", " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
            val name = (camMap["friendly_name"] ?: camMap["name"])?.toString() ?: defaultName

            // Extract RTSP urls from ffmpeg configuration, honouring Frigate's `roles`.
            // Previously this took inputs[0] unconditionally and ignored roles entirely,
            // so a configured low-resolution detect substream was never used and every
            // camera decoded its full-resolution feed just to produce a detect frame.
            val ffmpegMap = camMap["ffmpeg"] as? Map<*, *>
            val inputs = ffmpegMap?.get("inputs") as? List<*>

            fun pathOf(input: Any?): String =
                (input as? Map<*, *>)?.get("path")?.toString().orEmpty()

            fun rolesOf(input: Any?): List<String> =
                ((input as? Map<*, *>)?.get("roles") as? List<*>)
                    ?.mapNotNull { it?.toString()?.lowercase() }
                    ?: emptyList()

            val inputList = inputs.orEmpty()
            // `record` wins for the primary URL, falling back to the first input so
            // single-input configs (the common case) behave exactly as before.
            val rtspUrl = inputList.firstOrNull { "record" in rolesOf(it) }?.let { pathOf(it) }
                ?.takeIf { it.isNotBlank() }
                ?: pathOf(inputList.firstOrNull())

            // Only treat a detect input as a substream when it is genuinely a different
            // feed; an input tagged `[detect, record]` is one stream, not two.
            val detectRtspUrl = inputList.firstOrNull { "detect" in rolesOf(it) }
                ?.let { pathOf(it) }
                ?.takeIf { it.isNotBlank() && it != rtspUrl }
                .orEmpty()

            // Extract detect resolution & fps, falling back to global defaults
            val detectMap = camMap["detect"] as? Map<*, *>
            val detectWidth = (detectMap?.get("width") as? Number)?.toInt() ?: globalWidth
            val detectHeight = (detectMap?.get("height") as? Number)?.toInt() ?: globalHeight
            val fps = (detectMap?.get("fps") as? Number)?.toInt() ?: globalFps

            // Extract recording retention days, falling back to global default
            val recordMap = camMap["record"] as? Map<*, *>
            val recordRetainMap = recordMap?.get("retain") as? Map<*, *>
            val recordingRetentionDays = (recordRetainMap?.get("days") as? Number)?.toFloat() ?: globalRecordRetentionDays

            cameraConfigs.add(
                CameraConfigEntity(
                    id = cameraId,
                    name = name,
                    rtspUrl = rtspUrl,
                    detectRtspUrl = detectRtspUrl,
                    isEnabled = asBool(camMap["enabled"], true),
                    // A zero or negative size reaches glViewport and Bitmap.createBitmap
                    // on the frame thread; refuse it here instead.
                    detectWidth = detectWidth.coerceIn(MIN_DETECT_SIDE, MAX_DETECT_SIDE),
                    detectHeight = detectHeight.coerceIn(MIN_DETECT_SIDE, MAX_DETECT_SIDE),
                    fps = fps.coerceIn(1, MAX_FPS),
                    recordingRetentionDays = recordingRetentionDays
                )
            )
        }

        return cameraConfigs
    }

    /**
     * Per-camera motion settings, zones and review rules, each merged over the
     * top-level block of the same name the way Frigate merges globals into cameras.
     */
    fun parseRuntimeConfigs(yamlString: String): Map<String, CameraRuntimeConfig> {
        val root = loadRoot(yamlString) ?: return emptyMap()
        val cameras = root["cameras"] as? Map<*, *> ?: return emptyMap()

        val globalDetect = root["detect"] as? Map<*, *>
        val globalWidth = (globalDetect?.get("width") as? Number)?.toInt() ?: 640
        val globalHeight = (globalDetect?.get("height") as? Number)?.toInt() ?: 360
        val globalMotion = root["motion"] as? Map<*, *>
        val globalReview = root["review"] as? Map<*, *>

        val result = LinkedHashMap<String, CameraRuntimeConfig>()
        for ((key, value) in cameras) {
            val cam = value as? Map<*, *> ?: continue
            val detect = cam["detect"] as? Map<*, *>
            val width = (detect?.get("width") as? Number)?.toInt() ?: globalWidth
            val height = (detect?.get("height") as? Number)?.toInt() ?: globalHeight

            val zones = (cam["zones"] as? Map<*, *>).orEmpty().mapNotNull { (zoneName, zoneValue) ->
                val zone = zoneValue as? Map<*, *> ?: return@mapNotNull null
                if (!asBool(zone["enabled"], true)) return@mapNotNull null
                val polygon = parseCoordinates(zone["coordinates"], width, height)
                if (polygon.size < 3) null else ZoneConfig(zoneName.toString(), polygon)
            }

            result[key.toString()] = CameraRuntimeConfig(
                motion = parseMotion(globalMotion, cam["motion"] as? Map<*, *>, width, height),
                zones = zones,
                review = parseReview(globalReview, cam["review"] as? Map<*, *>)
            )
        }
        return result
    }

    private fun parseMotion(global: Map<*, *>?, camera: Map<*, *>?, width: Int, height: Int): MotionConfig {
        fun value(name: String): Any? = camera?.get(name) ?: global?.get(name)
        val defaults = MotionConfig()
        // A camera-level mask replaces the global one, as any other key does.
        val maskSource = if (camera?.containsKey("mask") == true) camera["mask"] else global?.get("mask")
        return MotionConfig(
            enabled = asBool(value("enabled"), defaults.enabled),
            threshold = ((value("threshold") as? Number)?.toInt() ?: defaults.threshold).coerceIn(1, 255),
            lightningThreshold = ((value("lightning_threshold") as? Number)?.toFloat()
                ?: defaults.lightningThreshold).coerceIn(0.3f, 1.0f),
            skipMotionThreshold = (value("skip_motion_threshold") as? Number)?.toFloat()?.coerceIn(0f, 1f),
            improveContrast = asBool(value("improve_contrast"), defaults.improveContrast),
            contourArea = ((value("contour_area") as? Number)?.toInt() ?: defaults.contourArea).coerceAtLeast(0),
            frameAlpha = ((value("frame_alpha") as? Number)?.toFloat() ?: defaults.frameAlpha).coerceIn(0.001f, 1f),
            frameHeight = ((value("frame_height") as? Number)?.toInt() ?: defaults.frameHeight).coerceIn(32, 480),
            masks = parseMasks(maskSource, width, height)
        )
    }

    /**
     * Frigate has stored masks three ways over time: one coordinate string, a list of
     * them, and (current) a map of named masks each with `coordinates` and `enabled`.
     */
    private fun parseMasks(raw: Any?, width: Int, height: Int): List<List<RelPoint>> {
        val polygons = when (raw) {
            null -> emptyList()
            is Map<*, *> -> raw.values.mapNotNull { entry ->
                val mask = entry as? Map<*, *> ?: return@mapNotNull null
                if (!asBool(mask["enabled"], true)) null
                else parseCoordinates(mask["coordinates"], width, height)
            }
            is List<*> ->
                // A list of numbers is one polygon; a list of strings is one per entry.
                if (raw.all { it is Number }) listOf(parseCoordinates(raw, width, height))
                else raw.map { parseCoordinates(it, width, height) }
            else -> listOf(parseCoordinates(raw, width, height))
        }
        return polygons.filter { it.size >= 3 }
    }

    /**
     * `x1,y1,x2,y2,...`. Values up to 1 are relative to the frame (Frigate 0.14+);
     * anything larger is the older absolute form in detect-resolution pixels.
     */
    internal fun parseCoordinates(raw: Any?, width: Int, height: Int): List<RelPoint> {
        val numbers: List<Float> = when (raw) {
            null -> emptyList()
            is List<*> -> raw.flatMap { item ->
                when (item) {
                    is Number -> listOf(item.toFloat())
                    else -> item.toString().split(',').mapNotNull { it.trim().toFloatOrNull() }
                }
            }
            else -> raw.toString().split(',').mapNotNull { it.trim().toFloatOrNull() }
        }
        if (numbers.size < 6 || numbers.size % 2 != 0) return emptyList()
        val absolute = numbers.any { it > 1f }
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()
        return numbers.chunked(2).map { (x, y) ->
            if (absolute) RelPoint((x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f))
            else RelPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
        }
    }

    private fun parseReview(global: Map<*, *>?, camera: Map<*, *>?): ReviewRules {
        fun section(name: String, key: String): Any? =
            ((camera?.get(name) as? Map<*, *>)?.get(key)) ?: ((global?.get(name) as? Map<*, *>)?.get(key))

        fun strings(value: Any?): List<String>? = when (value) {
            null -> null
            is List<*> -> value.mapNotNull { it?.toString() }
            else -> listOf(value.toString())
        }

        val defaults = ReviewRules()
        return ReviewRules(
            alertLabels = strings(section("alerts", "labels")) ?: defaults.alertLabels,
            alertRequiredZones = strings(section("alerts", "required_zones")).orEmpty(),
            detectionLabels = strings(section("detections", "labels")),
            detectionRequiredZones = strings(section("detections", "required_zones")).orEmpty()
        )
    }

    private fun asBool(value: Any?, default: Boolean): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> default
    }

    private const val MIN_DETECT_SIDE = 16
    private const val MAX_DETECT_SIDE = 4096
    private const val MAX_FPS = 30
}
