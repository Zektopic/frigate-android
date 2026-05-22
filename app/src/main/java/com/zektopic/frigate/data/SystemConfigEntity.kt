package com.zektopic.frigate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "system_config")
data class SystemConfigEntity(
    @PrimaryKey val id: Int = 1,
    val configYaml: String
)
