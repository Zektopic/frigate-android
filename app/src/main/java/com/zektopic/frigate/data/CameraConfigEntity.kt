package com.zektopic.frigate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "camera_configs")
data class CameraConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    /**
     * The `record`-role input, and the fallback for everything. Empty if utilizing
     * local device camera.
     */
    val rtspUrl: String,
    /**
     * The `detect`-role input, when the config declares one. Frigate's core trick:
     * run detection on a low-resolution substream and keep the high-resolution feed
     * for recording. Decoding 2592x1520 to produce a 640x360 detect frame is ~17x
     * more work than needed, which is the difference between two cameras and seven
     * on a low-end SoC. Empty means "no substream configured, use [rtspUrl]".
     */
    val detectRtspUrl: String = "",
    val isEnabled: Boolean = true,
    val detectWidth: Int = 640,
    val detectHeight: Int = 360,
    val fps: Int = 5,
    val recordingRetentionDays: Float = 3.0f,
    val motionThreshold: Double = 0.02 // Percent change filter
) {
    /** URL to ingest for motion/detection: the substream when configured, else the main feed. */
    val effectiveDetectUrl: String
        get() = detectRtspUrl.ifBlank { rtspUrl }
}
