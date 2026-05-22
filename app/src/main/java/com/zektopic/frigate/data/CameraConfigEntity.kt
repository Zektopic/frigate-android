package com.zektopic.frigate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "camera_configs")
data class CameraConfigEntity(
    @PrimaryKey val id: String,
    val name: String,
    val rtspUrl: String, // Empty if utilizing local device camera
    val isEnabled: Boolean = true,
    val detectWidth: Int = 640,
    val detectHeight: Int = 360,
    val fps: Int = 5,
    val recordingRetentionDays: Float = 3.0f,
    val motionThreshold: Double = 0.02 // Percent change filter
)
