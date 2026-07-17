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

    // Events Queries
    @Query("SELECT * FROM nvr_events ORDER BY timestamp DESC")
    fun getAllEventsFlow(): Flow<List<EventEntity>>

    @Query("SELECT * FROM nvr_events ORDER BY timestamp DESC LIMIT :limit OFFSET :offset")
    suspend fun getPagedEvents(limit: Int, offset: Int): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity): Long

    @Query("DELETE FROM nvr_events WHERE timestamp < :expirationTimestamp")
    suspend fun deleteEventsOlderThan(expirationTimestamp: Long)

    @Query("UPDATE nvr_events SET videoPath = :videoPath WHERE id = :eventId")
    suspend fun updateEventVideoPath(eventId: Long, videoPath: String)

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
