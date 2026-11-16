package io.github.km20197.limpadroid.scan

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.data.scan.ScanRepository
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

sealed interface ScanStartResult {
  data class Started(val sessionId: String) : ScanStartResult
  data class AlreadyRunning(val sessionId: String) : ScanStartResult
  data class StorageUnavailable(val reason: String) : ScanStartResult
}

interface ScanController {
  val latestSession: Flow<ScanSession?>
  fun observeSession(sessionId: String): Flow<ScanSession?>
  fun observeRecentErrors(sessionId: String): Flow<List<WalkError>>
  suspend fun reconcileInterruptedSessions()
  suspend fun startNewScan(scope: ScanScope = ScanScope.INFRASTRUCTURE): ScanStartResult
  suspend fun resumeScan(sessionId: String): Boolean
  suspend fun pauseScan(sessionId: String)
  suspend fun cancelScan(sessionId: String)
}

class WorkManagerScanController(
  private val context: Context,
  private val scanRepository: ScanRepository,
  private val storageVolumeResolver: StorageVolumeResolver,
  private val scanLogger: ScanLogger,
) : ScanController {

  override val latestSession: Flow<ScanSession?> = scanRepository.latestSession

  override fun observeSession(sessionId: String): Flow<ScanSession?> =
    scanRepository.observeSession(sessionId)

  override fun observeRecentErrors(sessionId: String): Flow<List<WalkError>> =
    scanRepository.observeRecentErrors(sessionId)

  override suspend fun reconcileInterruptedSessions() {
    val latest = scanRepository.getLatestSession() ?: return
    if (latest.status != ScanStatus.RUNNING) return

    val hasActiveWork = runCatching {
      withContext(Dispatchers.IO) {
        val infos = WorkManager.getInstance(context)
          .getWorkInfosForUniqueWork(UNIQUE_SCAN_WORK_NAME)
          .get()
        infos.any { info ->
          info.state == WorkInfo.State.RUNNING ||
            info.state == WorkInfo.State.ENQUEUED ||
            info.state == WorkInfo.State.BLOCKED
        }
      }
    }.getOrDefault(false)

    if (!hasActiveWork) {
      scanRepository.finalizeWorkerRun(
        sessionId = latest.id,
        targetStatusIfStillRunning = ScanStatus.PAUSED,
        scannedFiles = latest.settledFiles,
        scannedDirs = latest.settledDirs,
        scannedBytes = latest.settledBytes,
        checkpoint = WalkCheckpoint(
          lastProcessedPath = latest.lastProcessedPath,
          completedSubtrees = latest.completedSubtrees,
          settledFiles = latest.settledFiles,
          settledDirs = latest.settledDirs,
          settledBytes = latest.settledBytes,
        ),
      )
      scanLogger.logEvent("SCAN_RECONCILED", "session=${latest.id} marked=PAUSED")
    }
  }

  override suspend fun startNewScan(scope: ScanScope): ScanStartResult {
    reconcileInterruptedSessions()
    val existing = scanRepository.getLatestSession()
    if (existing != null && existing.status == ScanStatus.RUNNING) {
      return ScanStartResult.AlreadyRunning(existing.id)
    }

    val storageResolution = storageVolumeResolver.resolvePrimarySharedStorage()
    if (storageResolution is StorageRootResolution.Unavailable) {
      scanLogger.logEvent("SCAN_START_BLOCKED", "reason=${storageResolution.reason}")
      return ScanStartResult.StorageUnavailable(storageResolution.reason)
    }

    val rootPath = (storageResolution as StorageRootResolution.Available).rootPath.toString()
    val session = scanRepository.createSession(scope = scope, rootPath = rootPath)
    scanLogger.logEvent("SCAN_ENQUEUED", "session=${session.id} scope=$scope root=$rootPath")
    enqueueWork(sessionId = session.id, resume = false)
    return ScanStartResult.Started(session.id)
  }

  override suspend fun resumeScan(sessionId: String): Boolean {
    reconcileInterruptedSessions()
    val session = scanRepository.getSession(sessionId) ?: return false
    if (session.status != ScanStatus.PAUSED) return false

    scanRepository.markRunningForResume(sessionId)
    scanLogger.logEvent("SCAN_RESUME_ENQUEUED", "session=$sessionId lastPath=${session.lastProcessedPath}")
    enqueueWork(sessionId = sessionId, resume = true)
    return true
  }

  override suspend fun pauseScan(sessionId: String) {
    scanRepository.markRequestedStatus(sessionId = sessionId, status = ScanStatus.PAUSED)
    scanLogger.logEvent("SCAN_PAUSE_REQUESTED", "session=$sessionId")
    runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_SCAN_WORK_NAME) }
  }

  override suspend fun cancelScan(sessionId: String) {
    scanRepository.markRequestedStatus(sessionId = sessionId, status = ScanStatus.CANCELLED)
    scanLogger.logEvent("SCAN_CANCEL_REQUESTED", "session=$sessionId")
    runCatching { WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_SCAN_WORK_NAME) }
  }

  private fun enqueueWork(sessionId: String, resume: Boolean) {
    val request = OneTimeWorkRequestBuilder<ScanWorker>()
      .setInputData(
        workDataOf(
          ScanWorker.KEY_SESSION_ID to sessionId,
          ScanWorker.KEY_RESUME to resume,
        ),
      )
      .addTag(UNIQUE_SCAN_WORK_NAME)
      .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
      UNIQUE_SCAN_WORK_NAME,
      ExistingWorkPolicy.REPLACE,
      request,
    )
  }

  companion object {
    const val UNIQUE_SCAN_WORK_NAME = "limpadroid_active_scan"
  }
}
