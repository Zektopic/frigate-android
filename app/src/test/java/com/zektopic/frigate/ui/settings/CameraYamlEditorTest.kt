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
}
