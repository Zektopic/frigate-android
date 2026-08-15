package com.zektopic.frigate

import com.zektopic.frigate.data.YamlConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class YamlConfigParserTest {

    @Test
    fun testParseConfigWithGlobalAndCameraSpecificSettings() {
        val yamlText = """
            version: 0.18.0

            mqtt:
              host: 192.168.1.10
              port: 1883

            detect:
              width: 640
              height: 360
              fps: 5

            cameras:
              front_camera:
                ffmpeg:
                  inputs:
                    - path: rtsp://user:pass@192.168.1.33/stream=0
                      roles:
                        - detect
                        - record
                record:
                  enabled: true
                  retain:
                    days: 3
                snapshots:
                  enabled: true

              work_camera:
                ffmpeg:
                  inputs:
                    - path: rtsp://10.50.0.63:8554/stream
                      roles:
                        - detect
                        - record
                record:
                  enabled: true
                  retain:
                    days: 0.5
                snapshots:
                  enabled: true
        """.trimIndent()

        val configs = YamlConfigParser.parseConfig(yamlText)
        assertEquals(2, configs.size)

        val frontCamera = configs.find { it.id == "front_camera" }
        assertNotNull(frontCamera)
        assertEquals("Front Camera", frontCamera!!.name)
        assertEquals("rtsp://user:pass@192.168.1.33/stream=0", frontCamera.rtspUrl)
        assertEquals(640, frontCamera.detectWidth)
        assertEquals(360, frontCamera.detectHeight)
        assertEquals(5, frontCamera.fps)
        assertEquals(3.0f, frontCamera.recordingRetentionDays)

        val workCamera = configs.find { it.id == "work_camera" }
        assertNotNull(workCamera)
        assertEquals("Work Camera", workCamera!!.name)
        assertEquals("rtsp://10.50.0.63:8554/stream", workCamera.rtspUrl)
        assertEquals(640, workCamera.detectWidth)
        assertEquals(360, workCamera.detectHeight)
        assertEquals(5, workCamera.fps)
        assertEquals(0.5f, workCamera.recordingRetentionDays)
    }
}
