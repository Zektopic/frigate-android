package com.zektopic.frigate.ui.settings

import com.zektopic.frigate.data.YamlConfigParser
import org.junit.Assert.*
import org.junit.Test

class CameraYamlEditorTest {

    private val baseYaml = """
        detect:
          width: 640
          height: 360
          fps: 5
        cameras:
          existing_cam:
            enabled: true
            ffmpeg:
              inputs:
                - path: rtsp://192.168.1.9:8554/existing_cam
                  roles: [detect, record]
    """.trimIndent()

    @Test
    fun upsertAddsNewCameraAndParsesBack() {
        val draft = CameraDraft(
            name = "Front Door",
            rtspUrl = "rtsp://user:pass@192.168.1.10:554/stream",
            detectWidth = 1280,
            detectHeight = 720,
            fps = 5,
            retentionDays = 7f,
            enabled = true
        )
        val newYaml = CameraYamlEditor.upsertCamera(baseYaml, draft)

        // The existing camera survives, the new one is added
        val cameras = YamlConfigParser.parseConfig(newYaml)
        assertEquals(2, cameras.size)
        val front = cameras.find { it.id == "front_door" }
        assertNotNull("front_door camera should exist", front)
        assertEquals("rtsp://user:pass@192.168.1.10:554/stream", front!!.rtspUrl)
        assertEquals(1280, front.detectWidth)
        assertEquals(720, front.detectHeight)
        assertNotNull(cameras.find { it.id == "existing_cam" })
    }

    @Test
    fun upsertReplacesExistingCameraById() {
        val draft = CameraDraft(
            originalId = "existing_cam",
            name = "Existing Cam",
            rtspUrl = "rtsp://192.168.1.9:8554/existing_cam_new",
            detectWidth = 640,
            detectHeight = 360,
            fps = 10
        )
        val newYaml = CameraYamlEditor.upsertCamera(baseYaml, draft)
        val cameras = YamlConfigParser.parseConfig(newYaml)
        assertEquals(1, cameras.size)
        assertEquals("rtsp://192.168.1.9:8554/existing_cam_new", cameras[0].rtspUrl)
        assertEquals(10, cameras[0].fps)
    }

    @Test
    fun removeDeletesCamera() {
        val newYaml = CameraYamlEditor.removeCamera(baseYaml, "existing_cam")
        val cameras = YamlConfigParser.parseConfig(newYaml)
        assertTrue("all cameras removed", cameras.isEmpty())
    }

    @Test
    fun cameraIdDerivedFromName() {
        assertEquals("front_door_cam", CameraDraft(name = "Front Door Cam").cameraId)
        assertEquals("garage_2", CameraDraft(name = "Garage #2").cameraId)
    }

    @Test
    fun featuresDefaultToOnWhenFlagsAbsent() {
        // existing_cam has no detect/record/snapshots enabled flags → all default true
        val features = CameraYamlEditor.readAllCameraFeatures(baseYaml)
        val f = features["existing_cam"]
        assertNotNull(f)
        assertTrue(f!!.detect)
        assertTrue(f.record)
        assertTrue(f.snapshots)
    }

    @Test
    fun setCameraFeatureIsReadBack() {
        val off = CameraYamlEditor.setCameraFeature(
            baseYaml, "existing_cam", CameraYamlEditor.Feature.RECORD, false
        )
        val features = CameraYamlEditor.readAllCameraFeatures(off)
        assertEquals(false, features["existing_cam"]?.record)
        // Other features untouched
        assertEquals(true, features["existing_cam"]?.detect)

        // Toggling back on round-trips
        val on = CameraYamlEditor.setCameraFeature(
            off, "existing_cam", CameraYamlEditor.Feature.RECORD, true
        )
        assertEquals(true, CameraYamlEditor.readAllCameraFeatures(on)["existing_cam"]?.record)
    }

