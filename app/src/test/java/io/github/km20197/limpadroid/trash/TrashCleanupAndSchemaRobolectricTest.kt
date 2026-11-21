package io.github.km20197.limpadroid.trash

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.km20197.limpadroid.data.db.LimpaDroidDatabase
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.LocalScanLogger
import io.github.km20197.limpadroid.data.scan.ScanRepository
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class TrashCleanupAndSchemaRobolectricTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var context: Context
  private lateinit var db: LimpaDroidDatabase
  private lateinit var storageRoot: Path
  private lateinit var logDir: File
  private lateinit var cleanupJobsDir: File
  private lateinit var scanRepository: ScanRepository
  private lateinit var trashRepository: TrashRepository
  private lateinit var scannedPaths: MutableList<String>

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, LimpaDroidDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    storageRoot = tempFolder.newFolder("shared_storage").toPath().toRealPath()
    logDir = tempFolder.newFolder("logs")
    cleanupJobsDir = tempFolder.newFolder("cleanup_jobs")
    scannedPaths = mutableListOf()

    scanRepository = ScanRepository(
      sessionDao = db.scanSessionDao(),
      candidateDao = db.scanCandidateDao(),
      errorDao = db.scanErrorDao(),
    )
    trashRepository = TrashRepository(
      trashEntryDao = db.trashEntryDao(),
      operationHistoryDao = db.operationHistoryDao(),
      storageVolumeResolver = object : StorageVolumeResolver {
        override fun resolvePrimarySharedStorage(): StorageRootResolution =
          StorageRootResolution.Available(storageRoot, "Armazenamento de teste")
      },
      mediaScannerHook = MediaScannerHook { paths -> scannedPaths.addAll(paths) },
      scanLogger = LocalScanLogger(logDir),
      applicationId = "io.github.km20197.limpadroid",
      clock = AppClock { 1_700_000_000_000L },
      scanCandidateDao = db.scanCandidateDao(),
    )
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `M5 TrashCleanupWorker moves trash candidates repairs MediaStore removes scan candidates and deletes job file`() =
    runTest {
      val dlDir = Files.createDirectories(storageRoot.resolve("Download"))
      val partFile = Files.writeString(dlDir.resolve("incompleto.part"), "dados-parciais")
      val mtime = 1_690_000_000_000L
      Files.setLastModifiedTime(partFile, FileTime.fromMillis(mtime))
      val missingMedia = storageRoot.resolve("Pictures/ausente.jpg")

      val session = scanRepository.createSession(ScanScope.SUPERFLUOUS, storageRoot.toString())
      scanRepository.appendFoundEventsAndErrors(
        sessionId = session.id,
        foundEvents = listOf(
          ScanEvent.Found(
            candidate = Candidate(
              path = partFile.toString(),
              sizeBytes = Files.size(partFile),
              lastModified = mtime,
              category = Category.SUPERFLUOUS,
              ruleId = SuperfluousRuleId.PARTIAL_DL.ruleId,
              confidence = Confidence.HIGH,
              preselected = true,
            ),
          ),
          ScanEvent.Found(
            candidate = Candidate(
              path = missingMedia.toString(),
              sizeBytes = 0L,
              lastModified = 100L,
              category = Category.SUPERFLUOUS,
              ruleId = SuperfluousRuleId.STALE_MEDIASTORE.ruleId,
              confidence = Confidence.HIGH,
              preselected = true,
            ),
          ),
        ),
        errors = emptyList(),
      )
      val savedRecords = scanRepository.observeCategoryCandidates(Category.SUPERFLUOUS).first()
      assertEquals(2, savedRecords.size)

      val jobId = "job-m5-worker"
      val jobFile = File(cleanupJobsDir, "$jobId.json")
      val payload = CleanupJobPayload(
        sessionId = session.id,
        candidateIds = savedRecords.map { it.id },
        excludedPaths = emptyList(),
      )
      jobFile.writeText(Json.encodeToString(payload), Charsets.UTF_8)

      val deps = TrashCleanupDependencies(
        trashRepository = trashRepository,
        scanRepository = scanRepository,
        scanLogger = LocalScanLogger(logDir),
        cleanupJobsDir = cleanupJobsDir,
      )
      val worker = TestListenableWorkerBuilder<TrashCleanupWorker>(context)
        .setInputData(workDataOf(TrashCleanupWorker.KEY_JOB_ID to jobId))
        .setWorkerFactory(
          object : WorkerFactory() {
            override fun createWorker(
              appContext: Context,
              workerClassName: String,
              workerParameters: WorkerParameters,
            ): ListenableWorker = TrashCleanupWorker(appContext, workerParameters, deps)
          },
        )
        .build()

      val result = worker.doWork()
      assertTrue(result is ListenableWorker.Result.Success)
      val out = (result as ListenableWorker.Result.Success).outputData
      assertEquals(1, out.getInt(TrashCleanupWorker.KEY_MOVED_COUNT, -1))
      assertEquals(14L, out.getLong(TrashCleanupWorker.KEY_MOVED_BYTES, -1L))
      assertEquals(1, out.getInt(TrashCleanupWorker.KEY_REPAIRED_COUNT, -1))
      assertEquals(0, out.getInt(TrashCleanupWorker.KEY_SKIPPED_OR_FAILED_COUNT, -1))

      assertFalse(Files.exists(partFile, LinkOption.NOFOLLOW_LINKS))
      assertEquals(1, trashRepository.inTrashEntries.first().size)
      assertTrue(scannedPaths.contains(missingMedia.toString()))
      assertTrue(scanRepository.observeCategoryCandidates(Category.SUPERFLUOUS).first().isEmpty())
      assertFalse(jobFile.exists())
    }

  @Test
  fun `M5 TrashCleanupWorker rejects path traversal jobId`() = runTest {
    val deps = TrashCleanupDependencies(
      trashRepository = trashRepository,
      scanRepository = scanRepository,
      scanLogger = LocalScanLogger(logDir),
      cleanupJobsDir = cleanupJobsDir,
    )
    val worker = TestListenableWorkerBuilder<TrashCleanupWorker>(context)
      .setInputData(workDataOf(TrashCleanupWorker.KEY_JOB_ID to "../outside"))
      .setWorkerFactory(
        object : WorkerFactory() {
          override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
          ): ListenableWorker = TrashCleanupWorker(appContext, workerParameters, deps)
        },
      )
      .build()

    val result = worker.doWork()
    assertTrue(result is ListenableWorker.Result.Failure)
  }

  @Test
  fun `M5 WorkManagerTrashController completes cleanup even if caller coroutine scope is cancelled`() =
    runBlocking {
      val workerStarted = CompletableDeferred<Unit>()
      val releaseWorker = CompletableDeferred<Unit>()
      val deps = TrashCleanupDependencies(
        trashRepository = trashRepository,
        scanRepository = scanRepository,
        scanLogger = LocalScanLogger(logDir),
        cleanupJobsDir = cleanupJobsDir,
      )

      val config = Configuration.Builder()
        .setMinimumLoggingLevel(Log.DEBUG)
        .setExecutor(SynchronousExecutor())
        .setTaskExecutor(SynchronousExecutor())
        .setWorkerFactory(
          object : WorkerFactory() {
            override fun createWorker(
              appContext: Context,
              workerClassName: String,
              workerParameters: WorkerParameters,
            ): ListenableWorker? {
              if (workerClassName != TrashCleanupWorker::class.java.name) return null
              val inner = TrashCleanupWorker(appContext, workerParameters, deps)
              return object : androidx.work.CoroutineWorker(appContext, workerParameters) {
                override suspend fun doWork(): Result {
                  workerStarted.complete(Unit)
                  releaseWorker.await()
                  return inner.doWork()
                }
              }
            }
          },
        )
        .build()

      WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
      val workManager = WorkManager.getInstance(context)

      val dlDir = Files.createDirectories(storageRoot.resolve("Download"))
      val target = Files.writeString(dlDir.resolve("sair_da_tela.tmp"), "resiste-cancelamento")
      val mtime = 1_691_000_000_000L
      Files.setLastModifiedTime(target, FileTime.fromMillis(mtime))

      val session = scanRepository.createSession(ScanScope.SUPERFLUOUS, storageRoot.toString())
      scanRepository.appendFoundEventsAndErrors(
        sessionId = session.id,
        foundEvents = listOf(
          ScanEvent.Found(
            candidate = Candidate(
              path = target.toString(),
              sizeBytes = Files.size(target),
              lastModified = mtime,
              category = Category.SUPERFLUOUS,
              ruleId = SuperfluousRuleId.PARTIAL_DL.ruleId,
              confidence = Confidence.HIGH,
              preselected = true,
            ),
          ),
        ),
        errors = emptyList(),
      )
      val records = scanRepository.observeCategoryCandidates(Category.SUPERFLUOUS).first()
      assertEquals(1, records.size)

      val controller = WorkManagerTrashController(
        context = context,
        trashRepository = trashRepository,
        cleanupJobsDir = cleanupJobsDir,
      )

      // Simula viewModelScope chamando a limpeza e sendo cancelado quando o usuário sai da tela
      val uiJob = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        controller.runBatchCleanup(
          sessionId = session.id,
          records = records,
          userExcludedPaths = emptySet(),
        )
      }
      workerStarted.await()
      uiJob.cancel()
      uiJob.join()

      // Libera a conclusão do worker independente do cancelamento da tela
      releaseWorker.complete(Unit)
      val finishedInfos = workManager
        .getWorkInfosForUniqueWorkFlow(WorkManagerTrashController.UNIQUE_CLEANUP_WORK_NAME)
        .first { list -> list.isNotEmpty() && list.all { it.state.isFinished } }

      assertEquals(WorkInfo.State.SUCCEEDED, finishedInfos.single().state)
      assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
      assertEquals(1, trashRepository.inTrashEntries.first().size)
      assertTrue(scanRepository.observeCategoryCandidates(Category.SUPERFLUOUS).first().isEmpty())
    }

  @Test
  fun `exported Room schema v4 json exists and matches live LimpaDroidDatabase tables and columns`() {
    val schemaFile = listOf(
      File("schemas/io.github.km20197.limpadroid.data.db.LimpaDroidDatabase/4.json"),
      File("app/schemas/io.github.km20197.limpadroid.data.db.LimpaDroidDatabase/4.json"),
    ).firstOrNull { it.isFile }
    assertNotNull("Arquivo de schema Room v4 deve existir após KSP", schemaFile)

    val rootObj = Json.parseToJsonElement(schemaFile!!.readText(Charsets.UTF_8)).jsonObject
    val dbObj = rootObj["database"]!!.jsonObject
    assertEquals(4, dbObj["version"]!!.jsonPrimitive.intOrNull)

    val entities = dbObj["entities"]!!.jsonArray
    assertEquals(7, entities.size)

    val sqliteDb = db.openHelper.readableDatabase
    for (entityElement in entities) {
      val entityObj = entityElement.jsonObject
      val tableName = entityObj["tableName"]!!.jsonPrimitive.content
      val expectedColumns = entityObj["fields"]!!.jsonArray
        .map { it.jsonObject["columnName"]!!.jsonPrimitive.content }
        .toSet()

      val actualColumns = mutableSetOf<String>()
      sqliteDb.query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIdx = cursor.getColumnIndexOrThrow("name")
        while (cursor.moveToNext()) {
          actualColumns.add(cursor.getString(nameIdx))
        }
      }
      assertEquals("Colunas da tabela $tableName devem coincidir com 4.json", expectedColumns, actualColumns)
    }
  }
}
