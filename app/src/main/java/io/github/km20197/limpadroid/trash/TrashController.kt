package io.github.km20197.limpadroid.trash

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.km20197.limpadroid.domain.model.BatchTrashMoveSummary
import io.github.km20197.limpadroid.domain.model.MediaStoreRepairSummary
import io.github.km20197.limpadroid.domain.model.OperationHistoryRecord
import io.github.km20197.limpadroid.domain.model.PurgeSummary
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.domain.model.TrashRestoreResult
import io.github.km20197.limpadroid.domain.model.TrashStats
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow

interface TrashController {
  val inTrashEntries: Flow<List<TrashEntry>>
  val trashStats: Flow<TrashStats>
  val recentHistory: Flow<List<OperationHistoryRecord>>

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
}

class WorkManagerTrashController(
  private val context: Context,
  private val trashRepository: TrashRepository,
) : TrashController {

  override val inTrashEntries: Flow<List<TrashEntry>> = trashRepository.inTrashEntries
  override val trashStats: Flow<TrashStats> = trashRepository.trashStats
  override val recentHistory: Flow<List<OperationHistoryRecord>> = trashRepository.recentHistory

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
  }
}
