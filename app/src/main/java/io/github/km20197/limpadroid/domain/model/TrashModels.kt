package io.github.km20197.limpadroid.domain.model

import kotlinx.serialization.Serializable

enum class TrashEntryState {
  PENDING,
  IN_TRASH,
  RESTORED,
  PURGED,
  FAILED,
}

enum class OperationType {
  MOVE_TO_TRASH,
  RESTORE,
  PURGE_EXPIRED,
  PURGE_MANUAL,
  EMPTY_TRASH,
  MEDIASTORE_REPAIR,
}

data class TrashEntry(
  val id: String,
  val originalPath: String,
  val trashPath: String,
  val sizeBytes: Long,
  val sha256: String?,
  val originalMtime: Long,
  val category: Category,
  val ruleId: String,
  val deletedAt: Long,
  val expiresAt: Long,
  val state: TrashEntryState,
  val restoredAt: Long? = null,
  val restoredPath: String? = null,
  val purgedAt: Long? = null,
  val failureReason: String? = null,
) {
  fun remainingDays(nowMillis: Long): Int {
    val diff = expiresAt - nowMillis
    if (diff <= 0L) return 0
    return ((diff + MILLIS_PER_DAY - 1L) / MILLIS_PER_DAY).toInt()
  }

  companion object {
    const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    const val RETENTION_DAYS = 30L
    const val RETENTION_MILLIS = RETENTION_DAYS * MILLIS_PER_DAY
  }
}

data class TrashStats(
  val inTrashCount: Int = 0,
  val inTrashBytes: Long = 0L,
  val expiringSoonCount: Int = 0,
  val purgedCount: Int = 0,
  val freedBytes: Long = 0L,
  val systemTrashCount: Int = 0,
  val systemTrashBytes: Long = 0L,
)

data class OperationHistoryRecord(
  val id: Long = 0L,
  val operationType: OperationType,
  val category: Category?,
  val itemCount: Int,
  val totalBytes: Long,
  val timestamp: Long,
  val detail: String? = null,
)

@Serializable
data class TrashMetadataPayload(
  val id: String,
  val originalPath: String,
  val trashPath: String,
  val sizeBytes: Long,
  val sha256: String? = null,
  val originalMtime: Long,
  val category: String,
  val ruleId: String,
  val deletedAt: Long,
  val expiresAt: Long,
)

fun interface AppClock {
  fun nowMillis(): Long

  companion object {
    val System: AppClock = AppClock { java.lang.System.currentTimeMillis() }
  }
}

sealed interface TrashMoveResult {
  data class Moved(val entry: TrashEntry) : TrashMoveResult
  data class Rejected(val reason: String) : TrashMoveResult
  data class StaleCandidate(val reason: String) : TrashMoveResult
  data class Failed(val reason: String) : TrashMoveResult
}

sealed interface TrashRestoreResult {
  data class Restored(val entry: TrashEntry, val restoredPath: String) : TrashRestoreResult
  data class MissingPayload(val reason: String) : TrashRestoreResult
  data class Rejected(val reason: String) : TrashRestoreResult
  data class Failed(val reason: String) : TrashRestoreResult
}

data class PurgeSummary(
  val purgedCount: Int,
  val freedBytes: Long,
)

data class BatchTrashMoveSummary(
  val movedCandidateIds: List<Long>,
  val movedBytes: Long,
  val staleOrSkippedCount: Int,
  val failedCount: Int,
)

data class MediaStoreRepairSummary(
  val repairedCandidateIds: List<Long>,
  val rejectedOrExistingCount: Int,
)
