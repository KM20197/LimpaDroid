package io.github.km20197.limpadroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrashEntryDao {
  @Query("SELECT * FROM trash_entries WHERE state = 'IN_TRASH' ORDER BY deletedAt DESC")
  fun observeInTrashEntries(): Flow<List<TrashEntryEntity>>

  @Query("SELECT * FROM trash_entries WHERE state = 'PURGED'")
  fun observePurgedEntries(): Flow<List<TrashEntryEntity>>

  @Query("SELECT * FROM trash_entries WHERE id = :id")
  suspend fun getById(id: String): TrashEntryEntity?

  @Query("SELECT * FROM trash_entries WHERE state = :state ORDER BY deletedAt DESC")
  suspend fun getByState(state: String): List<TrashEntryEntity>

  @Query("SELECT * FROM trash_entries WHERE state = 'IN_TRASH' AND expiresAt <= :nowMillis")
  suspend fun getExpiredEntries(nowMillis: Long): List<TrashEntryEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entry: TrashEntryEntity)

  @Query(
    """
    UPDATE trash_entries
    SET state = :state,
        trashPath = :trashPath,
        sizeBytes = :sizeBytes,
        sha256 = :sha256,
        originalMtime = :originalMtime,
        failureReason = :failureReason
    WHERE id = :id
    """,
  )
  suspend fun updateMovedState(
    id: String,
    state: String,
    trashPath: String,
    sizeBytes: Long,
    sha256: String?,
    originalMtime: Long,
    failureReason: String?,
  )

  @Query(
    """
    UPDATE trash_entries
    SET state = :state,
        restoredAt = :restoredAt,
        restoredPath = :restoredPath,
        purgedAt = :purgedAt,
        failureReason = :failureReason
    WHERE id = :id
    """,
  )
  suspend fun updateLifecycleState(
    id: String,
    state: String,
    restoredAt: Long?,
    restoredPath: String?,
    purgedAt: Long?,
    failureReason: String?,
  )
}

@Dao
interface OperationHistoryDao {
  @Query("SELECT * FROM operation_history ORDER BY timestamp DESC, id DESC LIMIT :limit")
  fun observeRecent(limit: Int = 200): Flow<List<OperationHistoryEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(record: OperationHistoryEntity): Long
}
