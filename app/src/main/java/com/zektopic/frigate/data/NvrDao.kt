package com.zektopic.frigate.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NvrDao {

    // Camera Configurations Queries
    @Query("SELECT * FROM camera_configs")
    fun getAllCameraConfigsFlow(): Flow<List<CameraConfigEntity>>

    @Query("SELECT * FROM camera_configs")
    suspend fun getAllCameraConfigs(): List<CameraConfigEntity>

    @Query("SELECT * FROM camera_configs WHERE id = :id")
    suspend fun getCameraConfigById(id: String): CameraConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCameraConfig(config: CameraConfigEntity)

    @Delete
    suspend fun deleteCameraConfig(config: CameraConfigEntity)

    @Query("DELETE FROM camera_configs")
    suspend fun deleteAllCameraConfigs()

    /**
     * Atomically replace the whole configuration. Room fires invalidation only
     * after the transaction commits, so observers of the camera/system-config
     * flows see old→new and never a momentary empty list (which the service
     * would otherwise treat as "stop all streams").
     */
    @Transaction
    suspend fun applyConfig(systemConfig: SystemConfigEntity, cameras: List<CameraConfigEntity>) {
        insertSystemConfig(systemConfig)
        deleteAllCameraConfigs()
        for (camera in cameras) insertCameraConfig(camera)
    }

    // Events Queries
    @Query("SELECT * FROM nvr_events ORDER BY timestamp DESC")
    fun getAllEventsFlow(): Flow<List<EventEntity>>

    @Query("SELECT * FROM nvr_events ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPagedEvents(limit: Int, offset: Int): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity): Long

    @Query("DELETE FROM nvr_events WHERE timestamp < :expirationTimestamp")
    suspend fun deleteEventsOlderThan(expirationTimestamp: Long)

    @Query("DELETE FROM nvr_events")
    suspend fun deleteAllEvents()

    @Query("UPDATE nvr_events SET videoPath = :videoPath WHERE id = :eventId")
    suspend fun updateEventVideoPath(eventId: Long, videoPath: String)

    /**
     * Detach events from files the janitor has deleted.
     *
     * By ref rather than by timestamp: for a clip published into a SAF tree the file's
     * lastModified is the provider's publish time while [EventEntity.timestamp] is when
     * motion fired, so the two orderings diverge — a time cutoff would both strand rows
     * pointing at deleted files and drop rows whose files are still there.
     *
     * Chunk the refs: these bind one variable per entry and SQLite caps that.
     */
    @Query("UPDATE nvr_events SET videoPath = NULL WHERE videoPath IN (:refs)")
    suspend fun clearEventVideoPaths(refs: List<String>)

    @Query("UPDATE nvr_events SET snapshotPath = NULL WHERE snapshotPath IN (:refs)")
    suspend fun clearEventSnapshotPaths(refs: List<String>)

    /** Drop rows left with neither a clip nor a snapshot — nothing to show, nothing to play. */
    @Query("DELETE FROM nvr_events WHERE videoPath IS NULL AND snapshotPath IS NULL")
    suspend fun deleteEventsWithoutMedia()

    @Query("SELECT COUNT(*) FROM nvr_events")
    fun getEventCountFlow(): Flow<Long>

    // System Config Queries
    @Query("SELECT * FROM system_config WHERE id = 1")
    fun getSystemConfigFlow(): Flow<SystemConfigEntity?>

    @Query("SELECT * FROM system_config WHERE id = 1")
    suspend fun getSystemConfig(): SystemConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSystemConfig(config: SystemConfigEntity)
}
