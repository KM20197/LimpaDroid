package io.github.km20197.limpadroid.trash

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.km20197.limpadroid.domain.model.BatchTrashMoveSummary
import io.github.km20197.limpadroid.domain.model.MediaStoreRepairSummary
import io.github.km20197.limpadroid.domain.model.OperationHistoryRecord
import io.github.km20197.limpadroid.domain.model.PurgeSummary
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.domain.model.TrashRestoreResult
import io.github.km20197.limpadroid.domain.model.TrashStats
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CleanupBatchResult(
  val movedCount: Int,
  val movedBytes: Long,
  val repairedMediaStoreCount: Int,
  val skippedOrFailedCount: Int,
  val cleanedCandidateIds: List<Long> = emptyList(),
  val candidatesAlreadyRemovedFromRepo: Boolean = false,
  val failed: Boolean = false,
  val errorMessage: String? = null,
)

interface TrashController {
  val inTrashEntries: Flow<List<TrashEntry>>
  val trashStats: Flow<TrashStats>
  val recentHistory: Flow<List<OperationHistoryRecord>>
  val isBatchCleanupRunning: Flow<Boolean>
    get() = flowOf(false)

  suspend fun reconcileAndPurgeOnStartup()
  suspend fun restoreEntry(entryId: String): TrashRestoreResult
  suspend fun deletePermanently(entryId: String): PurgeSummary
  suspend fun emptyTrash(): PurgeSummary

  suspend fun moveCandidatesToTrash(
    records: List<ScanCandidateRecord>,
    userExcludedPaths: Set<Path> = emptySet(),
  ): BatchTrashMoveSummary = BatchTrashMoveSummary(emptyList(), 0L, 0, 0)

  suspend fun repairStaleMediaStore(
    records: List<ScanCandidateRecord>,
    userExcludedPaths: Set<Path> = emptySet(),
  ): MediaStoreRepairSummary = MediaStoreRepairSummary(emptyList(), 0)

  suspend fun runBatchCleanup(
    sessionId: String?,
    records: List<ScanCandidateRecord>,
    userExcludedPaths: Set<Path> = emptySet(),
  ): CleanupBatchResult {
    if (records.isEmpty()) {
      return CleanupBatchResult(
        movedCount = 0,
        movedBytes = 0L,
        repairedMediaStoreCount = 0,
        skippedOrFailedCount = 0,
      )
    }
    val staleRecords = records.filter {
      it.candidate.ruleId == SuperfluousRuleId.STALE_MEDIASTORE.ruleId
    }
    val trashRecords = records.filter {
      it.candidate.ruleId != SuperfluousRuleId.STALE_MEDIASTORE.ruleId
    }
    val trashSummary = moveCandidatesToTrash(
      records = trashRecords,
      userExcludedPaths = userExcludedPaths,
    )
    val repairSummary = repairStaleMediaStore(
      records = staleRecords,
      userExcludedPaths = userExcludedPaths,
    )
    return CleanupBatchResult(
      movedCount = trashSummary.movedCandidateIds.size,
      movedBytes = trashSummary.movedBytes,
      repairedMediaStoreCount = repairSummary.repairedCandidateIds.size,
      skippedOrFailedCount = trashSummary.staleOrSkippedCount +
        trashSummary.failedCount +
        repairSummary.rejectedOrExistingCount,
      cleanedCandidateIds = trashSummary.movedCandidateIds + repairSummary.repairedCandidateIds,
      candidatesAlreadyRemovedFromRepo = false,
    )
  }
}

