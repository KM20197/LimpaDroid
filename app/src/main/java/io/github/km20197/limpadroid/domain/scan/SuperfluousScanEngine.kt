package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.fs.MediaStoreGateway
import io.github.km20197.limpadroid.domain.fs.WalkConfig
import io.github.km20197.limpadroid.domain.fs.WalkEvent
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.scan.rules.StaleMediaStoreDescriptor
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleRegistry
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SuperfluousScanEngine(
  private val fileWalker: FileWalker,
  private val mediaStoreGateway: MediaStoreGateway? = null,
  private val clock: AppClock = AppClock.System,
  private val enabledRulesProvider: suspend () -> Set<String> = { SuperfluousRuleId.DEFAULT_ENABLED_IDS },
) : ScanEngine {
  override val scope: ScanScope = ScanScope.SUPERFLUOUS
  override val category: Category = Category.SUPERFLUOUS

  override fun scan(
    config: ScanConfig,
    latestCheckpointRef: AtomicReference<WalkCheckpoint>?,
  ): Flow<ScanEvent> = flow {
    emit(ScanEvent.Started(sessionId = config.sessionId, resumed = config.resumed))

    val enabledIds = enabledRulesProvider()
    val activeRules = if (config.rules.isNotEmpty()) {
      config.rules
    } else {
      SuperfluousRuleRegistry.buildFilesystemRules(enabledIds, clock)
    }
    val classifier = CandidateClassifier(activeRules)
    val rootString = config.pathGuard.storageRoot.toString()
    var candidatesCount = 0
    var completedWalk: WalkEvent.Completed? = null

    fileWalker.walk(
      config = WalkConfig(
        rootPath = config.rootPath,
        pathGuard = config.pathGuard,
        initialCheckpoint = config.initialCheckpoint,
        initialErrorCount = config.initialErrorCount,
      ),
      latestCheckpointRef = latestCheckpointRef,
    ).collect { walkEvent ->
      when (walkEvent) {
        is WalkEvent.Entry -> {
          val classified = classifier.classify(walkEvent.entry, rootString)
          if (classified != null) {
            candidatesCount += 1
            emit(ScanEvent.Found(candidate = classified.first, reason = classified.second))
          }
        }

        is WalkEvent.Progress -> {
          emit(
            ScanEvent.Progress(
              scannedFiles = walkEvent.scannedFiles,
              scannedDirs = walkEvent.scannedDirs,
              scannedBytes = walkEvent.scannedBytes,
              currentPath = walkEvent.currentPath,
              errorCount = walkEvent.errorCount,
              checkpoint = walkEvent.checkpoint,
            ),
          )
        }

        is WalkEvent.Error -> {
          emit(ScanEvent.ErrorLogged(walkEvent.error))
        }

        is WalkEvent.Interrupted -> {
          emit(
            ScanEvent.Interrupted(
              checkpoint = walkEvent.checkpoint,
              scannedFiles = walkEvent.scannedFiles,
              scannedDirs = walkEvent.scannedDirs,
              scannedBytes = walkEvent.scannedBytes,
              errorCount = walkEvent.errorCount,
            ),
          )
        }

        is WalkEvent.Completed -> {
          completedWalk = walkEvent
        }
      }
    }

    val finished = completedWalk ?: return@flow

    if (SuperfluousRuleId.STALE_MEDIASTORE.ruleId in enabledIds && mediaStoreGateway != null) {
      currentCoroutineContext().ensureActive()
      val staleEntries = runCatching {
        mediaStoreGateway.findStaleEntries(config.rootPath, config.pathGuard)
      }.getOrDefault(emptyList())

      for (stale in staleEntries) {
        currentCoroutineContext().ensureActive()
        candidatesCount += 1
        emit(
          ScanEvent.Found(
            candidate = Candidate(
              path = stale.path,
              sizeBytes = 0L,
              lastModified = stale.lastModifiedMillis,
              category = Category.SUPERFLUOUS,
              ruleId = StaleMediaStoreDescriptor.ruleId,
              confidence = Confidence.HIGH,
              preselected = true,
            ),
            reason = StaleMediaStoreDescriptor.description,
          ),
        )
      }
    }

    emit(
      ScanEvent.Done(
        scannedFiles = finished.scannedFiles,
        scannedDirs = finished.scannedDirs,
        scannedBytes = finished.scannedBytes,
        candidatesCount = candidatesCount,
        errorCount = finished.errorCount,
        durationMs = finished.durationMs,
      ),
    )
  }
}
