package com.zektopic.frigate.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CameraConfigEntity::class, EventEntity::class, SystemConfigEntity::class], version = 2, exportSchema = false)
abstract class NvrDatabase : RoomDatabase() {

    abstract fun nvrDao(): NvrDao

    companion object {
        @Volatile
        private var INSTANCE: NvrDatabase? = null

        fun getDatabase(context: Context): NvrDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NvrDatabase::class.java,
                    "frigate_nvr_database"
                )
                .fallbackToDestructiveMigration() // Simplicity for local developments
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
