package io.github.km20197.limpadroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface KnownAppDao {
  @Query("SELECT * FROM known_apps ORDER BY packageName ASC")
  suspend fun getAll(): List<KnownAppEntity>

  @Query("SELECT * FROM known_apps WHERE removedAt IS NOT NULL ORDER BY removedAt DESC, packageName ASC")
  suspend fun getRemoved(): List<KnownAppEntity>

  @Query("SELECT * FROM known_apps ORDER BY packageName ASC")
  fun observeAll(): Flow<List<KnownAppEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(entries: List<KnownAppEntity>)
}
