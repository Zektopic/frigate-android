package com.zektopic.frigate.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CameraConfigEntity::class, EventEntity::class, SystemConfigEntity::class], version = 3, exportSchema = false)
abstract class NvrDatabase : RoomDatabase() {

    abstract fun nvrDao(): NvrDao

    companion object {
        @Volatile
        private var INSTANCE: NvrDatabase? = null

        /**
         * Adds `detectRtspUrl` for substream support. Written by hand rather than
         * relying on `fallbackToDestructiveMigration`, which was previously in place
         * and would have silently deleted every camera config and the entire event
         * history on this bump - then re-seeded a hardcoded default config over the
         * top, making the loss look like a reset rather than a bug.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE camera_configs ADD COLUMN detectRtspUrl TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        fun getDatabase(context: Context): NvrDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NvrDatabase::class.java,
                    "frigate_nvr_database"
                )
                .addMigrations(MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
