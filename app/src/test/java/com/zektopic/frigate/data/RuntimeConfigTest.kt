package com.zektopic.frigate.data

import com.zektopic.frigate.ai.MotionBox
import com.zektopic.frigate.ai.MotionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeConfigTest {

    private fun camera(body: String, globals: String = "") = """
$globals
cameras:
  front:
    ffmpeg:
      inputs:
        - path: rtsp://host/front
$body
    """.trimIndent()

    @Test
    fun motionDefaultsAreFrigatesDefaults() {
        val motion = YamlConfigParser.parseRuntimeConfigs(camera("")).getValue("front").motion
        assertEquals(MotionConfig(), motion)
        assertEquals(30, motion.threshold)
        assertEquals(10, motion.contourArea)
        assertEquals(100, motion.frameHeight)
    }

    @Test
    fun cameraMotionSettingsOverrideGlobalsKeyByKey() {
        val yaml = camera(
            globals = "motion:\n  threshold: 40\n  contour_area: 25\n  improve_contrast: false",
            body = "    motion:\n      threshold: 55"
        )
        val motion = YamlConfigParser.parseRuntimeConfigs(yaml).getValue("front").motion
        assertEquals(55, motion.threshold)       // camera wins
        assertEquals(25, motion.contourArea)     // inherited
        assertFalse(motion.improveContrast)      // inherited
    }

    @Test
    fun outOfRangeMotionValuesAreClamped() {
        val yaml = camera("    motion:\n      threshold: 900\n      lightning_threshold: 0.01\n      frame_height: 5")
        val motion = YamlConfigParser.parseRuntimeConfigs(yaml).getValue("front").motion
        assertEquals(255, motion.threshold)
        assertEquals(0.3f, motion.lightningThreshold, 0f)
        assertEquals(32, motion.frameHeight)
    }

    @Test
    fun masksParseInAllThreeFrigateForms() {
        val single = camera("    motion:\n      mask: 0,0,0.5,0,0.5,0.5")
        val list = camera("    motion:\n      mask:\n        - 0,0,0.5,0,0.5,0.5\n        - 0.5,0.5,1,0.5,1,1")
        val named = camera(
            "    motion:\n      mask:\n        timestamp:\n          coordinates: 0,0,0.5,0,0.5,0.5\n" +
                "        off:\n          enabled: false\n          coordinates: 0.5,0.5,1,0.5,1,1"
        )
        fun masks(yaml: String) = YamlConfigParser.parseRuntimeConfigs(yaml).getValue("front").motion.masks
        assertEquals(1, masks(single).size)
        assertEquals(2, masks(list).size)
        assertEquals("a disabled mask is not applied", 1, masks(named).size)
        assertEquals(0.5f, masks(single)[0][1].x, 0f)
    }

    @Test
    fun aCameraMaskReplacesTheGlobalOne() {
        val yaml = camera(
            globals = "motion:\n  mask: 0,0,1,0,1,1",
            body = "    motion:\n      mask: 0,0,0.2,0,0.2,0.2"
        )
        val masks = YamlConfigParser.parseRuntimeConfigs(yaml).getValue("front").motion.masks
        assertEquals(1, masks.size)
        assertEquals(0.2f, masks[0][1].x, 0f)
    }

    @Test
    fun absolutePixelCoordinatesAreScaledByTheDetectSize() {
        // The pre-0.14 form: pixels at detect resolution
        val points = YamlConfigParser.parseCoordinates("0,0,320,0,320,180", 640, 360)
        assertEquals(0.5f, points[1].x, 0f)
        assertEquals(0.5f, points[2].y, 0f)
    }

    @Test
    fun malformedCoordinatesYieldNoPolygon() {
        assertTrue(YamlConfigParser.parseCoordinates("0,0,1,1", 640, 360).isEmpty())       // two points
        assertTrue(YamlConfigParser.parseCoordinates("0,0,1,1,0.5", 640, 360).isEmpty())   // odd count
        assertTrue(YamlConfigParser.parseCoordinates(null, 640, 360).isEmpty())
    }

    private val zonesYaml = camera(
        "    zones:\n      driveway:\n        coordinates: 0,0.5,1,0.5,1,1,0,1\n" +
            "      porch:\n        coordinates: 0,0,0.3,0,0.3,0.3,0,0.3"
    )

    @Test
    fun aBoxIsAttributedToTheZoneItsBottomCentreStandsIn() {
        val runtime = YamlConfigParser.parseRuntimeConfigs(zonesYaml).getValue("front")
        // Overlaps the driveway from above but its feet are at y=0.4, outside it
        val above = MotionBox(200, 40, 300, 144)
        // Feet at y=0.8, inside the lower half
        val inside = MotionBox(200, 200, 300, 288)
        assertEquals(emptyList<String>(), runtime.zonesFor(listOf(above), 640, 360))
        assertEquals(listOf("driveway"), runtime.zonesFor(listOf(inside), 640, 360))
        assertEquals(listOf("driveway"), runtime.zonesFor(listOf(above, inside), 640, 360))
    }

    @Test
    fun aBoxTouchingTheBottomEdgeStillCountsAsInside() {
        val runtime = YamlConfigParser.parseRuntimeConfigs(zonesYaml).getValue("front")
        assertEquals(listOf("driveway"), runtime.zonesFor(listOf(MotionBox(200, 300, 300, 360)), 640, 360))
    }

    @Test
    fun byDefaultAllMotionIsAnAlert() {
        assertEquals(ReviewSeverity.ALERT, ReviewRules().classify("motion", emptyList()))
    }

    @Test
    fun requiredZonesSplitAlertsFromDetections() {
        val yaml = camera(
            globals = "review:\n  alerts:\n    required_zones:\n      - driveway",
            body = ""
        )
        val rules = YamlConfigParser.parseRuntimeConfigs(yaml).getValue("front").review
        assertEquals(ReviewSeverity.ALERT, rules.classify("motion", listOf("driveway")))
        assertEquals(ReviewSeverity.DETECTION, rules.classify("motion", listOf("porch")))
        assertEquals(ReviewSeverity.DETECTION, rules.classify("motion", emptyList()))
    }

    @Test
    fun motionOutsideEveryRequiredZoneIsNotRecorded() {
        val rules = ReviewRules(
            alertRequiredZones = listOf("driveway"),
            detectionRequiredZones = listOf("porch")
        )
        assertNull(rules.classify("motion", listOf("lawn")))
        assertEquals(ReviewSeverity.DETECTION, rules.classify("motion", listOf("porch")))
    }

    @Test
    fun cameraReviewRulesOverrideGlobalOnes() {
        val yaml = camera(
            globals = "review:\n  alerts:\n    required_zones: [driveway]",
            body = "    review:\n      alerts:\n        required_zones: [porch]"
        )
        val rules = YamlConfigParser.parseRuntimeConfigs(yaml).getValue("front").review
        assertEquals(listOf("porch"), rules.alertRequiredZones)
    }

    @Test
    fun lowResourceIsOffUnlessEnabled() {
        assertFalse(YamlConfigParser.parseLowResource("cameras: {}"))
        assertFalse(YamlConfigParser.parseLowResource("low_resource:\n  enabled: false"))
        assertTrue(YamlConfigParser.parseLowResource("low_resource:\n  enabled: true"))
        assertFalse(YamlConfigParser.parseLowResource("not: [valid"))
    }

    @Test
    fun aNonMappingDocumentParsesToNothingInsteadOfThrowing() {
        assertTrue(YamlConfigParser.parseConfig("just a string").isEmpty())
        assertTrue(YamlConfigParser.parseRuntimeConfigs("- a\n- list").isEmpty())
    }

    @Test
    fun unusableDetectValuesAreClampedAtParseTime() {
        val yaml = camera("    detect:\n      width: 0\n      height: -5\n      fps: 0")
        val cam = YamlConfigParser.parseConfig(yaml).single()
        assertEquals(1, cam.fps)
        assertTrue(cam.detectWidth > 0 && cam.detectHeight > 0)
    }

    @Test
    fun friendlyNameIsUsedWhenPresent() {
        val cam = YamlConfigParser.parseConfig(camera("    friendly_name: Front Door")).single()
        assertEquals("Front Door", cam.name)
    }
}