class WorkManagerTrashController(
  private val context: Context,
  private val trashRepository: TrashRepository,
  private val cleanupJobsDir: File = File(context.filesDir, CLEANUP_JOBS_DIR_NAME),
) : TrashController {

  private val json = Json { ignoreUnknownKeys = true }

  override val inTrashEntries: Flow<List<TrashEntry>> = trashRepository.inTrashEntries
  override val trashStats: Flow<TrashStats> = trashRepository.trashStats
  override val recentHistory: Flow<List<OperationHistoryRecord>> = trashRepository.recentHistory

  override val isBatchCleanupRunning: Flow<Boolean> by lazy {
    runCatching {
      WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkFlow(UNIQUE_CLEANUP_WORK_NAME)
        .map { infos -> infos.any { !it.state.isFinished } }
    }.getOrDefault(flowOf(false))
  }

  override suspend fun reconcileAndPurgeOnStartup() {
    schedulePeriodicPurge()
    trashRepository.reconcilePendingAndRebuildIndex()
    trashRepository.purgeExpired()
  }

  override suspend fun restoreEntry(entryId: String): TrashRestoreResult =
    trashRepository.restoreEntry(entryId)

  override suspend fun deletePermanently(entryId: String): PurgeSummary =
    trashRepository.deletePermanently(entryId)

  override suspend fun emptyTrash(): PurgeSummary =
    trashRepository.emptyTrash()

  override suspend fun moveCandidatesToTrash(
    records: List<ScanCandidateRecord>,
    userExcludedPaths: Set<Path>,
  ): BatchTrashMoveSummary =
    trashRepository.moveCandidatesToTrash(records = records, userExcludedPaths = userExcludedPaths)

  override suspend fun repairStaleMediaStore(
    records: List<ScanCandidateRecord>,
    userExcludedPaths: Set<Path>,
  ): MediaStoreRepairSummary =
    trashRepository.repairStaleMediaStore(records = records, userExcludedPaths = userExcludedPaths)

  override suspend fun runBatchCleanup(
    sessionId: String?,
    records: List<ScanCandidateRecord>,
    userExcludedPaths: Set<Path>,
  ): CleanupBatchResult {
    if (records.isEmpty()) {
      return CleanupBatchResult(
        movedCount = 0,
        movedBytes = 0L,
        repairedMediaStoreCount = 0,
        skippedOrFailedCount = 0,
      )
    }

    val jobId = UUID.randomUUID().toString()
    val payload = CleanupJobPayload(
      sessionId = sessionId,
      candidateIds = records.map { it.id },
      excludedPaths = userExcludedPaths.map { it.toString() },
    )
    val jobFile = File(cleanupJobsDir, "$jobId.json")
    withContext(Dispatchers.IO) {
      cleanupJobsDir.mkdirs()
      jobFile.writeText(json.encodeToString(payload), Charsets.UTF_8)
    }

    val request = OneTimeWorkRequestBuilder<TrashCleanupWorker>()
      .setInputData(workDataOf(TrashCleanupWorker.KEY_JOB_ID to jobId))
      .addTag(UNIQUE_CLEANUP_WORK_NAME)
      .build()

    val workManager = try {
      WorkManager.getInstance(context).also { wm ->
        wm.enqueueUniqueWork(
          UNIQUE_CLEANUP_WORK_NAME,
          ExistingWorkPolicy.APPEND_OR_REPLACE,
          request,
        )
      }
    } catch (ce: CancellationException) {
      withContext(Dispatchers.IO) { runCatching { jobFile.delete() } }
      throw ce
    } catch (t: Throwable) {
      withContext(Dispatchers.IO) { runCatching { jobFile.delete() } }
      return CleanupBatchResult(
        movedCount = 0,
        movedBytes = 0L,
        repairedMediaStoreCount = 0,
        skippedOrFailedCount = records.size,
        failed = true,
        errorMessage = t.message ?: t.javaClass.simpleName,
      )
    }

    val finalInfo = workManager
      .getWorkInfoByIdFlow(request.id)
      .first { info -> info != null && info.state.isFinished }
      ?: return CleanupBatchResult(
        movedCount = 0,
        movedBytes = 0L,
        repairedMediaStoreCount = 0,
        skippedOrFailedCount = records.size,
        candidatesAlreadyRemovedFromRepo = true,
        failed = true,
      )

    return if (finalInfo.state == WorkInfo.State.SUCCEEDED) {
      val out = finalInfo.outputData
      CleanupBatchResult(
        movedCount = out.getInt(TrashCleanupWorker.KEY_MOVED_COUNT, 0),
        movedBytes = out.getLong(TrashCleanupWorker.KEY_MOVED_BYTES, 0L),
        repairedMediaStoreCount = out.getInt(TrashCleanupWorker.KEY_REPAIRED_COUNT, 0),
        skippedOrFailedCount = out.getInt(TrashCleanupWorker.KEY_SKIPPED_OR_FAILED_COUNT, 0),
        candidatesAlreadyRemovedFromRepo = true,
      )
    } else {
      val errMsg = finalInfo.outputData.getString(TrashCleanupWorker.KEY_ERROR_MESSAGE)
      CleanupBatchResult(
        movedCount = 0,
        movedBytes = 0L,
        repairedMediaStoreCount = 0,
        skippedOrFailedCount = records.size,
        candidatesAlreadyRemovedFromRepo = true,
        failed = true,
        errorMessage = errMsg,
      )
    }
  }

  private fun schedulePeriodicPurge() {
    val constraints = Constraints.Builder()
      .setRequiresBatteryNotLow(true)
      .build()

    val request = PeriodicWorkRequestBuilder<PurgeWorker>(24L, TimeUnit.HOURS)
      .setConstraints(constraints)
      .build()

    runCatching {
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE_PURGE_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
      )
    }
  }

  companion object {
    const val UNIQUE_PURGE_WORK_NAME = "limpadroid_trash_purge"
    const val UNIQUE_CLEANUP_WORK_NAME = "limpadroid_trash_cleanup"
    const val CLEANUP_JOBS_DIR_NAME = "cleanup_jobs"
  }
}
