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
            motionThresholdPercent = 3,
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
}
