package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FullScanEngine(
  private val engines: List<ScanEngine>,
) : ScanEngine {
  override val scope: ScanScope = ScanScope.FULL
  override val category: Category? = null

  override fun scan(
    config: ScanConfig,
    latestCheckpointRef: AtomicReference<WalkCheckpoint>?,
  ): Flow<ScanEvent> = flow {
    val startNanos = System.nanoTime()
    emit(ScanEvent.Started(sessionId = config.sessionId, resumed = config.resumed))

    val completedStageMarkers = config.initialCheckpoint.completedSubtrees
      .filter { it.startsWith(STAGE_MARKER_PREFIX) }
      .toMutableSet()
    val nonStageSubtrees = config.initialCheckpoint.completedSubtrees
      .filterNot { it.startsWith(STAGE_MARKER_PREFIX) }
      .toSet()

    val resumedFromCompletedStage = config.resumed && completedStageMarkers.isNotEmpty()
    var baseFiles = if (resumedFromCompletedStage) config.initialCheckpoint.settledFiles else 0L
    var baseDirs = if (resumedFromCompletedStage) config.initialCheckpoint.settledDirs else 0L
    var baseBytes = if (resumedFromCompletedStage) config.initialCheckpoint.settledBytes else 0L
    var baseErrors = config.initialErrorCount
    var totalCandidates = 0
    var latestFiles = config.initialCheckpoint.settledFiles
    var latestDirs = config.initialCheckpoint.settledDirs
    var latestBytes = config.initialCheckpoint.settledBytes
    var latestErrors = config.initialErrorCount
    var latestPath = config.initialCheckpoint.lastProcessedPath ?: config.rootPath.toString()

    for ((index, engine) in engines.withIndex()) {
      currentCoroutineContext().ensureActive()
      val marker = stageMarker(engine.scope)
      if (marker in completedStageMarkers) {
        continue
      }

      val passInitialSubtreeCheckpoint =
        config.resumed &&
          completedStageMarkers.isEmpty() &&
          index == 0 &&
          engine.scope == ScanScope.SUPERFLUOUS

      val stageInitialCheckpoint = if (passInitialSubtreeCheckpoint) {
        config.initialCheckpoint.copy(completedSubtrees = nonStageSubtrees)
      } else {
        WalkCheckpoint()
      }

      val childConfig = config.copy(
        scope = engine.scope,
        resumed = passInitialSubtreeCheckpoint,
        initialCheckpoint = stageInitialCheckpoint,
        initialErrorCount = 0,
      )
      val childCheckpointRef = AtomicReference(stageInitialCheckpoint)
      var stageDone: ScanEvent.Done? = null
      var stageInterrupted = false

      engine.scan(childConfig, childCheckpointRef).collect { event ->
        when (event) {
          is ScanEvent.Started -> Unit

          is ScanEvent.Found -> {
            totalCandidates += 1
            emit(event)
          }

          is ScanEvent.ErrorLogged -> {
            emit(event)
          }

          is ScanEvent.Progress -> {
            latestFiles = baseFiles + event.scannedFiles
            latestDirs = baseDirs + event.scannedDirs
            latestBytes = baseBytes + event.scannedBytes
            latestErrors = baseErrors + event.errorCount
            latestPath = event.currentPath ?: latestPath
            val merged = mergeCheckpoint(
              completedStageMarkers = completedStageMarkers,
              childCheckpoint = event.checkpoint,
              cumulativeFiles = latestFiles,
              cumulativeDirs = latestDirs,
              cumulativeBytes = latestBytes,
              fallbackPath = latestPath,
            )
            latestCheckpointRef?.set(merged)
            emit(
              ScanEvent.Progress(
                scannedFiles = latestFiles,
                scannedDirs = latestDirs,
                scannedBytes = latestBytes,
                currentPath = latestPath,
                errorCount = latestErrors,
                checkpoint = merged,
              ),
            )
          }

          is ScanEvent.Checkpoint -> {
            latestFiles = baseFiles + event.scannedFiles
            latestDirs = baseDirs + event.scannedDirs
            latestBytes = baseBytes + event.scannedBytes
            latestErrors = baseErrors + event.errorCount
            latestPath = event.checkpoint.lastProcessedPath ?: latestPath
            val merged = mergeCheckpoint(
              completedStageMarkers = completedStageMarkers,
              childCheckpoint = event.checkpoint,
              cumulativeFiles = latestFiles,
              cumulativeDirs = latestDirs,
              cumulativeBytes = latestBytes,
              fallbackPath = latestPath,
            )
            latestCheckpointRef?.set(merged)
            emit(
              ScanEvent.Checkpoint(
                checkpoint = merged,
                scannedFiles = latestFiles,
                scannedDirs = latestDirs,
                scannedBytes = latestBytes,
                errorCount = latestErrors,
              ),
            )
          }

          is ScanEvent.Interrupted -> {
            stageInterrupted = true
            latestFiles = baseFiles + event.scannedFiles
            latestDirs = baseDirs + event.scannedDirs
            latestBytes = baseBytes + event.scannedBytes
            latestErrors = baseErrors + event.errorCount
            latestPath = event.checkpoint.lastProcessedPath ?: latestPath
            val merged = mergeCheckpoint(
              completedStageMarkers = completedStageMarkers,
              childCheckpoint = event.checkpoint,
              cumulativeFiles = latestFiles,
              cumulativeDirs = latestDirs,
              cumulativeBytes = latestBytes,
              fallbackPath = latestPath,
            )
            latestCheckpointRef?.set(merged)
            emit(
              ScanEvent.Interrupted(
                checkpoint = merged,
                scannedFiles = latestFiles,
                scannedDirs = latestDirs,
                scannedBytes = latestBytes,
                errorCount = latestErrors,
              ),
            )
          }

          is ScanEvent.Done -> {
            stageDone = event
          }
        }
      }

      if (stageInterrupted) {
        return@flow
      }

      val done = stageDone ?: return@flow
      baseFiles += done.scannedFiles
      baseDirs += done.scannedDirs
      baseBytes += done.scannedBytes
      baseErrors += done.errorCount
      latestFiles = baseFiles
      latestDirs = baseDirs
      latestBytes = baseBytes
      latestErrors = baseErrors
      completedStageMarkers.add(marker)

      val stageCheckpoint = WalkCheckpoint(
        lastProcessedPath = latestPath,
        completedSubtrees = completedStageMarkers.toSet(),
        settledFiles = baseFiles,
        settledDirs = baseDirs,
        settledBytes = baseBytes,
      )
      latestCheckpointRef?.set(stageCheckpoint)
      emit(
        ScanEvent.Checkpoint(
          checkpoint = stageCheckpoint,
          scannedFiles = baseFiles,
          scannedDirs = baseDirs,
          scannedBytes = baseBytes,
          errorCount = baseErrors,
        ),
      )
    }

    val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
    emit(
      ScanEvent.Done(
        scannedFiles = latestFiles,
        scannedDirs = latestDirs,
        scannedBytes = latestBytes,
        candidatesCount = totalCandidates,
        errorCount = latestErrors,
        durationMs = durationMs,
      ),
    )
  }

  private fun mergeCheckpoint(
    completedStageMarkers: Set<String>,
    childCheckpoint: WalkCheckpoint,
    cumulativeFiles: Long,
    cumulativeDirs: Long,
    cumulativeBytes: Long,
    fallbackPath: String?,
  ): WalkCheckpoint = WalkCheckpoint(
    lastProcessedPath = childCheckpoint.lastProcessedPath ?: fallbackPath,
    completedSubtrees = completedStageMarkers + childCheckpoint.completedSubtrees,
    settledFiles = cumulativeFiles,
    settledDirs = cumulativeDirs,
    settledBytes = cumulativeBytes,
  )

  companion object {
    private const val STAGE_MARKER_PREFIX = "__FULL_STAGE_"

    fun stageMarker(scope: ScanScope): String = "${STAGE_MARKER_PREFIX}${scope.name}__"
  }
}
