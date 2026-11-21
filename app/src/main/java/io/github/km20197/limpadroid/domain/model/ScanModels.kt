package io.github.km20197.limpadroid.domain.model

enum class ScanScope {
  INFRASTRUCTURE,
  SUPERFLUOUS,
  ORPHAN,
  DUPLICATE,
  WHATSAPP,
  FULL,
}

enum class ScanStatus {
  RUNNING,
  PAUSED,
  CANCELLED,
  COMPLETED,
  FAILED,
}

data class FileEntry(
  val path: String,
  val sizeBytes: Long,
  val lastModified: Long,
  val isDirectory: Boolean,
  val isEmptyDirectory: Boolean? = null,
)

data class CategorySummary(
  val category: Category,
  val candidateCount: Int = 0,
  val totalBytes: Long = 0L,
  val latestSessionId: String? = null,
)

data class OverallCleanupOverview(
  val totalCandidatesCount: Int = 0,
  val totalCandidatesBytes: Long = 0L,
  val preselectedCount: Int = 0,
  val preselectedTrashCount: Int = 0,
  val preselectedTrashBytes: Long = 0L,
  val preselectedMediaStoreCount: Int = 0,
) {
  val hasAnyCandidates: Boolean get() = totalCandidatesCount > 0
  val hasPreselected: Boolean get() = preselectedCount > 0
  val manualReviewCount: Int get() = (totalCandidatesCount - preselectedCount).coerceAtLeast(0)
}

data class WalkError(
  val path: String,
  val errorType: String,
  val message: String,
  val timestamp: Long = System.currentTimeMillis(),
)

data class WalkCheckpoint(
  val lastProcessedPath: String? = null,
  val completedSubtrees: Set<String> = emptySet(),
  val settledFiles: Long = 0L,
  val settledDirs: Long = 0L,
  val settledBytes: Long = 0L,
)

data class ScanSession(
  val id: String,
  val scope: ScanScope,
  val status: ScanStatus,
  val rootPath: String,
  val startedAt: Long,
  val updatedAt: Long,
  val finishedAt: Long? = null,
  val scannedFiles: Long = 0L,
  val scannedDirs: Long = 0L,
  val scannedBytes: Long = 0L,
  val settledFiles: Long = 0L,
  val settledDirs: Long = 0L,
  val settledBytes: Long = 0L,
  val candidatesCount: Int = 0,
  val errorCount: Int = 0,
  val lastProcessedPath: String? = null,
  val completedSubtrees: Set<String> = emptySet(),
)

data class ScanCandidateRecord(
  val id: Long = 0L,
  val sessionId: String,
  val candidate: Candidate,
  val reason: String? = null,
  val isKept: Boolean = false,
)

sealed interface ScanEvent {
  data class Started(
    val sessionId: String,
    val resumed: Boolean,
  ) : ScanEvent

  data class Progress(
    val scannedFiles: Long,
    val scannedDirs: Long,
    val scannedBytes: Long,
    val currentPath: String?,
    val errorCount: Int,
    val checkpoint: WalkCheckpoint,
  ) : ScanEvent

  data class Found(
    val candidate: Candidate,
    val reason: String? = null,
    val isKept: Boolean = false,
  ) : ScanEvent

  data class ErrorLogged(
    val error: WalkError,
  ) : ScanEvent

  data class Checkpoint(
    val checkpoint: WalkCheckpoint,
    val scannedFiles: Long,
    val scannedDirs: Long,
    val scannedBytes: Long,
    val errorCount: Int,
  ) : ScanEvent

  data class Interrupted(
    val checkpoint: WalkCheckpoint,
    val scannedFiles: Long,
    val scannedDirs: Long,
    val scannedBytes: Long,
    val errorCount: Int,
  ) : ScanEvent

  data class Done(
    val scannedFiles: Long,
    val scannedDirs: Long,
    val scannedBytes: Long,
    val candidatesCount: Int,
    val errorCount: Int,
    val durationMs: Long,
  ) : ScanEvent
}