    @Test
    fun setCameraFeaturePreservesOtherCameraData() {
        val updated = CameraYamlEditor.setCameraFeature(
            baseYaml, "existing_cam", CameraYamlEditor.Feature.SNAPSHOTS, false
        )
        // Camera stream config survives the feature toggle
        val cameras = YamlConfigParser.parseConfig(updated)
        assertEquals(1, cameras.size)
        assertEquals("rtsp://192.168.1.9:8554/existing_cam", cameras[0].rtspUrl)
    }

    @Test
    fun globalsRoundTrip() {
        val draft = GlobalConfigDraft(
            mqttHost = "192.168.1.50",
            mqttPort = 1884,
            detectWidth = 1280,
            detectHeight = 720,
            detectFps = 8,
            trackedObjects = listOf("person", "dog")
        )
        val yaml = CameraYamlEditor.upsertGlobals(baseYaml, draft)
        val read = CameraYamlEditor.readGlobals(yaml)
        assertEquals("192.168.1.50", read.mqttHost)
        assertEquals(1884, read.mqttPort)
        assertEquals(1280, read.detectWidth)
        assertEquals(8, read.detectFps)
        assertEquals(listOf("person", "dog"), read.trackedObjects)
        // Existing cameras are untouched by a globals edit
        assertEquals(1, YamlConfigParser.parseConfig(yaml).size)
    }

    @Test
    fun blankMqttHostRemovesMqttBlock() {
        val withMqtt = CameraYamlEditor.upsertGlobals(baseYaml, GlobalConfigDraft(mqttHost = "10.0.0.1"))
        assertTrue(withMqtt.contains("mqtt"))
        val withoutMqtt = CameraYamlEditor.upsertGlobals(withMqtt, GlobalConfigDraft(mqttHost = ""))
        assertEquals("", CameraYamlEditor.readGlobals(withoutMqtt).mqttHost)
    }

    @Test
    fun appSpecificStreamCapSurvivesEveryEditor() {
        val withCap = "android:\n  max_concurrent_streams: 5\n$baseYaml"
        val edits = listOf(
            CameraYamlEditor.upsertCamera(withCap, CameraDraft(name = "Porch", rtspUrl = "rtsp://h:8554/porch")),
            CameraYamlEditor.removeCamera(withCap, "existing_cam"),
            CameraYamlEditor.setCameraFeature(withCap, "existing_cam", CameraYamlEditor.Feature.RECORD, false),
            CameraYamlEditor.upsertGlobals(withCap, GlobalConfigDraft(mqttHost = "192.168.1.10"))
        )
        for (edited in edits) {
            assertEquals(edited, 5, YamlConfigParser.parseMaxConcurrentStreams(edited))
        }
    }

    private val richYaml = """
        cameras:
          front:
            ffmpeg:
              inputs:
                - path: rtsp://host:8554/front_sub
                  roles: [detect]
                - path: rtsp://user:pass@host:8554/front
                  roles: [record]
            snapshots:
              enabled: false
            motion:
              threshold: 45
              mask:
                - 0,0,0.3,0,0.3,0.1
            zones:
              driveway:
                coordinates: 0,0.5,1,0.5,1,1,0,1
    """.trimIndent()

    @Test
    fun editingACameraKeepsEverythingTheWizardDoesNotShow() {
        // Editing used to rewrite the whole camera block, dropping all of this.
        val edited = CameraYamlEditor.upsertCamera(
            richYaml,
            CameraDraft(
                originalId = "front",
                name = "Front",
                rtspUrl = "rtsp://user:pass@host:8554/front",
                detectUrl = "rtsp://host:8554/front_sub",
                fps = 8
            )
        )
        val camera = YamlConfigParser.parseConfig(edited).single()
        assertEquals(8, camera.fps)
        assertEquals("rtsp://host:8554/front_sub", camera.detectRtspUrl)

        val runtime = YamlConfigParser.parseRuntimeConfigs(edited).getValue("front")
        assertEquals(listOf("driveway"), runtime.zones.map { it.name })
        assertEquals(45, runtime.motion.threshold)
        assertEquals(1, runtime.motion.masks.size)
        assertEquals(false, CameraYamlEditor.readAllCameraFeatures(edited)["front"]?.snapshots)
    }

