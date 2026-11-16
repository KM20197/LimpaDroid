package io.github.km20197.limpadroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
  @PrimaryKey val id: String,
  val scope: String,
  val status: String,
  val rootPath: String,
  val startedAt: Long,
  val updatedAt: Long,
  val finishedAt: Long?,
  val scannedFiles: Long,
  val scannedDirs: Long,
  val scannedBytes: Long,
  val settledFiles: Long,
  val settledDirs: Long,
  val settledBytes: Long,
  val candidatesCount: Int,
  val errorCount: Int,
  val lastProcessedPath: String?,
  val completedSubtreesJson: String,
)

@Entity(
  tableName = "scan_candidates",
  foreignKeys = [
    ForeignKey(
      entity = ScanSessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["sessionId", "path"], unique = true),
  ],
)
data class ScanCandidateEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val sessionId: String,
  val path: String,
  val sizeBytes: Long,
  val lastModified: Long,
  val category: String,
  val ruleId: String,
  val confidence: String,
  val groupId: String?,
  val preselected: Boolean,
  val reason: String?,
)

@Entity(
  tableName = "scan_errors",
  foreignKeys = [
    ForeignKey(
      entity = ScanSessionEntity::class,
      parentColumns = ["id"],
      childColumns = ["sessionId"],
      onDelete = ForeignKey.CASCADE,
    ),
  ],
  indices = [
    Index(value = ["sessionId"]),
  ],
)
data class ScanErrorEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val sessionId: String,
  val path: String,
  val errorType: String,
  val message: String,
  val timestamp: Long,
)

@Entity(tableName = "hash_cache")
data class HashCacheEntity(
  @PrimaryKey val path: String,
  val sizeBytes: Long,
  val lastModified: Long,
  val sha256: String,
  val updatedAt: Long,
)
