package com.zektopic.frigate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nvr_events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cameraId: String,
    val label: String,       // e.g. "person", "car"
    val confidence: Float,   // e.g. 0.89
    val timestamp: Long,     // Epoch timestamp in milliseconds
    val snapshotPath: String?, // Path to local JPG image file
    val videoPath: String?,    // Path to local MP4 recording segment
    val zone: String?         // Name of the active zone triggered
)
