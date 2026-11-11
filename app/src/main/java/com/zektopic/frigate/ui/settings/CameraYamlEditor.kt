package com.zektopic.frigate.ui.settings

import com.zektopic.frigate.ai.MotionConfig
import com.zektopic.frigate.ai.RelPoint
import com.zektopic.frigate.data.YamlConfigParser
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.util.Locale

/** Form state collected by the wizard; maps onto a Frigate camera block. */
data class CameraDraft(
    val originalId: String? = null, // set when editing an existing camera
    val name: String = "",
    val rtspUrl: String = "",
    /** Optional low-resolution stream to run detection on; blank uses [rtspUrl] for both. */
    val detectUrl: String = "",
    val detectWidth: Int = 640,
    val detectHeight: Int = 360,
    val fps: Int = 5,
    val retentionDays: Float = 3.0f,
    val enabled: Boolean = true
) {
    val cameraId: String
        get() = originalId ?: name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

/** Snapshot of a camera's live feature toggles, consumed by the NVR service. */
data class CameraFeatures(
    val detect: Boolean = true,
    val record: Boolean = true,
    val snapshots: Boolean = true
)

/** Top-level (non per-camera) configuration edited in the global settings forms. */
data class GlobalConfigDraft(
    val mqttHost: String = "",
    val mqttPort: Int = 1883,
    val detectWidth: Int = 640,
    val detectHeight: Int = 360,
    val detectFps: Int = 5,
    val trackedObjects: List<String> = listOf("person", "car", "bird")
)

/** The motion settings the tuner exposes; the rest of `motion:` is left as written. */
data class MotionDraft(
    val threshold: Int = 30,
    val contourArea: Int = 10,
    val improveContrast: Boolean = true
) {
    companion object {
        fun from(config: MotionConfig) = MotionDraft(config.threshold, config.contourArea, config.improveContrast)
    }
}

/** A motion mask as stored: [key] is its name in the map form, its index otherwise. */
data class MaskEntry(val key: String, val polygon: List<RelPoint>)

/**
 * Every edit the settings screens make to the stored config. Each function reads the
 * YAML, changes only the keys it is about, and writes the rest back untouched.
 */
object CameraYamlEditor {

    private fun yaml(): Yaml {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            indent = 2
            isPrettyFlow = true
        }
        return Yaml(options)
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadRoot(yamlText: String): MutableMap<String, Any> {
        val loaded = Yaml().load<Any>(yamlText.ifBlank { "{}" })
        return (loaded as? MutableMap<String, Any>) ?: linkedMapOf()
    }

    @Suppress("UNCHECKED_CAST")
    private fun MutableMap<String, Any>.child(key: String): MutableMap<String, Any> =
        (this[key] as? MutableMap<String, Any>) ?: linkedMapOf<String, Any>().also { this[key] = it }

    @Suppress("UNCHECKED_CAST")
    private fun camera(root: MutableMap<String, Any>, cameraId: String): MutableMap<String, Any>? =
        (root["cameras"] as? MutableMap<String, Any>)?.get(cameraId) as? MutableMap<String, Any>

    /**
     * Insert `cameras.<id>`, or update the keys the wizard owns on an existing one.
     *
     * Merges rather than replaces. Editing a camera used to rewrite its whole block,
     * which silently dropped everything the wizard does not show: zones, masks, the
     * snapshots toggle, any extra ffmpeg input.
     */
    fun upsertCamera(yamlText: String, draft: CameraDraft): String {
        val root = loadRoot(yamlText)
        val camera = root.child("cameras").child(draft.cameraId)

        camera["enabled"] = draft.enabled
        // Only written when it says something the id does not already.
        if (camera.containsKey("friendly_name") || draft.name.trim() != defaultName(draft.cameraId)) {
            camera["friendly_name"] = draft.name.trim()
        }
        // `name` was this app's own key for the same thing; friendly_name is Frigate's.
        camera.remove("name")

        val main = draft.rtspUrl.trim()
        val detect = draft.detectUrl.trim()
        camera.child("ffmpeg")["inputs"] = if (detect.isEmpty() || detect == main) {
            listOf(linkedMapOf("path" to main, "roles" to listOf("detect", "record")))
        } else {
            listOf(
                linkedMapOf("path" to detect, "roles" to listOf("detect")),
                linkedMapOf("path" to main, "roles" to listOf("record"))
            )
        }

        camera.child("detect").apply {
            this["width"] = draft.detectWidth
            this["height"] = draft.detectHeight
            this["fps"] = draft.fps
        }
        camera.child("record").apply {
            if (!containsKey("enabled")) this["enabled"] = true
            child("retain")["days"] = draft.retentionDays
        }
        return yaml().dump(root)
    }

    private fun defaultName(cameraId: String): String = cameraId.replace("_", " ")
        .split(" ")
        .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

    /** Remove `cameras.<id>` from the stored config YAML. */
    @Suppress("UNCHECKED_CAST")
    fun removeCamera(yamlText: String, cameraId: String): String {
        val root = loadRoot(yamlText)
        val cameras = (root["cameras"] as? MutableMap<String, Any>) ?: return yamlText
        cameras.remove(cameraId)
        root["cameras"] = cameras
        return yaml().dump(root)
    }

    /** Set `cameras.<id>.enabled`: whether the camera is ingested at all. */
    fun setCameraEnabled(yamlText: String, cameraId: String, enabled: Boolean): String {
        val root = loadRoot(yamlText)
        val cam = camera(root, cameraId) ?: return yamlText
        cam["enabled"] = enabled
        return yaml().dump(root)
    }

    /** Per-camera feature flag toggled from the camera view. */
    enum class Feature(val block: String) { DETECT("detect"), RECORD("record"), SNAPSHOTS("snapshots") }

    private fun asBool(value: Any?, default: Boolean): Boolean = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> default
    }

    /**
     * Read the detect/record/snapshots enabled flags for every camera in the
     * config. Absent flags default to true (the app's historical behavior), so
     * existing configs keep recording and snapshots on.
     */
    @Suppress("UNCHECKED_CAST")
    fun readAllCameraFeatures(yamlText: String): Map<String, CameraFeatures> {
        val root = try { loadRoot(yamlText) } catch (e: Exception) { return emptyMap() }
        val cameras = root["cameras"] as? Map<String, Any> ?: return emptyMap()
        val result = LinkedHashMap<String, CameraFeatures>()
        for ((id, raw) in cameras) {
            val cam = raw as? Map<String, Any> ?: continue
            fun blockEnabled(name: String): Boolean {
                val block = cam[name] as? Map<String, Any> ?: return true
                return asBool(block["enabled"], true)
            }
            result[id] = CameraFeatures(
                detect = blockEnabled("detect"),
                record = blockEnabled("record"),
                snapshots = blockEnabled("snapshots")
            )
        }
        return result
    }

    /** Set `cameras.<id>.<block>.enabled` and return the updated YAML. */
    fun setCameraFeature(yamlText: String, cameraId: String, feature: Feature, enabled: Boolean): String {
        val root = loadRoot(yamlText)
        val cam = camera(root, cameraId) ?: return yamlText
        cam.child(feature.block)["enabled"] = enabled
        return yaml().dump(root)
    }

    /** Read the top-level MQTT / detection / tracked-object globals. */
    @Suppress("UNCHECKED_CAST")
    fun readGlobals(yamlText: String): GlobalConfigDraft {
        val root = try { loadRoot(yamlText) } catch (e: Exception) { return GlobalConfigDraft() }
        val mqtt = root["mqtt"] as? Map<String, Any>
        val detect = root["detect"] as? Map<String, Any>
        val objects = root["objects"] as? Map<String, Any>
        val track = (objects?.get("track") as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
        val default = GlobalConfigDraft()
        return GlobalConfigDraft(
            mqttHost = (mqtt?.get("host") as? String) ?: "",
            mqttPort = (mqtt?.get("port") as? Number)?.toInt() ?: default.mqttPort,
            detectWidth = (detect?.get("width") as? Number)?.toInt() ?: default.detectWidth,
            detectHeight = (detect?.get("height") as? Number)?.toInt() ?: default.detectHeight,
            detectFps = (detect?.get("fps") as? Number)?.toInt() ?: default.detectFps,
            trackedObjects = track.ifEmpty { default.trackedObjects }
        )
    }

    /** Insert or replace the top-level `mqtt`, `detect`, and `objects.track` blocks. */
    fun upsertGlobals(yamlText: String, draft: GlobalConfigDraft): String {
        val root = loadRoot(yamlText)
        if (draft.mqttHost.isNotBlank()) {
            root.child("mqtt").apply {
                this["host"] = draft.mqttHost.trim()
                this["port"] = draft.mqttPort
            }
        } else {
            root.remove("mqtt")
        }
        root.child("detect").apply {
            this["width"] = draft.detectWidth
            this["height"] = draft.detectHeight
            this["fps"] = draft.detectFps
        }
        root.child("objects")["track"] = draft.trackedObjects
        return yaml().dump(root)
    }

    /**
     * Write the tuner's motion settings to `cameras.<id>.motion`, or to the top-level
     * `motion` block when [cameraId] is null.
     */
    fun setMotion(yamlText: String, cameraId: String?, draft: MotionDraft): String {
        val root = loadRoot(yamlText)
        val holder = if (cameraId == null) root else camera(root, cameraId) ?: return yamlText
        holder.child("motion").apply {
            this["threshold"] = draft.threshold.coerceIn(1, 255)
            this["contour_area"] = draft.contourArea.coerceAtLeast(0)
            this["improve_contrast"] = draft.improveContrast
        }
        return yaml().dump(root)
    }

    /** The top-level `motion` block's tunable values, defaults where unset. */
    @Suppress("UNCHECKED_CAST")
    fun readGlobalMotion(yamlText: String): MotionDraft {
        val root = try { loadRoot(yamlText) } catch (e: Exception) { return MotionDraft() }
        val motion = root["motion"] as? Map<String, Any> ?: return MotionDraft()
        val defaults = MotionDraft()
        return MotionDraft(
            threshold = ((motion["threshold"] as? Number)?.toInt() ?: defaults.threshold).coerceIn(1, 255),
            contourArea = ((motion["contour_area"] as? Number)?.toInt() ?: defaults.contourArea).coerceAtLeast(0),
            improveContrast = asBool(motion["improve_contrast"], defaults.improveContrast)
        )
    }

    /** `low_resource.enabled`. */
    fun setLowResource(yamlText: String, enabled: Boolean): String {
        val root = loadRoot(yamlText)
        root.child("low_resource")["enabled"] = enabled
        return yaml().dump(root)
    }

    /** `android.max_concurrent_streams`; null removes it so the device budget decides. */
    @Suppress("UNCHECKED_CAST")
    fun setMaxConcurrentStreams(yamlText: String, cap: Int?): String {
        val root = loadRoot(yamlText)
        if (cap != null) {
            root.child("android")["max_concurrent_streams"] = cap
        } else {
            (root["android"] as? MutableMap<String, Any>)?.let { android ->
                android.remove("max_concurrent_streams")
                if (android.isEmpty()) root.remove("android")
            }
        }
        return yaml().dump(root)
    }

    /** Frigate's `x1,y1,x2,y2,...` with relative coordinates. */
    fun coordinates(points: List<RelPoint>): String = points.joinToString(",") { point ->
        "${trim(point.x)},${trim(point.y)}"
    }

    private fun trim(value: Float): String =
        String.format(Locale.US, "%.3f", value.coerceIn(0f, 1f)).trimEnd('0').trimEnd('.')

    /** A zone name as Frigate requires it: lowercase letters, digits and underscores. */
    fun zoneId(name: String): String = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** Add or replace `cameras.<id>.zones.<name>`. */
    fun setZone(yamlText: String, cameraId: String, name: String, points: List<RelPoint>): String {
        val id = zoneId(name)
        if (id.isEmpty() || points.size < 3) return yamlText
        val root = loadRoot(yamlText)
        val cam = camera(root, cameraId) ?: return yamlText
        cam.child("zones").child(id)["coordinates"] = coordinates(points)
        return yaml().dump(root)
    }

    @Suppress("UNCHECKED_CAST")
    fun removeZone(yamlText: String, cameraId: String, name: String): String {
        val root = loadRoot(yamlText)
        val cam = camera(root, cameraId) ?: return yamlText
        val zones = cam["zones"] as? MutableMap<String, Any> ?: return yamlText
        zones.remove(name)
        if (zones.isEmpty()) cam.remove("zones")
        return yaml().dump(root)
    }

    /** The camera's own motion masks, in whichever of Frigate's three forms they are stored. */
    @Suppress("UNCHECKED_CAST")
    fun readMotionMasks(yamlText: String, cameraId: String, width: Int, height: Int): List<MaskEntry> {
        val root = try { loadRoot(yamlText) } catch (e: Exception) { return emptyList() }
        val raw = (camera(root, cameraId)?.get("motion") as? Map<String, Any>)?.get("mask")
        val entries: List<Pair<String, Any?>> = when (raw) {
            null -> emptyList()
            is Map<*, *> -> raw.entries.map { (key, value) -> key.toString() to (value as? Map<*, *>)?.get("coordinates") }
            is List<*> ->
                if (raw.all { it is Number }) listOf("0" to raw)
                else raw.mapIndexed { index, value -> index.toString() to value }
            else -> listOf("0" to raw)
        }
        return entries.mapNotNull { (key, coordinates) ->
            val polygon = YamlConfigParser.parseCoordinates(coordinates, width, height)
            if (polygon.size < 3) null else MaskEntry(key, polygon)
        }
    }

    /** Append a motion mask, keeping the form the config already uses. */
    @Suppress("UNCHECKED_CAST")
    fun addMotionMask(yamlText: String, cameraId: String, points: List<RelPoint>): String {
        if (points.size < 3) return yamlText
        val root = loadRoot(yamlText)
        val cam = camera(root, cameraId) ?: return yamlText
        val motion = cam.child("motion")
        val coordinates = coordinates(points)
        when (val raw = motion["mask"]) {
            null -> motion["mask"] = mutableListOf(coordinates)
            is MutableMap<*, *> -> {
                val masks = raw as MutableMap<String, Any>
                var index = masks.size + 1
                while (masks.containsKey("mask_$index")) index++
                masks["mask_$index"] = linkedMapOf("coordinates" to coordinates)
            }
            is List<*> ->
                motion["mask"] =
                    if (raw.all { it is Number }) mutableListOf(raw.joinToString(","), coordinates)
                    else (raw.toMutableList() as MutableList<Any?>).apply { add(coordinates) }
            else -> motion["mask"] = mutableListOf(raw.toString(), coordinates)
        }
        return yaml().dump(root)
    }

    /** Remove the mask [readMotionMasks] reported under [key]. */
    @Suppress("UNCHECKED_CAST")
    fun removeMotionMask(yamlText: String, cameraId: String, key: String): String {
        val root = loadRoot(yamlText)
        val cam = camera(root, cameraId) ?: return yamlText
        val motion = cam["motion"] as? MutableMap<String, Any> ?: return yamlText
        when (val raw = motion["mask"]) {
            is MutableMap<*, *> -> {
                raw.remove(key)
                if (raw.isEmpty()) motion.remove("mask")
            }
            is List<*> -> {
                val index = key.toIntOrNull() ?: return yamlText
                if (raw.all { it is Number }) {
                    motion.remove("mask")
                } else {
                    val remaining = raw.filterIndexed { i, _ -> i != index }
                    if (remaining.isEmpty()) motion.remove("mask") else motion["mask"] = remaining
                }
            }
            null -> return yamlText
            else -> motion.remove("mask")
        }
        if (motion.isEmpty()) cam.remove("motion")
        return yaml().dump(root)
    }
}
