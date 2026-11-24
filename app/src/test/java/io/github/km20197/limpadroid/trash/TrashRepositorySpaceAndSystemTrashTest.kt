package io.github.km20197.limpadroid.trash

import io.github.km20197.limpadroid.data.db.OperationHistoryDao
import io.github.km20197.limpadroid.data.db.OperationHistoryEntity
import io.github.km20197.limpadroid.data.db.TrashEntryDao
import io.github.km20197.limpadroid.data.db.TrashEntryEntity
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook
import io.github.km20197.limpadroid.domain.fs.SystemTrashGateway
import io.github.km20197.limpadroid.domain.fs.SystemTrashPurgeResult
import io.github.km20197.limpadroid.domain.fs.SystemTrashStats
import io.github.km20197.limpadroid.domain.fs.TrashStorage
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.OperationType
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.model.TrashEntryState
import io.github.km20197.limpadroid.domain.model.TrashMoveResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrashRepositorySpaceAndSystemTrashTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `moveToTrash rejects cleanup when usableSpace is below 100 MiB or provider fails`() = runTest {
    val storageRoot = tempFolder.newFolder("low_space").toPath().toRealPath()
    val dl = Files.createDirectories(storageRoot.resolve("Download"))
    val file = Files.writeString(dl.resolve("temp.tmp"), "payload")
    val mtime = Files.getLastModifiedTime(file).toMillis()
    val candidate = Candidate(
      path = file.toString(),
      sizeBytes = 7L,
      lastModified = mtime,
      category = Category.SUPERFLUOUS,
      ruleId = "PARTIAL_DL",
      confidence = Confidence.HIGH,
    )

    val probedPaths = mutableListOf<Path>()
    val dao = InMemoryTrashEntryDao()
    val historyDao = InMemoryOperationHistoryDao()
    val logger = RecordingScanLogger()

    val belowMarginRepo = createRepository(
      storageRoot = storageRoot,
      trashEntryDao = dao,
      operationHistoryDao = historyDao,
      scanLogger = logger,
      usableSpaceProvider = { probe ->
        probedPaths.add(probe)
        TrashRepository.CLEANUP_SAFETY_MARGIN_BYTES - 1L
      },
    )

    val lowResult = belowMarginRepo.moveToTrash(candidate)
    assertTrue(lowResult is TrashMoveResult.Failed)
    assertTrue(Files.exists(file))
    // O diretório .lixeira ainda não existe: a sonda deve subir para o ancestral existente.
    assertTrue(probedPaths.single() == storageRoot)
    assertTrue(dao.allEntries().isEmpty())
    assertTrue(logger.events.any { it.first == "TRASH_MOVE_LOW_SPACE" })

    val failingRepo = createRepository(
      storageRoot = storageRoot,
      trashEntryDao = dao,
      operationHistoryDao = historyDao,
      scanLogger = logger,
      usableSpaceProvider = { error("stat indisponível") },
    )
    val errResult = failingRepo.moveToTrash(candidate)
    assertTrue(errResult is TrashMoveResult.Failed)
    assertTrue(Files.exists(file))
  }

  @Test
  fun `moveCandidatesToTrash aborts batch without moving files when usableSpace is below 100 MiB`() = runTest {
    val storageRoot = tempFolder.newFolder("batch_low_space").toPath().toRealPath()
    val dl = Files.createDirectories(storageRoot.resolve("Download"))
    val file1 = Files.writeString(dl.resolve("a.tmp"), "aaaa")
    val file2 = Files.writeString(dl.resolve("b.tmp"), "bbbb")

    val dao = InMemoryTrashEntryDao()
    val historyDao = InMemoryOperationHistoryDao()
    val logger = RecordingScanLogger()
    val repo = createRepository(
      storageRoot = storageRoot,
      trashEntryDao = dao,
      operationHistoryDao = historyDao,
      scanLogger = logger,
      usableSpaceProvider = { 50L * 1024L * 1024L },
    )

    val summary = repo.moveCandidatesToTrash(
      records = listOf(
        ScanCandidateRecord(
          id = 1L,
          sessionId = "s1",
          candidate = Candidate(
            path = file1.toString(),
            sizeBytes = 4L,
            lastModified = Files.getLastModifiedTime(file1).toMillis(),
            category = Category.SUPERFLUOUS,
            ruleId = "PARTIAL_DL",
            confidence = Confidence.HIGH,
          ),
        ),
        ScanCandidateRecord(
          id = 2L,
          sessionId = "s1",
          candidate = Candidate(
            path = file2.toString(),
            sizeBytes = 4L,
            lastModified = Files.getLastModifiedTime(file2).toMillis(),
            category = Category.SUPERFLUOUS,
            ruleId = "PARTIAL_DL",
            confidence = Confidence.HIGH,
          ),
        ),
      ),
    )

    assertTrue(summary.movedCandidateIds.isEmpty())
    assertEquals(2, summary.failedCount)
    assertTrue(Files.exists(file1))
    assertTrue(Files.exists(file2))
    assertTrue(logger.events.any { it.first == "TRASH_MOVE_LOW_SPACE" })
  }

  @Test
  fun `emptyTrash clears both internal trash and Android system trash and resets stats`() = runTest {
    val storageRoot = tempFolder.newFolder("empty_both").toPath().toRealPath()
    val trashStorage = TrashStorage(usableSpaceProvider = { 512L * 1024L * 1024L })
    val trashRoot = trashStorage.resolveTrashRoot(storageRoot)
    trashStorage.ensureTrashRootInitialized(trashRoot)

    val deletedAt = 1_700_000_000_000L
    val entryDir = trashStorage.allocateEntryDir(trashRoot, "entry-1", deletedAt)
    val payloadPath = trashStorage.resolvePayloadPath(entryDir)
    Files.createDirectories(entryDir)
    Files.writeString(payloadPath, "interno")

    val dao = InMemoryTrashEntryDao()
    val historyDao = InMemoryOperationHistoryDao()
    val logger = RecordingScanLogger()
    val scannedPaths = mutableListOf<String>()
    val fakeSystemTrash = FakeSystemTrashGateway(
      stats = SystemTrashStats(itemCount = 2, totalBytes = 4096L),
      purgeResult = SystemTrashPurgeResult(purgedCount = 2, freedBytes = 4096L),
    )
    val repository = createRepository(
      storageRoot = storageRoot,
      trashEntryDao = dao,
      operationHistoryDao = historyDao,
      scanLogger = logger,
      trashStorage = trashStorage,
      mediaScannerHook = MediaScannerHook { paths -> scannedPaths.addAll(paths) },
      systemTrashGateway = fakeSystemTrash,
      usableSpaceProvider = { 512L * 1024L * 1024L },
    )

    val originalPath = storageRoot.resolve("Download/interno.tmp").toString()
    dao.upsert(
      TrashEntryEntity(
        id = "entry-1",
        originalPath = originalPath,
        trashPath = payloadPath.toString(),
        sizeBytes = 7L,
        sha256 = null,
        originalMtime = 100L,
        category = Category.SUPERFLUOUS.name,
        ruleId = "PARTIAL_DL",
        deletedAt = deletedAt,
        expiresAt = deletedAt + 86_400_000L,
        state = TrashEntryState.IN_TRASH.name,
      ),
    )

    repository.refreshSystemTrashStats()
    val beforeStats = repository.trashStats.first()
    assertEquals(1, beforeStats.inTrashCount)
    assertEquals(2, beforeStats.systemTrashCount)
    assertEquals(4096L, beforeStats.systemTrashBytes)

    val result = repository.emptyTrash()

    assertEquals(3, result.purgedCount)
    assertEquals(4103L, result.freedBytes)
    assertTrue(fakeSystemTrash.emptyCalled)
    assertFalse(Files.exists(payloadPath))
    assertFalse(Files.exists(entryDir))
    assertFalse(Files.exists(entryDir.parent))
    assertTrue(scannedPaths.contains(originalPath))
    assertTrue(scannedPaths.contains(payloadPath.toString()))

    val afterStats = repository.trashStats.first()
    assertEquals(0, afterStats.inTrashCount)
    assertEquals(0, afterStats.systemTrashCount)
    assertEquals(0L, afterStats.systemTrashBytes)
    assertEquals(1, historyDao.records.size)
    assertEquals(OperationType.EMPTY_TRASH.name, historyDao.records.single().operationType)
    assertEquals(3, historyDao.records.single().itemCount)
    assertEquals(4103L, historyDao.records.single().totalBytes)
  }

  @Test
  fun `emptyTrash clears Android system trash even when internal trash is empty`() = runTest {
    val storageRoot = tempFolder.newFolder("empty_sys_only").toPath().toRealPath()
    val dao = InMemoryTrashEntryDao()
    val historyDao = InMemoryOperationHistoryDao()
    val logger = RecordingScanLogger()
    val fakeSystemTrash = FakeSystemTrashGateway(
      stats = SystemTrashStats(itemCount = 3, totalBytes = 12_288L),
      purgeResult = SystemTrashPurgeResult(purgedCount = 3, freedBytes = 12_288L),
    )
    val repository = createRepository(
      storageRoot = storageRoot,
      trashEntryDao = dao,
      operationHistoryDao = historyDao,
      scanLogger = logger,
      systemTrashGateway = fakeSystemTrash,
      usableSpaceProvider = { 512L * 1024L * 1024L },
    )

    repository.refreshSystemTrashStats()
    assertEquals(3, repository.trashStats.first().systemTrashCount)

    val summary = repository.emptyTrash()

    assertEquals(3, summary.purgedCount)
    assertEquals(12_288L, summary.freedBytes)
    assertTrue(fakeSystemTrash.emptyCalled)
    assertEquals(0, repository.trashStats.first().systemTrashCount)
    assertEquals(1, historyDao.records.size)
  }

  private fun createRepository(
    storageRoot: Path,
    trashEntryDao: TrashEntryDao,
    operationHistoryDao: OperationHistoryDao,
    scanLogger: ScanLogger,
    trashStorage: TrashStorage = TrashStorage(usableSpaceProvider = { 512L * 1024L * 1024L }),
    mediaScannerHook: MediaScannerHook = MediaScannerHook { },
    systemTrashGateway: SystemTrashGateway = SystemTrashGateway.NoOp,
    usableSpaceProvider: (Path) -> Long = { 512L * 1024L * 1024L },
  ): TrashRepository = TrashRepository(
    trashEntryDao = trashEntryDao,
    operationHistoryDao = operationHistoryDao,
    storageVolumeResolver = object : StorageVolumeResolver {
      override fun resolvePrimarySharedStorage(): StorageRootResolution =
        StorageRootResolution.Available(storageRoot, "Teste")
    },
    mediaScannerHook = mediaScannerHook,
    scanLogger = scanLogger,
    applicationId = "io.github.km20197.limpadroid",
    trashStorage = trashStorage,
    clock = AppClock { 1_700_000_500_000L },
    systemTrashGateway = systemTrashGateway,
    usableSpaceProvider = usableSpaceProvider,
  )

  private class FakeSystemTrashGateway(
    var stats: SystemTrashStats,
    private val purgeResult: SystemTrashPurgeResult,
  ) : SystemTrashGateway {
    var emptyCalled = false

    override suspend fun queryStats(): SystemTrashStats = stats

    override suspend fun emptySystemTrash(): SystemTrashPurgeResult {
      emptyCalled = true
      stats = SystemTrashStats()
      return purgeResult
    }
  }

  private class RecordingScanLogger : ScanLogger {
    val events = mutableListOf<Pair<String, String>>()
    override fun logEvent(tag: String, message: String) {
      events.add(tag to message)
    }
  }

  private class InMemoryTrashEntryDao : TrashEntryDao {
    private val entries = linkedMapOf<String, TrashEntryEntity>()
    private val flow = MutableStateFlow<List<TrashEntryEntity>>(emptyList())

    fun allEntries(): List<TrashEntryEntity> = entries.values.toList()

    override fun observeInTrashEntries(): Flow<List<TrashEntryEntity>> =
      flow.map { list -> list.filter { it.state == TrashEntryState.IN_TRASH.name }.sortedByDescending { it.deletedAt } }

    override fun observePurgedEntries(): Flow<List<TrashEntryEntity>> =
      flow.map { list -> list.filter { it.state == TrashEntryState.PURGED.name } }

    override suspend fun getById(id: String): TrashEntryEntity? = entries[id]

    override suspend fun getByState(state: String): List<TrashEntryEntity> =
      entries.values.filter { it.state == state }.sortedByDescending { it.deletedAt }

    override suspend fun getExpiredEntries(nowMillis: Long): List<TrashEntryEntity> =
      entries.values.filter { it.state == TrashEntryState.IN_TRASH.name && it.expiresAt <= nowMillis }

    override suspend fun upsert(entry: TrashEntryEntity) {
      entries[entry.id] = entry
      flow.value = entries.values.toList()
    }

    override suspend fun updateMovedState(
      id: String,
      state: String,
      trashPath: String,
      sizeBytes: Long,
      sha256: String?,
      originalMtime: Long,
      failureReason: String?,
    ) {
      val current = entries[id] ?: return
      upsert(
        current.copy(
          state = state,
          trashPath = trashPath,
          sizeBytes = sizeBytes,
          sha256 = sha256,
          originalMtime = originalMtime,
          failureReason = failureReason,
        ),
      )
    }

    override suspend fun updateLifecycleState(
      id: String,
      state: String,
      restoredAt: Long?,
      restoredPath: String?,
      purgedAt: Long?,
      failureReason: String?,
    ) {
      val current = entries[id] ?: return
      upsert(
        current.copy(
          state = state,
          restoredAt = restoredAt,
          restoredPath = restoredPath,
          purgedAt = purgedAt,
          failureReason = failureReason,
        ),
      )
    }
  }

  private class InMemoryOperationHistoryDao : OperationHistoryDao {
    val records = mutableListOf<OperationHistoryEntity>()
    private val flow = MutableStateFlow<List<OperationHistoryEntity>>(emptyList())

    override fun observeRecent(limit: Int): Flow<List<OperationHistoryEntity>> =
      flow.map { it.take(limit) }

    override suspend fun insert(record: OperationHistoryEntity): Long {
      val assignedId = (records.size + 1).toLong()
      records.add(record.copy(id = assignedId))
      flow.value = records.sortedByDescending { it.timestamp }
      return assignedId
    }
  }
}
