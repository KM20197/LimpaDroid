package io.github.km20197.limpadroid.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
  entities = [
    ScanSessionEntity::class,
    ScanCandidateEntity::class,
    ScanErrorEntity::class,
    HashCacheEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
abstract class LimpaDroidDatabase : RoomDatabase() {
  abstract fun scanSessionDao(): ScanSessionDao
  abstract fun scanCandidateDao(): ScanCandidateDao
  abstract fun scanErrorDao(): ScanErrorDao
  abstract fun hashCacheDao(): HashCacheDao

  companion object {
    @Volatile
    private var instance: LimpaDroidDatabase? = null

    fun getInstance(context: Context): LimpaDroidDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
          context.applicationContext,
          LimpaDroidDatabase::class.java,
          "limpadroid.db",
        ).build().also { db ->
          instance = db
        }
      }
  }
}
