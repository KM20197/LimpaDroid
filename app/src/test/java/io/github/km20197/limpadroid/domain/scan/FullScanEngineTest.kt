package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FullScanEngineTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `scan runs all category engines sequentially accumulates metrics and emits single Done`() = runTest {
    val root = tempFolder.newFolder("full_root").toPath()
    val pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid")

    val engine1 = fakeEngine(
      scope = ScanScope.SUPERFLUOUS,
      category = Category.SUPERFLUOUS,
      filesCount = 10L,
      dirsCount = 2L,
      bytesCount = 1000L,
      errorCount = 1,
      foundCandidate = Candidate(
        path = "$root/DCIM/.thumbnails/a.jpg",
        sizeBytes = 500L,
        lastModified = 100L,
        category = Category.SUPERFLUOUS,
        ruleId = "THUMBS",
        confidence = Confidence.HIGH,
        preselected = true,
      ),
    )
    val engine2 = fakeEngine(
      scope = ScanScope.DUPLICATE,
      category = Category.DUPLICATE,
      filesCount = 25L,
      dirsCount = 4L,
      bytesCount = 2500L,
      errorCount = 2,
      foundCandidate = Candidate(
        path = "$root/Download/dup.jpg",
        sizeBytes = 1200L,
        lastModified = 200L,
        category = Category.DUPLICATE,
        ruleId = "DUPLICATE_PRIORITY",
        confidence = Confidence.HIGH,
        groupId = "sha1",
        preselected = true,
      ),
    )

    val checkpointRef = AtomicReference(WalkCheckpoint())
    val fullEngine = FullScanEngine(listOf(engine1, engine2))
    val events = fullEngine.scan(
      ScanConfig(
        sessionId = "s-full",
        scope = ScanScope.FULL,
        rootPath = root,
        pathGuard = pathGuard,
      ),
      checkpointRef,
    ).toList()

    assertEquals(1, events.count { it is ScanEvent.Started })
    assertEquals(1, events.count { it is ScanEvent.Done })
    assertEquals(2, events.count { it is ScanEvent.Found })

    val progressEvents = events.filterIsInstance<ScanEvent.Progress>()
    assertTrue(progressEvents.isNotEmpty())
    assertTrue(progressEvents.zipWithNext().all { (a, b) -> b.scannedFiles >= a.scannedFiles })

    val done = events.filterIsInstance<ScanEvent.Done>().single()
    assertEquals(35L, done.scannedFiles)
    assertEquals(6L, done.scannedDirs)
    assertEquals(3500L, done.scannedBytes)
    assertEquals(2, done.candidatesCount)
    assertEquals(3, done.errorCount)

    val finalMarkers = checkpointRef.get().completedSubtrees
    assertTrue(FullScanEngine.stageMarker(ScanScope.SUPERFLUOUS) in finalMarkers)
    assertTrue(FullScanEngine.stageMarker(ScanScope.DUPLICATE) in finalMarkers)
  }

  @Test
  fun `resumed full scan skips stages marked completed and stays monotonic`() = runTest {
    val root = tempFolder.newFolder("full_resume").toPath()
    val pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid")

    var ranFirst = false
    val firstEngine = object : ScanEngine {
      override val scope: ScanScope = ScanScope.SUPERFLUOUS
      override val category: Category = Category.SUPERFLUOUS
      override fun scan(config: ScanConfig, latestCheckpointRef: AtomicReference<WalkCheckpoint>?): Flow<ScanEvent> = flow {
        ranFirst = true
        emit(ScanEvent.Started(config.sessionId, config.resumed))
        emit(ScanEvent.Done(10L, 1L, 100L, 0, 0, 1L))
      }
    }
    val secondEngine = fakeEngine(
      scope = ScanScope.WHATSAPP,
      category = Category.WHATSAPP,
      filesCount = 15L,
      dirsCount = 3L,
      bytesCount = 2000L,
      errorCount = 1,
      foundCandidate = Candidate(
        path = "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/img.jpg",
        sizeBytes = 900L,
        lastModified = 100L,
        category = Category.WHATSAPP,
        ruleId = "WA_IMAGES",
        confidence = Confidence.HIGH,
        preselected = false,
      ),
    )

    val initialCheckpoint = WalkCheckpoint(
      lastProcessedPath = "$root/DCIM",
      completedSubtrees = setOf(FullScanEngine.stageMarker(ScanScope.SUPERFLUOUS)),
      settledFiles = 40L,
      settledDirs = 5L,
      settledBytes = 4000L,
    )

    val fullEngine = FullScanEngine(listOf(firstEngine, secondEngine))
    val events = fullEngine.scan(
      ScanConfig(
        sessionId = "s-resume",
        scope = ScanScope.FULL,
        rootPath = root,
        pathGuard = pathGuard,
        resumed = true,
        initialCheckpoint = initialCheckpoint,
        initialErrorCount = 2,
      ),
    ).toList()

    assertEquals(false, ranFirst)
    val done = events.filterIsInstance<ScanEvent.Done>().single()
    assertEquals(55L, done.scannedFiles)
    assertEquals(8L, done.scannedDirs)
    assertEquals(6000L, done.scannedBytes)
    assertEquals(3, done.errorCount)
    assertEquals(1, events.count { it is ScanEvent.Found })
  }

  private fun fakeEngine(
    scope: ScanScope,
    category: Category,
    filesCount: Long,
    dirsCount: Long,
    bytesCount: Long,
    errorCount: Int,
    foundCandidate: Candidate,
  ): ScanEngine = object : ScanEngine {
    override val scope: ScanScope = scope
    override val category: Category = category

    override fun scan(
      config: ScanConfig,
      latestCheckpointRef: AtomicReference<WalkCheckpoint>?,
    ): Flow<ScanEvent> = flow {
      emit(ScanEvent.Started(config.sessionId, config.resumed))
      repeat(errorCount) { idx ->
        emit(
          ScanEvent.ErrorLogged(
            WalkError(path = "${foundCandidate.path}_err_$idx", errorType = "IOException", message = "err"),
          ),
        )
      }
      emit(ScanEvent.Found(foundCandidate, reason = "teste"))
      val cp = WalkCheckpoint(
        lastProcessedPath = foundCandidate.path,
        settledFiles = filesCount,
        settledDirs = dirsCount,
        settledBytes = bytesCount,
      )
      latestCheckpointRef?.set(cp)
      emit(
        ScanEvent.Progress(
          scannedFiles = filesCount,
          scannedDirs = dirsCount,
          scannedBytes = bytesCount,
          currentPath = foundCandidate.path,
          errorCount = errorCount,
          checkpoint = cp,
        ),
      )
      emit(
        ScanEvent.Done(
          scannedFiles = filesCount,
          scannedDirs = dirsCount,
          scannedBytes = bytesCount,
          candidatesCount = 1,
          errorCount = errorCount,
          durationMs = 5L,
        ),
      )
    }
  }
}
