package com.zektopic.frigate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nvr_events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cameraId: String,
    val label: String,       // "motion" — the only label this app produces
    val confidence: Float,
    val timestamp: Long,     // Epoch timestamp in milliseconds
    val snapshotPath: String?, // Path to local JPG image file
    val videoPath: String?,    // Path to local MP4 recording segment
    /** Zones the motion fell in, comma-separated, or null when it was in none. */
    val zone: String?,
    /** [ReviewSeverity.key]: how the event is filed on the Review page. */
    val severity: String = ReviewSeverity.ALERT.key,
    val hasBeenReviewed: Boolean = false,
    /** When the event's recording finished; null while it is still in progress. */
    val endTime: Long? = null
) {
    val zones: List<String>
        get() = zone?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    val reviewSeverity: ReviewSeverity
        get() = ReviewSeverity.fromKey(severity)
}
