package com.zektopic.frigate.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigMigrationsTest {

    @Test
    fun wizardWrittenPercentThresholdsAreDropped() {
        val yaml = """
            cameras:
              front:
                ffmpeg:
                  inputs:
                    - path: rtsp://host/front
                motion:
                  threshold: 2
        """.trimIndent()
        val migrated = ConfigMigrations.dropLegacyMotionThresholds(yaml)
        assertNotNull(migrated)
        val motion = YamlConfigParser.parseRuntimeConfigs(migrated!!).getValue("front").motion
        assertEquals("falls back to Frigate's default", 30, motion.threshold)
        assertEquals("the camera itself is untouched", "rtsp://host/front", YamlConfigParser.parseConfig(migrated).single().rtspUrl)
    }

    @Test
    fun otherMotionKeysSurvive() {
        val yaml = "motion:\n  threshold: 5\n  contour_area: 40\ncameras:\n  a:\n    ffmpeg:\n      inputs:\n        - path: rtsp://h/a\n"
        val migrated = ConfigMigrations.dropLegacyMotionThresholds(yaml)!!
        assertFalse(migrated.contains("threshold"))
        assertTrue(migrated.contains("contour_area: 40"))
    }

    @Test
    fun valuesTheWizardCouldNotHaveWrittenAreKept() {
        val yaml = "motion:\n  threshold: 80\ncameras: {}\n"
        assertNull(ConfigMigrations.dropLegacyMotionThresholds(yaml))
    }

    @Test
    fun aConfigWithoutThresholdsIsLeftByteForByte() {
        val yaml = "# my comments\ncameras:\n  a:\n    ffmpeg:\n      inputs:\n        - path: rtsp://h/a\n"
        assertNull(ConfigMigrations.dropLegacyMotionThresholds(yaml))
    }

    @Test
    fun invalidYamlIsLeftAlone() {
        assertNull(ConfigMigrations.dropLegacyMotionThresholds("cameras: [unclosed"))
        assertNull(ConfigMigrations.dropLegacyMotionThresholds(""))
    }
}
