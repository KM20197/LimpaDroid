package io.github.km20197.limpadroid.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {
  @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC LIMIT 1")
  fun observeLatestSession(): Flow<ScanSessionEntity?>

  @Query("SELECT * FROM scan_sessions WHERE id = :sessionId")
  fun observeSession(sessionId: String): Flow<ScanSessionEntity?>

  @Query("SELECT * FROM scan_sessions WHERE id = :sessionId")
  suspend fun getSession(sessionId: String): ScanSessionEntity?

  @Query("SELECT * FROM scan_sessions ORDER BY startedAt DESC LIMIT 1")
  suspend fun getLatestSession(): ScanSessionEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(session: ScanSessionEntity)

  @Query(
    """
    UPDATE scan_sessions
    SET scannedFiles = :scannedFiles,
        scannedDirs = :scannedDirs,
        scannedBytes = :scannedBytes,
        settledFiles = :settledFiles,
        settledDirs = :settledDirs,
        settledBytes = :settledBytes,
        candidatesCount = :candidatesCount,
        errorCount = :errorCount,
        lastProcessedPath = :lastProcessedPath,
        completedSubtreesJson = :completedSubtreesJson,
        updatedAt = :updatedAt
    WHERE id = :sessionId
    """,
  )
  suspend fun updateProgress(
    sessionId: String,
    scannedFiles: Long,
    scannedDirs: Long,
    scannedBytes: Long,
    settledFiles: Long,
    settledDirs: Long,
    settledBytes: Long,
    candidatesCount: Int,
    errorCount: Int,
    lastProcessedPath: String?,
    completedSubtreesJson: String,
    updatedAt: Long,
  )

  @Query(
    """
    UPDATE scan_sessions
    SET status = :status,
        finishedAt = :finishedAt,
        updatedAt = :updatedAt
    WHERE id = :sessionId
    """,
  )
  suspend fun updateStatus(
    sessionId: String,
    status: String,
    finishedAt: Long?,
    updatedAt: Long,
  )

  @Query(
    """
    UPDATE scan_sessions
    SET status = :status,
        scannedFiles = :scannedFiles,
        scannedDirs = :scannedDirs,
        scannedBytes = :scannedBytes,
        settledFiles = :settledFiles,
        settledDirs = :settledDirs,
        settledBytes = :settledBytes,
        candidatesCount = :candidatesCount,
        errorCount = :errorCount,
        lastProcessedPath = :lastProcessedPath,
        completedSubtreesJson = :completedSubtreesJson,
        finishedAt = :finishedAt,
        updatedAt = :updatedAt
    WHERE id = :sessionId
    """,
  )
  suspend fun updateTerminalOrCheckpointState(
    sessionId: String,
    status: String,
    scannedFiles: Long,
    scannedDirs: Long,
    scannedBytes: Long,
    settledFiles: Long,
    settledDirs: Long,
    settledBytes: Long,
    candidatesCount: Int,
    errorCount: Int,
    lastProcessedPath: String?,
    completedSubtreesJson: String,
    finishedAt: Long?,
    updatedAt: Long,
  )

  @Query("DELETE FROM scan_sessions WHERE id = :sessionId")
  suspend fun deleteById(sessionId: String)
}

@Dao
interface ScanCandidateDao {
  @Query("SELECT * FROM scan_candidates WHERE sessionId = :sessionId ORDER BY id ASC")
  fun observeForSession(sessionId: String): Flow<List<ScanCandidateEntity>>

  @Query("SELECT COUNT(*) FROM scan_candidates WHERE sessionId = :sessionId")
  suspend fun countForSession(sessionId: String): Int

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(candidates: List<ScanCandidateEntity>)

  @Query("DELETE FROM scan_candidates WHERE sessionId = :sessionId")
  suspend fun deleteForSession(sessionId: String)
}

@Dao
interface ScanErrorDao {
  @Query("SELECT * FROM scan_errors WHERE sessionId = :sessionId ORDER BY timestamp DESC, id DESC LIMIT :limit")
  fun observeRecentForSession(sessionId: String, limit: Int = 200): Flow<List<ScanErrorEntity>>

  @Query("SELECT * FROM scan_errors WHERE sessionId = :sessionId ORDER BY id ASC")
  suspend fun getAllForSession(sessionId: String): List<ScanErrorEntity>

  @Query("SELECT COUNT(*) FROM scan_errors WHERE sessionId = :sessionId")
  suspend fun countForSession(sessionId: String): Int

  @Insert(onConflict = OnConflictStrategy.IGNORE)
  suspend fun insertAll(errors: List<ScanErrorEntity>)

  @Query("DELETE FROM scan_errors WHERE sessionId = :sessionId")
  suspend fun deleteForSession(sessionId: String)
}

@Dao
interface HashCacheDao {
  @Query("SELECT * FROM hash_cache WHERE path = :path")
  suspend fun getByPath(path: String): HashCacheEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(entry: HashCacheEntity)

  @Query("DELETE FROM hash_cache WHERE path = :path")
  suspend fun deleteByPath(path: String)
}