    @Test
    fun aDetectSubstreamIsWrittenAsItsOwnInput() {
        val yaml = CameraYamlEditor.upsertCamera(
            baseYaml,
            CameraDraft(name = "Porch", rtspUrl = "rtsp://h:8554/porch", detectUrl = "rtsp://h:8554/porch_sub")
        )
        val porch = YamlConfigParser.parseConfig(yaml).first { it.id == "porch" }
        assertEquals("rtsp://h:8554/porch", porch.rtspUrl)
        assertEquals("rtsp://h:8554/porch_sub", porch.detectRtspUrl)
        assertEquals("rtsp://h:8554/porch_sub", porch.effectiveDetectUrl)
    }

    @Test
    fun clearingTheSubstreamGoesBackToOneInput() {
        val edited = CameraYamlEditor.upsertCamera(
            richYaml,
            CameraDraft(originalId = "front", name = "Front", rtspUrl = "rtsp://user:pass@host:8554/front", detectUrl = "")
        )
        assertEquals("", YamlConfigParser.parseConfig(edited).single().detectRtspUrl)
    }

    @Test
    fun aRenamedCameraKeepsItsIdAndGainsAFriendlyName() {
        val edited = CameraYamlEditor.upsertCamera(
            baseYaml,
            CameraDraft(originalId = "existing_cam", name = "Back Gate", rtspUrl = "rtsp://192.168.1.9:8554/existing_cam")
        )
        val camera = YamlConfigParser.parseConfig(edited).single()
        assertEquals("existing_cam", camera.id)
        assertEquals("Back Gate", camera.name)
    }

    @Test
    fun cameraEnabledFlagRoundTrips() {
        val off = CameraYamlEditor.setCameraEnabled(baseYaml, "existing_cam", false)
        assertEquals(false, YamlConfigParser.parseConfig(off).single().isEnabled)
        val on = CameraYamlEditor.setCameraEnabled(off, "existing_cam", true)
        assertEquals(true, YamlConfigParser.parseConfig(on).single().isEnabled)
    }

    @Test
    fun cameraMotionSettingsAreWrittenWithFrigatesKeys() {
        val yaml = CameraYamlEditor.setMotion(baseYaml, "existing_cam", MotionDraft(threshold = 40, contourArea = 25, improveContrast = false))
        val motion = YamlConfigParser.parseRuntimeConfigs(yaml).getValue("existing_cam").motion
        assertEquals(40, motion.threshold)
        assertEquals(25, motion.contourArea)
        assertEquals(false, motion.improveContrast)
    }

    @Test
    fun globalMotionSettingsApplyToEveryCameraAndReadBack() {
        val draft = MotionDraft(threshold = 50, contourArea = 30, improveContrast = true)
        val yaml = CameraYamlEditor.setMotion(baseYaml, null, draft)
        assertEquals(draft, CameraYamlEditor.readGlobalMotion(yaml))
        assertEquals(50, YamlConfigParser.parseRuntimeConfigs(yaml).getValue("existing_cam").motion.threshold)
    }

    @Test
    fun savingMotionSettingsKeepsTheCamerasMasks() {
        val yaml = CameraYamlEditor.setMotion(richYaml, "front", MotionDraft(threshold = 20))
        val motion = YamlConfigParser.parseRuntimeConfigs(yaml).getValue("front").motion
        assertEquals(20, motion.threshold)
        assertEquals(1, motion.masks.size)
    }

    private val triangle = listOf(
        com.zektopic.frigate.ai.RelPoint(0.1f, 0.1f),
        com.zektopic.frigate.ai.RelPoint(0.9f, 0.1f),
        com.zektopic.frigate.ai.RelPoint(0.5f, 0.75f)
    )

