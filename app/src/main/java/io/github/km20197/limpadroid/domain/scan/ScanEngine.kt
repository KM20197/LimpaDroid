package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.fs.PathGuard
import io.github.km20197.limpadroid.domain.fs.WalkConfig
import io.github.km20197.limpadroid.domain.fs.WalkEvent
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class ScanConfig(
  val sessionId: String,
  val scope: ScanScope,
  val rootPath: Path,
  val pathGuard: PathGuard,
  val resumed: Boolean = false,
  val initialCheckpoint: WalkCheckpoint = WalkCheckpoint(),
  val initialErrorCount: Int = 0,
  val rules: List<ScanRule> = emptyList(),
)

interface ScanEngine {
  val scope: ScanScope
  val category: Category?
  fun scan(
    config: ScanConfig,
    latestCheckpointRef: AtomicReference<WalkCheckpoint>? = null,
  ): Flow<ScanEvent>
}

class InfrastructureScanEngine(
  private val fileWalker: FileWalker,
) : ScanEngine {
  override val scope: ScanScope = ScanScope.INFRASTRUCTURE
  override val category: Category? = null

  override fun scan(
    config: ScanConfig,
    latestCheckpointRef: AtomicReference<WalkCheckpoint>?,
  ): Flow<ScanEvent> = flow {
    emit(ScanEvent.Started(sessionId = config.sessionId, resumed = config.resumed))

    val classifier = CandidateClassifier(config.rules)
    val rootString = config.pathGuard.storageRoot.toString()
    var candidatesCount = 0

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
          emit(
            ScanEvent.Done(
              scannedFiles = walkEvent.scannedFiles,
              scannedDirs = walkEvent.scannedDirs,
              scannedBytes = walkEvent.scannedBytes,
              candidatesCount = candidatesCount,
              errorCount = walkEvent.errorCount,
              durationMs = walkEvent.durationMs,
            ),
          )
        }
      }
    }
  }
}
