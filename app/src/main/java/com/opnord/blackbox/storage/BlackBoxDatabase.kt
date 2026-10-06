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
    @Query("UPDATE trips SET syncedAt=MAX(:at, COALESCE(endedAt, :at)) WHERE id=:id") suspend fun markSynced(id: String, at: Long)
    @Query("SELECT id FROM trips WHERE state='CLOSED' AND (syncedAt IS NULL OR syncedAt < endedAt) ORDER BY endedAt LIMIT 1") suspend fun nextClosedNeedingSync(): String?
}

@Dao interface SampleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(sample: TelemetrySampleEntity)
    @Query("SELECT COUNT(*) FROM samples") fun countFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM samples WHERE syncedAt IS NULL") fun unsyncedCountFlow(): Flow<Int>
    @Query("UPDATE samples SET syncedAt=:at WHERE id IN (:ids)") suspend fun markSynced(ids: List<String>, at: Long)
    @Query("SELECT s.tripId FROM samples AS s WHERE s.syncedAt IS NULL GROUP BY s.tripId ORDER BY MIN(s.timestamp) LIMIT 1") suspend fun nextUnsyncedTripId(): String?
    @Query("SELECT * FROM samples WHERE tripId=:tripId AND syncedAt IS NULL ORDER BY timestamp, id LIMIT :limit") suspend fun unsyncedForTrip(tripId: String, limit: Int): List<TelemetrySampleEntity>
    @Query("SELECT COUNT(*) FROM samples WHERE tripId=:tripId AND syncedAt IS NULL") suspend fun unsyncedCountForTrip(tripId: String): Int
    @Query("SELECT MAX(timestamp) FROM samples WHERE tripId=:tripId AND latitude IS NOT NULL AND longitude IS NOT NULL") suspend fun latestGpsAt(tripId: String): Long?
}

@Dao interface PendingSyncBatchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(batch: PendingSyncBatchEntity)
    @Query("SELECT * FROM pending_sync_batches ORDER BY createdAt LIMIT 1") suspend fun first(): PendingSyncBatchEntity?
    @Query("DELETE FROM pending_sync_batches WHERE batchId=:id") suspend fun delete(id: String)
}

@Dao interface TripEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(event: TripEventEntity)
    @Query("SELECT tripId FROM trip_events WHERE syncedAt IS NULL ORDER BY observedAt LIMIT 1") suspend fun nextUnsyncedTripId(): String?
    @Query("SELECT * FROM trip_events WHERE tripId=:tripId AND syncedAt IS NULL ORDER BY observedAt, eventId LIMIT :limit") suspend fun unsyncedForTrip(tripId: String, limit: Int): List<TripEventEntity>
    @Query("UPDATE trip_events SET syncedAt=:at WHERE eventId IN (:ids)") suspend fun markSynced(ids: List<String>, at: Long)
    @Query("SELECT COUNT(*) FROM trip_events WHERE tripId=:tripId AND syncedAt IS NULL") suspend fun unsyncedCountForTrip(tripId: String): Int
    @Query("SELECT * FROM trip_events WHERE tripId=:tripId AND kind='stop_start' AND stopId NOT IN (SELECT stopId FROM trip_events WHERE tripId=:tripId AND kind='stop_end') ORDER BY observedAt DESC LIMIT 1") suspend fun openStop(tripId: String): TripEventEntity?
}

@Dao interface ObdRawReplyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(reply: ObdRawReplyEntity)
    @Query("SELECT tripId FROM obd_raw_replies WHERE syncedAt IS NULL ORDER BY observedAt LIMIT 1") suspend fun nextUnsyncedTripId(): String?
    @Query("SELECT * FROM obd_raw_replies WHERE tripId=:tripId AND syncedAt IS NULL ORDER BY observedAt, replyId LIMIT :limit") suspend fun unsyncedForTrip(tripId: String, limit: Int): List<ObdRawReplyEntity>
    @Query("UPDATE obd_raw_replies SET syncedAt=:at WHERE replyId IN (:ids)") suspend fun markSynced(ids: List<String>, at: Long)
    @Query("SELECT COUNT(*) FROM obd_raw_replies WHERE tripId=:tripId AND syncedAt IS NULL") suspend fun unsyncedCountForTrip(tripId: String): Int
}

@Database(entities = [TripEntity::class, TelemetrySampleEntity::class, PendingSyncBatchEntity::class, TripEventEntity::class, ObdRawReplyEntity::class], version = 6, exportSchema = true)
abstract class BlackBoxDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun samples(): SampleDao
    abstract fun pendingBatches(): PendingSyncBatchDao
    abstract fun events(): TripEventDao
    abstract fun rawReplies(): ObdRawReplyDao
    companion object {
        @Volatile private var instance: BlackBoxDatabase? = null
        fun get(context: android.content.Context): BlackBoxDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, BlackBoxDatabase::class.java, "blackbox.db")
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .addMigrations(MIGRATION_4_5)
                .addMigrations(MIGRATION_5_6)
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
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_sync_batches ADD COLUMN eventIds TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS trip_events (eventId TEXT NOT NULL PRIMARY KEY, stopId TEXT NOT NULL, tripId TEXT NOT NULL, kind TEXT NOT NULL, observedAt INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, syncedAt INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_trip_events_tripId_observedAt ON trip_events (tripId, observedAt)")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS obd_raw_replies (replyId TEXT NOT NULL PRIMARY KEY, tripId TEXT NOT NULL, observedAt INTEGER NOT NULL, mode INTEGER NOT NULL, pid INTEGER, command TEXT NOT NULL, ecuId TEXT, responseHex TEXT NOT NULL, rawResponse TEXT NOT NULL, parseStatus TEXT NOT NULL, syncedAt INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_obd_raw_replies_tripId_observedAt ON obd_raw_replies (tripId, observedAt)")
                db.execSQL("ALTER TABLE pending_sync_batches ADD COLUMN rawReplyIds TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
