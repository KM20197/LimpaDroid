package io.github.km20197.limpadroid.trash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.km20197.limpadroid.LimpaDroidApp
import io.github.km20197.limpadroid.R
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.data.scan.ScanRepository
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import java.io.File
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class TrashCleanupDependencies(
  val trashRepository: TrashRepository,
  val scanRepository: ScanRepository,
  val scanLogger: ScanLogger,
  val cleanupJobsDir: File,
)

@Serializable
data class CleanupJobPayload(
  val sessionId: String? = null,
  val candidateIds: List<Long>,
  val excludedPaths: List<String> = emptyList(),
)

class TrashCleanupWorker(
  appContext: Context,
  params: WorkerParameters,
  private val runtimeDependencies: TrashCleanupDependencies? = null,
) : CoroutineWorker(appContext, params) {

  constructor(
    appContext: Context,
    params: WorkerParameters,
  ) : this(appContext, params, null)

  override suspend fun doWork(): Result {
    val deps = resolveDependencies() ?: return Result.failure(
      workDataOf(KEY_ERROR_MESSAGE to "Dependências de limpeza indisponíveis."),
    )
    val jobId = inputData.getString(KEY_JOB_ID)?.trim().orEmpty()
    if (jobId.isEmpty() || jobId.contains('/') || jobId.contains('\\') || jobId.contains("..")) {
      return Result.failure(
        workDataOf(KEY_ERROR_MESSAGE to "Identificador de lote de limpeza inválido."),
      )
    }

    val jobFile = File(deps.cleanupJobsDir, "$jobId.json")
    val canonicalJobsDir = runCatching { deps.cleanupJobsDir.canonicalFile }.getOrNull()
    val canonicalJobFile = runCatching { jobFile.canonicalFile }.getOrNull()
    if (canonicalJobsDir == null ||
      canonicalJobFile == null ||
      canonicalJobFile.parentFile != canonicalJobsDir ||
      !canonicalJobFile.isFile
    ) {
      return Result.failure(
        workDataOf(KEY_ERROR_MESSAGE to "Arquivo do lote de limpeza ausente ou inválido."),
      )
    }

    val payload = runCatching {
      withContext(Dispatchers.IO) {
        json.decodeFromString<CleanupJobPayload>(canonicalJobFile.readText(Charsets.UTF_8))
      }
    }.getOrElse { error ->
      withContext(Dispatchers.IO) { runCatching { canonicalJobFile.delete() } }
      return Result.failure(
        workDataOf(KEY_ERROR_MESSAGE to (error.message ?: "Falha ao ler lote de limpeza.")),
      )
    }

    runCatching {
      setForeground(createForegroundInfo(payload.candidateIds.size))
    }

    return try {
      val records = deps.scanRepository.getCandidatesByIds(payload.candidateIds)
      val excludedPaths = payload.excludedPaths
        .mapNotNull { raw -> runCatching { Paths.get(raw) }.getOrNull() }
        .toSet()

      val staleRecords = records.filter {
        it.candidate.ruleId == SuperfluousRuleId.STALE_MEDIASTORE.ruleId
      }
      val trashRecords = records.filter {
        it.candidate.ruleId != SuperfluousRuleId.STALE_MEDIASTORE.ruleId
      }

      val trashSummary = deps.trashRepository.moveCandidatesToTrash(
        records = trashRecords,
        userExcludedPaths = excludedPaths,
      )
      val repairSummary = deps.trashRepository.repairStaleMediaStore(
        records = staleRecords,
        userExcludedPaths = excludedPaths,
      )

      val cleanedIds = trashSummary.movedCandidateIds + repairSummary.repairedCandidateIds
      val skippedOrFailed = trashSummary.staleOrSkippedCount +
        trashSummary.failedCount +
        repairSummary.rejectedOrExistingCount

      withContext(NonCancellable) {
        if (cleanedIds.isNotEmpty()) {
          deps.scanRepository.removeCleanedCandidates(payload.sessionId, cleanedIds)
        }
        deps.scanLogger.logEvent(
          "CLEANUP_WORKER_DONE",
          "job=$jobId moved=${trashSummary.movedCandidateIds.size} repaired=${repairSummary.repairedCandidateIds.size} skippedOrFailed=$skippedOrFailed",
        )
        withContext(Dispatchers.IO) {
          runCatching { canonicalJobFile.delete() }
        }
      }

      Result.success(
        workDataOf(
          KEY_MOVED_COUNT to trashSummary.movedCandidateIds.size,
          KEY_MOVED_BYTES to trashSummary.movedBytes,
          KEY_REPAIRED_COUNT to repairSummary.repairedCandidateIds.size,
          KEY_SKIPPED_OR_FAILED_COUNT to skippedOrFailed,
        ),
      )
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      val reason = t.message ?: t.javaClass.simpleName
      withContext(NonCancellable) {
        deps.scanLogger.logEvent("CLEANUP_WORKER_FAILED", "job=$jobId reason=$reason")
        withContext(Dispatchers.IO) {
          runCatching { canonicalJobFile.delete() }
        }
      }
      Result.failure(workDataOf(KEY_ERROR_MESSAGE to reason))
    }
  }

  private fun resolveDependencies(): TrashCleanupDependencies? {
    if (runtimeDependencies != null) return runtimeDependencies
    val app = applicationContext as? LimpaDroidApp ?: return null
    return app.container.trashCleanupDependencies
  }

  private fun createForegroundInfo(itemCount: Int): ForegroundInfo {
    ensureNotificationChannel()
    val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(applicationContext.getString(R.string.cleanup_notification_title))
      .setContentText(
        applicationContext.getString(
          R.string.cleanup_notification_text,
          itemCount,
        ),
      )
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .build()

    return ForegroundInfo(
      NOTIFICATION_ID,
      notification,
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  private fun ensureNotificationChannel() {
    val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      applicationContext.getString(R.string.cleanup_notification_channel_name),
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = applicationContext.getString(R.string.cleanup_notification_channel_desc)
    }
    manager.createNotificationChannel(channel)
  }

  companion object {
    const val KEY_JOB_ID = "cleanup_job_id"
    const val KEY_MOVED_COUNT = "moved_count"
    const val KEY_MOVED_BYTES = "moved_bytes"
    const val KEY_REPAIRED_COUNT = "repaired_count"
    const val KEY_SKIPPED_OR_FAILED_COUNT = "skipped_or_failed_count"
    const val KEY_ERROR_MESSAGE = "error_message"

    private const val CHANNEL_ID = "limpadroid_cleanup_progress"
    private const val NOTIFICATION_ID = 1003

    private val json = Json { ignoreUnknownKeys = true }
  }
}
