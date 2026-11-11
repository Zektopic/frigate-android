package com.zektopic.frigate.data

import com.zektopic.frigate.ai.MotionBox
import com.zektopic.frigate.ai.MotionConfig
import com.zektopic.frigate.ai.Polygons
import com.zektopic.frigate.ai.RelPoint

/** A named area of the frame; motion inside it is attributed to the zone. */
data class ZoneConfig(val name: String, val polygon: List<RelPoint>)

/** How an event is filed on the Review page. Values match Frigate's review severities. */
enum class ReviewSeverity(val key: String) {
    ALERT("alert"),
    DETECTION("detection");

    companion object {
        fun fromKey(key: String?): ReviewSeverity = values().firstOrNull { it.key == key } ?: ALERT
    }
}

/**
 * Frigate's `review:` block.
 *
 * The one deliberate difference is the default alert label. Frigate defaults to
 * `person` and `car`; this app only ever produces `motion`, so with Frigate's default
 * nothing would be an alert and nothing would notify. Everything else behaves the
 * same: add `required_zones` and only motion inside those zones alerts, the rest is
 * filed as a detection.
 */
data class ReviewRules(
    val alertLabels: List<String> = listOf(MOTION_LABEL),
    val alertRequiredZones: List<String> = emptyList(),
    /** Null means every label, as in Frigate. */
    val detectionLabels: List<String>? = null,
    val detectionRequiredZones: List<String> = emptyList()
) {
    /** Null when the event qualifies as neither, in which case Frigate records no review item. */
    fun classify(label: String, zones: Collection<String>): ReviewSeverity? {
        val isAlert = label in alertLabels &&
            (alertRequiredZones.isEmpty() || zones.any { it in alertRequiredZones })
        if (isAlert) return ReviewSeverity.ALERT
        val isDetection = (detectionLabels == null || label in detectionLabels) &&
            (detectionRequiredZones.isEmpty() || zones.any { it in detectionRequiredZones })
        return if (isDetection) ReviewSeverity.DETECTION else null
    }

    companion object {
        const val MOTION_LABEL = "motion"
    }
}

/** The parts of a camera's config the service reads straight from the YAML. */
data class CameraRuntimeConfig(
    val motion: MotionConfig = MotionConfig(),
    val zones: List<ZoneConfig> = emptyList(),
    val review: ReviewRules = ReviewRules()
) {
    /**
     * Zones a set of motion boxes falls in. Uses each box's bottom-centre, the point
     * Frigate tests for an object: it is where something standing in the scene
     * touches the ground, so a zone drawn on the driveway is not tripped by a head
     * that overlaps it from behind.
     */
    fun zonesFor(boxes: List<MotionBox>, frameWidth: Int, frameHeight: Int): List<String> {
        if (zones.isEmpty() || boxes.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return emptyList()
        return zones.filter { zone ->
            boxes.any { box ->
                val x = (box.x1 + box.x2) / 2f / frameWidth
                val y = box.y2.toFloat() / frameHeight
                Polygons.contains(zone.polygon, x, y.coerceAtMost(0.9999f))
            }
        }.map { it.name }
    }
}
