package io.github.km20197.limpadroid.data.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import io.github.km20197.limpadroid.data.db.LimpaDroidDatabase
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.LocalScanLogger
import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.scan.InfrastructureScanEngine
import io.github.km20197.limpadroid.domain.scan.ScanConfig
import io.github.km20197.limpadroid.domain.scan.ScanEngine
import io.github.km20197.limpadroid.scan.ScanWorker
import io.github.km20197.limpadroid.scan.ScanWorkerDependencies
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ScanRepositoryAndWorkerRobolectricTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var context: Context
  private lateinit var db: LimpaDroidDatabase
  private lateinit var repository: ScanRepository
  private lateinit var storageRoot: Path
  private lateinit var logDir: java.io.File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, LimpaDroidDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    repository = ScanRepository(
      sessionDao = db.scanSessionDao(),
      candidateDao = db.scanCandidateDao(),
      errorDao = db.scanErrorDao(),
    )
    storageRoot = tempFolder.newFolder("shared_storage").toPath().toRealPath()
    logDir = tempFolder.newFolder("logs")
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `repository deduplicates candidates by session and path and persists errors`() = runTest {
    val session = repository.createSession(ScanScope.INFRASTRUCTURE, storageRoot.toString())
    val candidate = Candidate(
      path = "${storageRoot}/Download/sample.tmp",
      sizeBytes = 512L,
      lastModified = 1000L,
      category = Category.SUPERFLUOUS,
      ruleId = "TEST_RULE",
      confidence = Confidence.HIGH,
    )
    val error = WalkError(
      path = "${storageRoot}/Blocked",
      errorType = "AccessDeniedException",
      message = "Sem permissão",
    )

    repository.appendCandidatesAndErrors(
      sessionId = session.id,
      candidates = listOf(candidate to "motivo", candidate to "duplicado"),
      errors = listOf(error),
    )
    repository.saveProgress(
      sessionId = session.id,
      scannedFiles = 10L,
      scannedDirs = 2L,
      scannedBytes = 4096L,
      checkpoint = WalkCheckpoint(
        lastProcessedPath = candidate.path,
        completedSubtrees = setOf("DCIM"),
        settledFiles = 8L,
        settledDirs = 1L,
        settledBytes = 2048L,
      ),
    )

    val savedCandidates = repository.observeCandidates(session.id).first()
    val savedErrors = repository.observeRecentErrors(session.id).first()
    val reloadedSession = repository.getSession(session.id)

    assertEquals(1, savedCandidates.size)
    assertEquals(1, savedErrors.size)
    assertNotNull(reloadedSession)
    assertEquals(1, reloadedSession?.candidatesCount)
    assertEquals(1, reloadedSession?.errorCount)
    assertEquals(setOf("DCIM"), reloadedSession?.completedSubtrees)
    assertEquals(8L, reloadedSession?.settledFiles)
  }

  @Test
  fun `ScanWorker completes full scan and logs unreadable directory without aborting`() = runTest {
    val okDir = Files.createDirectories(storageRoot.resolve("Documents"))
    Files.writeString(okDir.resolve("report.txt"), "123456")
    val blockedDir = Files.createDirectories(storageRoot.resolve("Restricted"))
    Files.writeString(blockedDir.resolve("hidden.txt"), "hidden")

    val session = repository.createSession(ScanScope.INFRASTRUCTURE, storageRoot.toString())
    val worker = buildWorker(
      sessionId = session.id,
      resume = false,
      readPermissionChecker = { path -> path.fileName?.toString() != "Restricted" },
    )
    val result = worker.doWork()

    assertTrue(result is ListenableWorker.Result.Success)
    val finished = repository.getSession(session.id)!!
    val errors = repository.observeRecentErrors(session.id).first()

    assertEquals(ScanStatus.COMPLETED, finished.status)
    assertEquals(1L, finished.scannedFiles)
    assertEquals(6L, finished.scannedBytes)
    assertTrue(errors.any { it.path == blockedDir.toString() })
  }

  @Test
  fun `ScanWorker persists PAUSED checkpoint when cancelled mid-scan and resumes to exact totals`() = runTest {
    val doneDir = Files.createDirectories(storageRoot.resolve("DoneFolder"))
    Files.writeString(doneDir.resolve("a.txt"), "1234")
    val todoDir = Files.createDirectories(storageRoot.resolve("TodoFolder"))
    Files.writeString(todoDir.resolve("b.txt"), "12345678")

    val checkpointReached = CompletableDeferred<Unit>()
    val firstRun = AtomicBoolean(true)
    val blockingGate = CompletableDeferred<Unit>()

    val controllableEngine = object : ScanEngine {
      override val scope: ScanScope = ScanScope.INFRASTRUCTURE
      override val category: Category? = null

      override fun scan(
        config: ScanConfig,
        latestCheckpointRef: AtomicReference<WalkCheckpoint>?,
      ): Flow<ScanEvent> = flow {
        if (firstRun.compareAndSet(true, false)) {
          val cp = WalkCheckpoint(
            lastProcessedPath = doneDir.resolve("a.txt").toString(),
            completedSubtrees = setOf("DoneFolder"),
            settledFiles = 1L,
            settledDirs = 1L,
            settledBytes = 4L,
          )
          emit(ScanEvent.Progress(1L, 1L, 4L, cp.lastProcessedPath, 0, cp))
          checkpointReached.complete(Unit)
          blockingGate.await()
        } else {
          InfrastructureScanEngine(FileWalker()).scan(config, latestCheckpointRef).collect {
            emit(it)
          }
        }
      }
    }

    val session = repository.createSession(ScanScope.INFRASTRUCTURE, storageRoot.toString())
    val worker1 = buildWorker(session.id, resume = false, customEngine = controllableEngine)

    val job = launch(Dispatchers.Default) {
      try {
        worker1.doWork()
      } catch (_: CancellationException) {
        // Expected on cooperative worker cancellation
      }
    }
    checkpointReached.await()
    job.cancel()
    job.join()

    val paused = repository.getSession(session.id)!!
    assertEquals(ScanStatus.PAUSED, paused.status)
    assertEquals(setOf("DoneFolder"), paused.completedSubtrees)
    assertEquals(1L, paused.settledFiles)

    repository.markRunningForResume(session.id)
    val worker2 = buildWorker(session.id, resume = true, customEngine = controllableEngine)
    val resumedResult = withContext(Dispatchers.Default) { worker2.doWork() }

    assertTrue(resumedResult is ListenableWorker.Result.Success)
    val completed = repository.getSession(session.id)!!
    assertEquals(ScanStatus.COMPLETED, completed.status)
    assertEquals(2L, completed.scannedFiles)
    assertEquals(12L, completed.scannedBytes)
  }

  @Test
  @Config(sdk = [30])
  fun `ScanWorker resumes paused session skipping completed subtrees on API 30`() = runTest {
    val doneDir = Files.createDirectories(storageRoot.resolve("DoneSubtree"))
    Files.writeString(doneDir.resolve("old.bin"), "1234")
    val todoDir = Files.createDirectories(storageRoot.resolve("PendingSubtree"))
    Files.writeString(todoDir.resolve("new.bin"), "12345678")

    val session = repository.createSession(ScanScope.INFRASTRUCTURE, storageRoot.toString())
    repository.saveProgress(
      sessionId = session.id,
      scannedFiles = 1L,
      scannedDirs = 1L,
      scannedBytes = 4L,
      checkpoint = WalkCheckpoint(
        lastProcessedPath = doneDir.resolve("old.bin").toString(),
        completedSubtrees = setOf("DoneSubtree"),
        settledFiles = 1L,
        settledDirs = 1L,
        settledBytes = 4L,
      ),
    )
    repository.markRequestedStatus(session.id, ScanStatus.PAUSED)
    repository.markRunningForResume(session.id)

    val worker = buildWorker(session.id, resume = true)
    val result = worker.doWork()

    assertTrue(result is ListenableWorker.Result.Success)
    val finished = repository.getSession(session.id)!!
    assertEquals(ScanStatus.COMPLETED, finished.status)
    assertEquals(2L, finished.scannedFiles)
    assertEquals(12L, finished.scannedBytes)
  }

  private fun buildWorker(
    sessionId: String,
    resume: Boolean,
    readPermissionChecker: (Path) -> Boolean = Files::isReadable,
    customEngine: ScanEngine? = null,
  ): ScanWorker {
    val deps = ScanWorkerDependencies(
      scanRepository = repository,
      storageVolumeResolver = object : StorageVolumeResolver {
        override fun resolvePrimarySharedStorage(): StorageRootResolution =
          StorageRootResolution.Available(storageRoot, "Armazenamento de teste")
      },
      scanEngine = customEngine ?: InfrastructureScanEngine(FileWalker(readPermissionChecker = readPermissionChecker)),
      scanLogger = LocalScanLogger(logDir),
      applicationId = "io.github.km20197.limpadroid",
    )

    val factory = object : WorkerFactory() {
      override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
      ): ListenableWorker = ScanWorker(appContext, workerParameters, deps)
    }

    return TestListenableWorkerBuilder<ScanWorker>(context)
      .setWorkerFactory(factory)
      .setInputData(
        workDataOf(
          ScanWorker.KEY_SESSION_ID to sessionId,
          ScanWorker.KEY_RESUME to resume,
        ),
      )
      .build()
  }
}
