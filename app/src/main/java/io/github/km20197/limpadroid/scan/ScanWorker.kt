package io.github.km20197.limpadroid.scan

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
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.data.scan.ScanRepository
import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.scan.FullScanEngine
import io.github.km20197.limpadroid.domain.scan.ScanConfig
import io.github.km20197.limpadroid.domain.scan.ScanEngine
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class ScanWorkerDependencies(
  val scanRepository: ScanRepository,
  val storageVolumeResolver: StorageVolumeResolver,
  val scanEngine: ScanEngine,
  val scanLogger: ScanLogger,
  val applicationId: String,
  val superfluousScanEngine: ScanEngine? = null,
  val orphanScanEngine: ScanEngine? = null,
  val duplicateScanEngine: ScanEngine? = null,
  val whatsappScanEngine: ScanEngine? = null,
  val userExcludedPathsProvider: suspend () -> Set<String> = { emptySet() },
)

class ScanWorker(
  appContext: Context,
  params: WorkerParameters,
  private val runtimeDependencies: ScanWorkerDependencies? = null,
) : CoroutineWorker(appContext, params) {

  constructor(
    appContext: Context,
    params: WorkerParameters,
  ) : this(appContext, params, null)

  override suspend fun doWork(): Result {
    val deps = resolveDependencies() ?: return Result.failure()
    val sessionId = inputData.getString(KEY_SESSION_ID) ?: return Result.failure()
    val session = deps.scanRepository.getSession(sessionId) ?: return Result.failure()

    if (session.status != ScanStatus.RUNNING) {
      return Result.success()
    }

    val storageResolution = deps.storageVolumeResolver.resolvePrimarySharedStorage()
    val rootPath = when (storageResolution) {
      is StorageRootResolution.Available -> storageResolution.rootPath
      is StorageRootResolution.Unavailable -> {
        val error = WalkError(
          path = session.rootPath,
          errorType = "StorageUnavailable",
          message = storageResolution.reason,
        )
        deps.scanRepository.appendCandidatesAndErrors(sessionId, emptyList(), listOf(error))
        deps.scanRepository.finalizeWorkerRun(
          sessionId = sessionId,
          targetStatusIfStillRunning = ScanStatus.FAILED,
          scannedFiles = session.scannedFiles,
          scannedDirs = session.scannedDirs,
          scannedBytes = session.scannedBytes,
          checkpoint = WalkCheckpoint(
            lastProcessedPath = session.lastProcessedPath,
            completedSubtrees = session.completedSubtrees,
            settledFiles = session.settledFiles,
            settledDirs = session.settledDirs,
            settledBytes = session.settledBytes,
          ),
        )
        deps.scanLogger.logEvent("SCAN_FAILED", "session=$sessionId reason=${storageResolution.reason}")
        return Result.failure()
      }
    }

    runCatching {
      setForeground(createForegroundInfo(session.scannedFiles, session.errorCount))
    }

    val resumed = inputData.getBoolean(KEY_RESUME, false) ||
      session.completedSubtrees.isNotEmpty() ||
      session.lastProcessedPath != null

    val initialCheckpoint = WalkCheckpoint(
      lastProcessedPath = session.lastProcessedPath,
      completedSubtrees = session.completedSubtrees,
      settledFiles = session.settledFiles,
      settledDirs = session.settledDirs,
      settledBytes = session.settledBytes,
    )
    val latestCheckpointRef = AtomicReference(initialCheckpoint)

    var latestScannedFiles = session.settledFiles
    var latestScannedDirs = session.settledDirs
    var latestScannedBytes = session.settledBytes
    var latestErrorCount = session.errorCount
    var completedNormally = false

    val pendingCandidates = mutableListOf<ScanEvent.Found>()
    val pendingErrors = mutableListOf<WalkError>()

    suspend fun flushBatches() {
      if (pendingCandidates.isNotEmpty() || pendingErrors.isNotEmpty()) {
        val candidatesSnapshot = pendingCandidates.toList()
        val errorsSnapshot = pendingErrors.toList()
        pendingCandidates.clear()
        pendingErrors.clear()
        deps.scanRepository.appendFoundEventsAndErrors(sessionId, candidatesSnapshot, errorsSnapshot)
      }
    }

    val excludedPaths = runCatching {
      deps.userExcludedPathsProvider()
        .mapNotNull { raw -> runCatching { Paths.get(raw) }.getOrNull() }
        .toSet()
    }.getOrDefault(emptySet())

    val pathGuard = DefaultPathGuard(
      rawStorageRoot = rootPath,
      applicationId = deps.applicationId,
      userExcludedPaths = excludedPaths,
    )
    val config = ScanConfig(
      sessionId = sessionId,
      scope = session.scope,
      rootPath = Paths.get(rootPath.toUri()),
      pathGuard = pathGuard,
      resumed = resumed,
      initialCheckpoint = initialCheckpoint,
      initialErrorCount = session.errorCount,
    )

    val categoryEngines = listOfNotNull(
      deps.superfluousScanEngine,
      deps.orphanScanEngine,
      deps.duplicateScanEngine,
      deps.whatsappScanEngine,
    )
    val activeEngine = when (session.scope) {
      ScanScope.SUPERFLUOUS -> deps.superfluousScanEngine ?: deps.scanEngine
      ScanScope.ORPHAN -> deps.orphanScanEngine ?: deps.scanEngine
      ScanScope.DUPLICATE -> deps.duplicateScanEngine ?: deps.scanEngine
      ScanScope.WHATSAPP -> deps.whatsappScanEngine ?: deps.scanEngine
      ScanScope.FULL -> if (categoryEngines.isNotEmpty()) FullScanEngine(categoryEngines) else deps.scanEngine
      ScanScope.INFRASTRUCTURE -> deps.scanEngine
    }

    deps.scanLogger.logEvent(
      "SCAN_WORKER_START",
      "session=$sessionId scope=${session.scope} resumed=$resumed root=$rootPath",
    )

    try {
      activeEngine.scan(config, latestCheckpointRef).collect { event ->
        when (event) {
          is ScanEvent.Started -> Unit

          is ScanEvent.Found -> {
            pendingCandidates.add(event)
            if (pendingCandidates.size >= BATCH_FLUSH_SIZE) {
              flushBatches()
            }
          }

          is ScanEvent.ErrorLogged -> {
            pendingErrors.add(event.error)
            deps.scanLogger.logEvent(
              "SCAN_FILE_ERROR",
              "session=$sessionId type=${event.error.errorType} path=${event.error.path}",
            )
            if (pendingErrors.size >= BATCH_FLUSH_SIZE) {
              flushBatches()
            }
          }

          is ScanEvent.Progress -> {
            latestScannedFiles = event.scannedFiles
            latestScannedDirs = event.scannedDirs
            latestScannedBytes = event.scannedBytes
            latestErrorCount = event.errorCount
            latestCheckpointRef.set(event.checkpoint)
            flushBatches()
            deps.scanRepository.saveProgress(
              sessionId = sessionId,
              scannedFiles = event.scannedFiles,
              scannedDirs = event.scannedDirs,
              scannedBytes = event.scannedBytes,
              checkpoint = event.checkpoint,
            )
            runCatching {
              setProgress(
                workDataOf(
                  KEY_SCANNED_FILES to event.scannedFiles,
                  KEY_SCANNED_BYTES to event.scannedBytes,
                  KEY_ERROR_COUNT to event.errorCount,
                ),
              )
              setForeground(createForegroundInfo(event.scannedFiles, event.errorCount))
            }
          }

          is ScanEvent.Checkpoint -> {
            latestScannedFiles = event.scannedFiles
            latestScannedDirs = event.scannedDirs
            latestScannedBytes = event.scannedBytes
            latestErrorCount = event.errorCount
            latestCheckpointRef.set(event.checkpoint)
            flushBatches()
            deps.scanRepository.saveProgress(
              sessionId = sessionId,
              scannedFiles = event.scannedFiles,
              scannedDirs = event.scannedDirs,
              scannedBytes = event.scannedBytes,
              checkpoint = event.checkpoint,
            )
          }

          is ScanEvent.Interrupted -> {
            latestScannedFiles = event.scannedFiles
            latestScannedDirs = event.scannedDirs
            latestScannedBytes = event.scannedBytes
            latestErrorCount = event.errorCount
            latestCheckpointRef.set(event.checkpoint)
          }

          is ScanEvent.Done -> {
            latestScannedFiles = event.scannedFiles
            latestScannedDirs = event.scannedDirs
            latestScannedBytes = event.scannedBytes
            latestErrorCount = event.errorCount
            completedNormally = true
          }
        }
      }

      if (completedNormally && !isStopped) {
        flushBatches()
        deps.scanRepository.finalizeWorkerRun(
          sessionId = sessionId,
          targetStatusIfStillRunning = ScanStatus.COMPLETED,
          scannedFiles = latestScannedFiles,
          scannedDirs = latestScannedDirs,
          scannedBytes = latestScannedBytes,
          checkpoint = latestCheckpointRef.get(),
        )
        deps.scanLogger.logEvent(
          "SCAN_COMPLETED",
          "session=$sessionId files=$latestScannedFiles dirs=$latestScannedDirs bytes=$latestScannedBytes errors=$latestErrorCount",
        )
        return Result.success(
          workDataOf(
            KEY_SESSION_ID to sessionId,
            KEY_SCANNED_FILES to latestScannedFiles,
            KEY_SCANNED_BYTES to latestScannedBytes,
            KEY_ERROR_COUNT to latestErrorCount,
          ),
        )
      } else {
        flushBatches()
        deps.scanRepository.finalizeWorkerRun(
          sessionId = sessionId,
          targetStatusIfStillRunning = ScanStatus.PAUSED,
          scannedFiles = latestScannedFiles,
          scannedDirs = latestScannedDirs,
          scannedBytes = latestScannedBytes,
          checkpoint = latestCheckpointRef.get(),
        )
        deps.scanLogger.logEvent(
          "SCAN_INTERRUPTED",
          "session=$sessionId files=$latestScannedFiles errors=$latestErrorCount",
        )
        return Result.success()
      }
    } catch (ce: CancellationException) {
      withContext(NonCancellable) {
        flushBatches()
        deps.scanRepository.finalizeWorkerRun(
          sessionId = sessionId,
          targetStatusIfStillRunning = ScanStatus.PAUSED,
          scannedFiles = latestScannedFiles,
          scannedDirs = latestScannedDirs,
          scannedBytes = latestScannedBytes,
          checkpoint = latestCheckpointRef.get(),
        )
        deps.scanLogger.logEvent(
          "SCAN_STOPPED",
          "session=$sessionId files=$latestScannedFiles errors=$latestErrorCount",
        )
      }
      throw ce
    } catch (t: Throwable) {
      withContext(NonCancellable) {
        pendingErrors.add(
          WalkError(
            path = rootPath.toString(),
            errorType = t.javaClass.simpleName.ifBlank { "ScanException" },
            message = t.message ?: "Falha inesperada na varredura",
          ),
        )
        flushBatches()
        deps.scanRepository.finalizeWorkerRun(
          sessionId = sessionId,
          targetStatusIfStillRunning = ScanStatus.FAILED,
          scannedFiles = latestScannedFiles,
          scannedDirs = latestScannedDirs,
          scannedBytes = latestScannedBytes,
          checkpoint = latestCheckpointRef.get(),
        )
        deps.scanLogger.logEvent(
          "SCAN_FAILED",
          "session=$sessionId error=${t.javaClass.simpleName}:${t.message}",
        )
      }
      return Result.failure()
    }
  }

  private fun resolveDependencies(): ScanWorkerDependencies? {
    if (runtimeDependencies != null) return runtimeDependencies
    val app = applicationContext as? LimpaDroidApp ?: return null
    return app.container.scanWorkerDependencies
  }

  private fun createForegroundInfo(scannedFiles: Long, errorCount: Int): ForegroundInfo {
    ensureNotificationChannel()
    val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(applicationContext.getString(R.string.scan_notification_title))
      .setContentText(
        applicationContext.getString(
          R.string.scan_notification_text,
          scannedFiles,
          errorCount,
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
      applicationContext.getString(R.string.scan_notification_channel_name),
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = applicationContext.getString(R.string.scan_notification_channel_desc)
    }
    manager.createNotificationChannel(channel)
  }

  companion object {
    const val KEY_SESSION_ID = "session_id"
    const val KEY_RESUME = "resume"
    const val KEY_SCANNED_FILES = "scanned_files"
    const val KEY_SCANNED_BYTES = "scanned_bytes"
    const val KEY_ERROR_COUNT = "error_count"

    private const val CHANNEL_ID = "limpadroid_scan_progress"
    private const val NOTIFICATION_ID = 1001
    private const val BATCH_FLUSH_SIZE = 50
  }
}
