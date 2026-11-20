package io.github.km20197.limpadroid.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
  entities = [
    ScanSessionEntity::class,
    ScanCandidateEntity::class,
    ScanErrorEntity::class,
    HashCacheEntity::class,
    TrashEntryEntity::class,
    OperationHistoryEntity::class,
    KnownAppEntity::class,
  ],
  version = 4,
  exportSchema = false,
)
abstract class LimpaDroidDatabase : RoomDatabase() {
  abstract fun scanSessionDao(): ScanSessionDao
  abstract fun scanCandidateDao(): ScanCandidateDao
  abstract fun scanErrorDao(): ScanErrorDao
  abstract fun hashCacheDao(): HashCacheDao
  abstract fun trashEntryDao(): TrashEntryDao
  abstract fun operationHistoryDao(): OperationHistoryDao
  abstract fun knownAppDao(): KnownAppDao

  companion object {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `trash_entries` (
            `id` TEXT NOT NULL,
            `originalPath` TEXT NOT NULL,
            `trashPath` TEXT NOT NULL,
            `sizeBytes` INTEGER NOT NULL,
            `sha256` TEXT,
            `originalMtime` INTEGER NOT NULL,
            `category` TEXT NOT NULL,
            `ruleId` TEXT NOT NULL,
            `deletedAt` INTEGER NOT NULL,
            `expiresAt` INTEGER NOT NULL,
            `state` TEXT NOT NULL,
            `restoredAt` INTEGER,
            `restoredPath` TEXT,
            `purgedAt` INTEGER,
            `failureReason` TEXT,
            PRIMARY KEY(`id`)
          )
          """.trimIndent(),
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_trash_entries_state_expiresAt` ON `trash_entries` (`state`, `expiresAt`)",
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_trash_entries_deletedAt` ON `trash_entries` (`deletedAt`)",
        )
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `operation_history` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `operationType` TEXT NOT NULL,
            `category` TEXT,
            `itemCount` INTEGER NOT NULL,
            `totalBytes` INTEGER NOT NULL,
            `timestamp` INTEGER NOT NULL,
            `detail` TEXT
          )
          """.trimIndent(),
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_operation_history_timestamp` ON `operation_history` (`timestamp`)",
        )
      }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
          """
          CREATE TABLE IF NOT EXISTS `known_apps` (
            `packageName` TEXT NOT NULL,
            `label` TEXT NOT NULL,
            `firstSeen` INTEGER NOT NULL,
            `lastSeen` INTEGER NOT NULL,
            `removedAt` INTEGER,
            PRIMARY KEY(`packageName`)
          )
          """.trimIndent(),
        )
        db.execSQL(
          "CREATE INDEX IF NOT EXISTS `index_known_apps_removedAt` ON `known_apps` (`removedAt`)",
        )
      }
    }

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `scan_candidates` ADD COLUMN `isKept` INTEGER NOT NULL DEFAULT 0")
      }
    }

    @Volatile
    private var instance: LimpaDroidDatabase? = null

    fun getInstance(context: Context): LimpaDroidDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          LimpaDroidDatabase::class.java,
          "limpadroid.db",
        )
          .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
          .build()
          .also { db ->
            instance = db
          }
      }
  }
}