    @Test
    fun zonesAreAddedReplacedAndRemoved() {
        val added = CameraYamlEditor.setZone(baseYaml, "existing_cam", "Front Porch", triangle)
        val zone = YamlConfigParser.parseRuntimeConfigs(added).getValue("existing_cam").zones.single()
        assertEquals("front_porch", zone.name)
        assertEquals(0.75f, zone.polygon[2].y, 0.0005f)

        val removed = CameraYamlEditor.removeZone(added, "existing_cam", "front_porch")
        assertTrue(YamlConfigParser.parseRuntimeConfigs(removed).getValue("existing_cam").zones.isEmpty())
        assertFalse(removed.contains("zones"))
    }

    @Test
    fun aZoneNeedsANameAndThreePoints() {
        assertEquals(baseYaml, CameraYamlEditor.setZone(baseYaml, "existing_cam", "  ", triangle))
        assertEquals(baseYaml, CameraYamlEditor.setZone(baseYaml, "existing_cam", "porch", triangle.take(2)))
        assertEquals(baseYaml, CameraYamlEditor.setZone(baseYaml, "no_such_camera", "porch", triangle))
    }

    @Test
    fun coordinatesAreWrittenCompactlyAndLocaleIndependent() {
        assertEquals("0.1,0.1,0.9,0.1,0.5,0.75", CameraYamlEditor.coordinates(triangle))
    }

    @Test
    fun motionMasksAreAppendedAndRemovedByKey() {
        val one = CameraYamlEditor.addMotionMask(baseYaml, "existing_cam", triangle)
        val two = CameraYamlEditor.addMotionMask(one, "existing_cam", triangle.map { it.copy(y = it.y / 2) })
        val masks = CameraYamlEditor.readMotionMasks(two, "existing_cam", 640, 360)
        assertEquals(listOf("0", "1"), masks.map { it.key })

        val afterRemove = CameraYamlEditor.removeMotionMask(two, "existing_cam", "0")
        val left = CameraYamlEditor.readMotionMasks(afterRemove, "existing_cam", 640, 360).single()
        assertEquals("the second mask is the one that remains", 0.375f, left.polygon[2].y, 0.0005f)

        val none = CameraYamlEditor.removeMotionMask(afterRemove, "existing_cam", "0")
        assertTrue(CameraYamlEditor.readMotionMasks(none, "existing_cam", 640, 360).isEmpty())
    }

    @Test
    fun aMaskAddedToASingleStringMaskKeepsTheOriginal() {
        val yaml = baseYaml + "\n    motion:\n      mask: 0,0,0.3,0,0.3,0.1\n"
        val added = CameraYamlEditor.addMotionMask(yaml, "existing_cam", triangle)
        assertEquals(2, YamlConfigParser.parseRuntimeConfigs(added).getValue("existing_cam").motion.masks.size)
    }

    @Test
    fun namedMasksStayNamed() {
        val yaml = baseYaml + "\n    motion:\n      mask:\n        timestamp:\n          coordinates: 0,0,0.3,0,0.3,0.1\n"
        val added = CameraYamlEditor.addMotionMask(yaml, "existing_cam", triangle)
        val masks = CameraYamlEditor.readMotionMasks(added, "existing_cam", 640, 360)
        assertEquals(listOf("timestamp", "mask_2"), masks.map { it.key })
        val removed = CameraYamlEditor.removeMotionMask(added, "existing_cam", "timestamp")
        assertEquals(listOf("mask_2"), CameraYamlEditor.readMotionMasks(removed, "existing_cam", 640, 360).map { it.key })
    }

    @Test
    fun lowResourceAndStreamCapRoundTrip() {
        val low = CameraYamlEditor.setLowResource(baseYaml, true)
        assertTrue(YamlConfigParser.parseLowResource(low))
        assertFalse(YamlConfigParser.parseLowResource(CameraYamlEditor.setLowResource(low, false)))

        val capped = CameraYamlEditor.setMaxConcurrentStreams(baseYaml, 3)
        assertEquals(3, YamlConfigParser.parseMaxConcurrentStreams(capped))
        val auto = CameraYamlEditor.setMaxConcurrentStreams(capped, null)
        assertNull(YamlConfigParser.parseMaxConcurrentStreams(auto))
        assertFalse("an emptied android block is removed", auto.contains("android"))
    }
}
