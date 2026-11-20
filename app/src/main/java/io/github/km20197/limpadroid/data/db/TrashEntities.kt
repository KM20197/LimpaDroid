package io.github.km20197.limpadroid.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "trash_entries",
  indices = [
    Index(value = ["state", "expiresAt"]),
    Index(value = ["deletedAt"]),
  ],
)
data class TrashEntryEntity(
  @PrimaryKey val id: String,
  val originalPath: String,
  val trashPath: String,
  val sizeBytes: Long,
  val sha256: String?,
  val originalMtime: Long,
  val category: String,
  val ruleId: String,
  val deletedAt: Long,
  val expiresAt: Long,
  val state: String,
  val restoredAt: Long? = null,
  val restoredPath: String? = null,
  val purgedAt: Long? = null,
  val failureReason: String? = null,
)

@Entity(
  tableName = "operation_history",
  indices = [
    Index(value = ["timestamp"]),
  ],
)
data class OperationHistoryEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val operationType: String,
  val category: String?,
  val itemCount: Int,
  val totalBytes: Long,
  val timestamp: Long,
  val detail: String? = null,
)
