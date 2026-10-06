package com.opnord.blackbox.storage

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao interface TripDao {
    @Insert suspend fun insert(trip: TripEntity)
    @Query("SELECT * FROM trips WHERE state = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1") suspend fun active(): TripEntity?
    @Query("UPDATE trips SET endedAt=:endedAt, endReason=:reason, state='CLOSED' WHERE id=:id") suspend fun close(id: String, endedAt: Long, reason: String)
    @Query("SELECT COUNT(*) FROM trips") fun countFlow(): Flow<Int>
    @Query("SELECT * FROM trips WHERE id=:id") suspend fun byId(id: String): TripEntity?
    @Query("UPDATE trips SET syncedAt=:at WHERE id=:id") suspend fun markSynced(id: String, at: Long)
}

@Dao interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(sample: TelemetrySampleEntity)
    @Query("SELECT COUNT(*) FROM samples") fun countFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM samples WHERE syncedAt IS NULL") fun unsyncedCountFlow(): Flow<Int>
    @Query("UPDATE samples SET syncedAt=:at WHERE id IN (:ids)") suspend fun markSynced(ids: List<String>, at: Long)
    @Query("SELECT s.tripId FROM samples AS s INNER JOIN trips AS t ON t.id=s.tripId WHERE s.syncedAt IS NULL AND t.state='CLOSED' GROUP BY s.tripId ORDER BY MIN(s.timestamp) LIMIT 1") suspend fun nextUnsyncedTripId(): String?
    @Query("SELECT * FROM samples WHERE tripId=:tripId AND syncedAt IS NULL ORDER BY timestamp, id LIMIT :limit") suspend fun unsyncedForTrip(tripId: String, limit: Int): List<TelemetrySampleEntity>
    @Query("SELECT COUNT(*) FROM samples WHERE tripId=:tripId AND syncedAt IS NULL") suspend fun unsyncedCountForTrip(tripId: String): Int
}

@Dao interface PendingSyncBatchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(batch: PendingSyncBatchEntity)
    @Query("SELECT * FROM pending_sync_batches ORDER BY createdAt LIMIT 1") suspend fun first(): PendingSyncBatchEntity?
    @Query("DELETE FROM pending_sync_batches WHERE batchId=:id") suspend fun delete(id: String)
}

@Database(entities = [TripEntity::class, TelemetrySampleEntity::class, PendingSyncBatchEntity::class], version = 4, exportSchema = true)
abstract class BlackBoxDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun samples(): SampleDao
    abstract fun pendingBatches(): PendingSyncBatchDao
    companion object {
        @Volatile private var instance: BlackBoxDatabase? = null
        fun get(context: android.content.Context): BlackBoxDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, BlackBoxDatabase::class.java, "blackbox.db")
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build().also { instance = it }
        }
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS pending_sync_batches (batchId TEXT NOT NULL PRIMARY KEY, tripId TEXT NOT NULL, payload TEXT NOT NULL, sampleIds TEXT NOT NULL, createdAt INTEGER NOT NULL)")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("obdRpm", "obdSpeedKmh", "obdEngineLoadPct", "obdThrottlePct", "obdCoolantC", "obdIntakeTempC", "obdMafGps", "obdFuelLevelPct", "obdVoltageV")
                    .forEach { db.execSQL("ALTER TABLE samples ADD COLUMN `$it` REAL") }
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE samples ADD COLUMN `obdValuesJson` TEXT NOT NULL DEFAULT '{}' ")
            }
        }
    }
}
