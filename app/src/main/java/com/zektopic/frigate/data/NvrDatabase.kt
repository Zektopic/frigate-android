package com.zektopic.frigate.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [CameraConfigEntity::class, EventEntity::class, SystemConfigEntity::class], version = 4, exportSchema = false)
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

        /**
         * Review state for events: severity, the reviewed flag and an end time.
         *
         * Also clears two values earlier builds invented. Every event was stamped
         * with the zone "Main Zone" although no zone existed, and the test
         * notification button logged a "person, 96%, Gate Zone" event that never
         * happened. Neither should survive into a Review page that filters by zone.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE nvr_events ADD COLUMN severity TEXT NOT NULL DEFAULT 'alert'")
                db.execSQL("ALTER TABLE nvr_events ADD COLUMN hasBeenReviewed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE nvr_events ADD COLUMN endTime INTEGER")
                // Everything recorded before this version has long since finished.
                db.execSQL("UPDATE nvr_events SET endTime = timestamp")
                db.execSQL(
                    "DELETE FROM nvr_events WHERE label = 'person' AND zone = 'Gate Zone' " +
                        "AND snapshotPath IS NULL AND videoPath IS NULL"
                )
                db.execSQL("UPDATE nvr_events SET zone = NULL WHERE zone = 'Main Zone'")
            }
        }

        fun getDatabase(context: Context): NvrDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NvrDatabase::class.java,
                    "frigate_nvr_database"
                )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
