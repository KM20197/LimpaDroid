package io.github.km20197.limpadroid.trash

import io.github.km20197.limpadroid.data.db.OperationHistoryDao
import io.github.km20197.limpadroid.data.db.OperationHistoryEntity
import io.github.km20197.limpadroid.data.db.TrashEntryDao
import io.github.km20197.limpadroid.data.db.TrashEntryEntity
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook
import io.github.km20197.limpadroid.domain.fs.TrashStorage
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.TrashEntryState
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrashRepositoryManualPurgeTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `deletePermanently rejects unmanaged trashPath without throwing or deleting target`() = runTest {
    val storageRoot = tempFolder.newFolder("storage").toPath()
    val trashStorage = TrashStorage()
    val trashRoot = trashStorage.resolveTrashRoot(storageRoot)
    trashStorage.ensureTrashRootInitialized(trashRoot)

    val outsideFile = storageRoot.resolve("Documents").resolve("importante.txt")
    Files.createDirectories(outsideFile.parent)
    Files.writeString(outsideFile, "nao apagar")

    val dao = InMemoryTrashEntryDao()
    val historyDao = InMemoryOperationHistoryDao()
    val logger = RecordingScanLogger()
    val repository = createRepository(storageRoot, dao, historyDao, logger, trashStorage)

    val entry = TrashEntryEntity(
      id = "entry-outside",
      originalPath = outsideFile.toString(),
      trashPath = outsideFile.toString(),
      sizeBytes = 10L,
      sha256 = null,
      originalMtime = 100L,
      category = Category.SUPERFLUOUS.name,
      ruleId = "PARTIAL_DL",
      deletedAt = 1_700_000_000_000L,
      expiresAt = 1_702_592_000_000L,
      state = TrashEntryState.IN_TRASH.name,
    )
    dao.upsert(entry)

    val summary = repository.deletePermanently(entry.id)

    assertEquals(0, summary.purgedCount)
    assertEquals(0L, summary.freedBytes)
    assertTrue(Files.exists(outsideFile))
    assertEquals(TrashEntryState.FAILED.name, dao.getById(entry.id)?.state)
    assertTrue(dao.getById(entry.id)?.failureReason != null)
    assertTrue(historyDao.records.isEmpty())
    assertTrue(logger.events.any { it.first == "TRASH_PURGE_MANUAL_FAILED" })
  }

  @Test
  fun `deletePermanently handles malformed trashPath without throwing`() = runTest {
    val storageRoot = tempFolder.newFolder("storage-malformed").toPath()
    val trashStorage = TrashStorage()
    val trashRoot = trashStorage.resolveTrashRoot(storageRoot)
    trashStorage.ensureTrashRootInitialized(trashRoot)

    val dao = InMemoryTrashEntryDao()
    val historyDao = InMemoryOperationHistoryDao()
    val logger = RecordingScanLogger()
    val repository = createRepository(storageRoot, dao, historyDao, logger, trashStorage)

    val entry = TrashEntryEntity(
      id = "entry-malformed",
      originalPath = storageRoot.resolve("Download/a.tmp").toString(),
      trashPath = "caminho\u0000invalido",
      sizeBytes = 64L,
      sha256 = null,
      originalMtime = 100L,
      category = Category.SUPERFLUOUS.name,
      ruleId = "PARTIAL_DL",
      deletedAt = 1_700_000_000_000L,
      expiresAt = 1_702_592_000_000L,
      state = TrashEntryState.IN_TRASH.name,
    )
    dao.upsert(entry)

    val summary = repository.deletePermanently(entry.id)

    assertEquals(0, summary.purgedCount)
    assertEquals(0L, summary.freedBytes)
    assertEquals(TrashEntryState.FAILED.name, dao.getById(entry.id)?.state)
    assertTrue(dao.getById(entry.id)?.failureReason != null)
    assertTrue(historyDao.records.isEmpty())
    assertTrue(logger.events.any { it.first == "TRASH_PURGE_MANUAL_FAILED" })
  }

  @Test
  fun `deletePermanently deletes managed trash payload and updates state and history`() = runTest {
    val storageRoot = tempFolder.newFolder("storage-valid").toPath()
    val trashStorage = TrashStorage()
    val trashRoot = trashStorage.resolveTrashRoot(storageRoot)
    trashStorage.ensureTrashRootInitialized(trashRoot)

    val deletedAt = 1_700_000_000_000L
    val entryId = "entry-valid"
    val entryDir = trashStorage.allocateEntryDir(trashRoot, entryId, deletedAt)
    val payloadPath = trashStorage.resolvePayloadPath(entryDir)
    Files.createDirectories(entryDir)
    Files.writeString(payloadPath, "lixo")

    val dao = InMemoryTrashEntryDao()
    val historyDao = InMemoryOperationHistoryDao()
    val logger = RecordingScanLogger()
    val repository = createRepository(storageRoot, dao, historyDao, logger, trashStorage)

    val entry = TrashEntryEntity(
      id = entryId,
      originalPath = storageRoot.resolve("Download/item.tmp").toString(),
      trashPath = payloadPath.toString(),
      sizeBytes = 4L,
      sha256 = null,
      originalMtime = 100L,
      category = Category.SUPERFLUOUS.name,
      ruleId = "PARTIAL_DL",
      deletedAt = deletedAt,
      expiresAt = deletedAt + 86_400_000L,
      state = TrashEntryState.IN_TRASH.name,
    )
    dao.upsert(entry)

    val summary = repository.deletePermanently(entryId)

    assertEquals(1, summary.purgedCount)
    assertEquals(4L, summary.freedBytes)
    assertFalse(Files.exists(payloadPath))
    assertFalse(Files.exists(entryDir))
    assertEquals(TrashEntryState.PURGED.name, dao.getById(entryId)?.state)
    assertEquals(1, historyDao.records.size)
    assertTrue(logger.events.any { it.first == "TRASH_PURGED_MANUAL" })
  }

  private fun createRepository(
    storageRoot: Path,
    trashEntryDao: TrashEntryDao,
    operationHistoryDao: OperationHistoryDao,
    scanLogger: ScanLogger,
    trashStorage: TrashStorage,
  ): TrashRepository = TrashRepository(
    trashEntryDao = trashEntryDao,
    operationHistoryDao = operationHistoryDao,
    storageVolumeResolver = object : StorageVolumeResolver {
      override fun resolvePrimarySharedStorage(): StorageRootResolution =
        StorageRootResolution.Available(storageRoot, "Teste")
    },
    mediaScannerHook = MediaScannerHook { },
    scanLogger = scanLogger,
    applicationId = "io.github.km20197.limpadroid",
    trashStorage = trashStorage,
    clock = AppClock { 1_700_000_500_000L },
  )

  private class RecordingScanLogger : ScanLogger {
    val events = mutableListOf<Pair<String, String>>()
    override fun logEvent(tag: String, message: String) {
      events.add(tag to message)
    }
  }

  private class InMemoryTrashEntryDao : TrashEntryDao {
    private val entries = linkedMapOf<String, TrashEntryEntity>()
    private val flow = MutableStateFlow<List<TrashEntryEntity>>(emptyList())

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
