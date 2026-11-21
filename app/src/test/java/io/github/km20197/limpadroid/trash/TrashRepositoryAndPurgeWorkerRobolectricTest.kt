package io.github.km20197.limpadroid.trash

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.km20197.limpadroid.data.db.LimpaDroidDatabase
import io.github.km20197.limpadroid.data.db.TrashEntryEntity
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.LocalScanLogger
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook
import io.github.km20197.limpadroid.domain.fs.TrashStorage
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.OperationType
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.domain.model.TrashEntryState
import io.github.km20197.limpadroid.domain.model.TrashMetadataPayload
import io.github.km20197.limpadroid.domain.model.TrashMoveResult
import io.github.km20197.limpadroid.domain.model.TrashRestoreResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
class TrashRepositoryAndPurgeWorkerRobolectricTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var context: Context
  private lateinit var db: LimpaDroidDatabase
  private lateinit var storageRoot: Path
  private lateinit var logDir: java.io.File
  private lateinit var scannedPaths: MutableList<String>
  private lateinit var scanBatchCalls: MutableList<List<String>>
  private var currentClockMillis: Long = 1_700_000_000_000L

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, LimpaDroidDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    storageRoot = tempFolder.newFolder("shared_storage").toPath().toRealPath()
    logDir = tempFolder.newFolder("logs")
    scannedPaths = mutableListOf()
    scanBatchCalls = mutableListOf()
    currentClockMillis = 1_700_000_000_000L
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `moveToTrash and restoreEntry preserve content notify MediaStore and update accounting`() = runTest {
    val repo = createRepository()
    val downloadDir = Files.createDirectories(storageRoot.resolve("Download"))
    val targetFile = Files.writeString(downloadDir.resolve("temp.part"), "dados-temporarios")
    val mtime = 1_690_000_000_000L
    Files.setLastModifiedTime(targetFile, FileTime.fromMillis(mtime))

    val candidate = Candidate(
      path = targetFile.toString(),
      sizeBytes = 17L,
      lastModified = mtime,
      category = Category.SUPERFLUOUS,
      ruleId = "PARTIAL_DL",
      confidence = Confidence.HIGH,
    )

    val moveResult = repo.moveToTrash(candidate)
    assertTrue(moveResult is TrashMoveResult.Moved)
    val movedEntry = (moveResult as TrashMoveResult.Moved).entry

    assertFalse(Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS))
    assertTrue(movedEntry.trashPath.contains("Documents/LimpaDroid/.lixeira"))
    assertEquals(movedEntry.deletedAt + TrashEntry.RETENTION_MILLIS, movedEntry.expiresAt)
    assertTrue(scannedPaths.contains(targetFile.toString()))

    val statsInTrash = repo.trashStats.first()
    assertEquals(1, statsInTrash.inTrashCount)
    assertEquals(17L, statsInTrash.inTrashBytes)
    assertEquals(0L, statsInTrash.freedBytes)

    // Restaura mesmo com arquivo novo ocupando o caminho original
    Files.writeString(targetFile, "novo-arquivo")
    val restoreResult = repo.restoreEntry(movedEntry.id)
    assertTrue(restoreResult is TrashRestoreResult.Restored)
    val restored = restoreResult as TrashRestoreResult.Restored
    assertTrue(restored.restoredPath.endsWith("temp (restaurado).part"))
    assertEquals("dados-temporarios", Files.readString(Paths.get(restored.restoredPath)))
    assertTrue(scannedPaths.contains(restored.restoredPath))

    val history = repo.recentHistory.first()
    assertEquals(2, history.size)
    assertEquals(OperationType.RESTORE, history[0].operationType)
    assertEquals(OperationType.MOVE_TO_TRASH, history[1].operationType)
  }

  @Test
  fun `moveToTrash rejects protected paths and stale candidates`() = runTest {
    val repo = createRepository()
    val androidData = Files.createDirectories(storageRoot.resolve("Android/data/com.other"))
    val protectedFile = Files.writeString(androidData.resolve("db.sqlite"), "abc")

    val protectedCandidate = Candidate(
      path = protectedFile.toString(),
      sizeBytes = 3L,
      lastModified = Files.getLastModifiedTime(protectedFile).toMillis(),
      category = Category.SUPERFLUOUS,
      ruleId = "TEST",
      confidence = Confidence.HIGH,
    )
    assertTrue(repo.moveToTrash(protectedCandidate) is TrashMoveResult.Rejected)
    assertTrue(Files.exists(protectedFile, LinkOption.NOFOLLOW_LINKS))

    val docsDir = Files.createDirectories(storageRoot.resolve("Documents"))
    val changedFile = Files.writeString(docsDir.resolve("note.txt"), "123456")
    val staleCandidate = Candidate(
      path = changedFile.toString(),
      sizeBytes = 2L,
      lastModified = Files.getLastModifiedTime(changedFile).toMillis(),
      category = Category.SUPERFLUOUS,
      ruleId = "TEST",
      confidence = Confidence.HIGH,
    )
    assertTrue(repo.moveToTrash(staleCandidate) is TrashMoveResult.StaleCandidate)
    assertTrue(Files.exists(changedFile, LinkOption.NOFOLLOW_LINKS))

    val orphanDir = Files.createDirectories(storageRoot.resolve("Documents/OldApp"))
    val protectedChild = Files.createDirectories(orphanDir.resolve("Manter"))
    val protectedInnerFile = Files.writeString(protectedChild.resolve("doc.txt"), "keep")
    val nestedExclusionResult = repo.moveToTrash(
      Candidate(
        path = orphanDir.toString(),
        sizeBytes = 4L,
        lastModified = Files.getLastModifiedTime(orphanDir).toMillis(),
        category = Category.ORPHAN,
        ruleId = "ORPHAN_ROOT_KNOWN",
        confidence = Confidence.MEDIUM,
        preselected = false,
      ),
      userExcludedPaths = setOf(protectedChild),
    )
    assertTrue(nestedExclusionResult is TrashMoveResult.Rejected)
    assertTrue(Files.exists(protectedInnerFile, LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `PurgeWorker purges expired entries after 30 days using injected clock`() = runTest {
    val repo = createRepository()
    val docsDir = Files.createDirectories(storageRoot.resolve("Documents"))
    val oldFile = Files.writeString(docsDir.resolve("old.tmp"), "1234567890")
    val mtime = 1_690_000_000_000L
    Files.setLastModifiedTime(oldFile, FileTime.fromMillis(mtime))

    val moved = repo.moveToTrash(
      Candidate(
        path = oldFile.toString(),
        sizeBytes = 10L,
        lastModified = mtime,
        category = Category.SUPERFLUOUS,
        ruleId = "PARTIAL_DL",
        confidence = Confidence.HIGH,
      ),
    ) as TrashMoveResult.Moved

    val worker = buildPurgeWorker(repo)

    // Após 29 dias ainda não expira
    currentClockMillis += 29L * TrashEntry.MILLIS_PER_DAY
    assertEquals(ListenableWorker.Result.success(), worker.doWork())
    assertEquals(1, repo.inTrashEntries.first().size)

    // Após 30 dias o expurgo remove definitivamente e contabiliza espaço liberado
    currentClockMillis += 2L * TrashEntry.MILLIS_PER_DAY
    assertEquals(ListenableWorker.Result.success(), worker.doWork())
    assertEquals(0, repo.inTrashEntries.first().size)
    assertFalse(Files.exists(Paths.get(moved.entry.trashPath), LinkOption.NOFOLLOW_LINKS))

    val statsAfterPurge = repo.trashStats.first()
    assertEquals(0, statsAfterPurge.inTrashCount)
    assertEquals(10L, statsAfterPurge.freedBytes)
    assertEquals(1, statsAfterPurge.purgedCount)
  }

  @Test
  fun `reconcilePendingAndRebuildIndex recovers interrupted PENDING and rebuilds from metadata json`() = runTest {
    val repo = createRepository()
    val storage = TrashStorage()
    val trashRoot = storage.resolveTrashRoot(storageRoot)

    // Caso 1: PENDING em que apenas o payload no destino existe -> promove para IN_TRASH
    val promotedDir = storage.allocateEntryDir(trashRoot, "pending-promoted", currentClockMillis)
    Files.createDirectories(promotedDir)
    val promotedPayload = Files.writeString(storage.resolvePayloadPath(promotedDir), "ok")
    db.trashEntryDao().upsert(
      TrashEntryEntity(
        id = "pending-promoted",
        originalPath = storageRoot.resolve("Download/done.tmp").toString(),
        trashPath = promotedPayload.toString(),
        sizeBytes = 2L,
        sha256 = null,
        originalMtime = 100L,
        category = Category.SUPERFLUOUS.name,
        ruleId = "TEST",
        deletedAt = currentClockMillis,
        expiresAt = currentClockMillis + TrashEntry.RETENTION_MILLIS,
        state = TrashEntryState.PENDING.name,
      ),
    )

    // Caso 2: PENDING em que origem e destino existem (falha no meio de cópia) -> preserva origem e marca FAILED
    val docsDir = Files.createDirectories(storageRoot.resolve("Documents"))
    val intactOriginal = Files.writeString(docsDir.resolve("keep.txt"), "intacto")
    val rollbackDir = storage.allocateEntryDir(trashRoot, "pending-rollback", currentClockMillis)
    Files.createDirectories(rollbackDir)
    val partialPayload = Files.writeString(storage.resolvePayloadPath(rollbackDir), "parcial")
    db.trashEntryDao().upsert(
      TrashEntryEntity(
        id = "pending-rollback",
        originalPath = intactOriginal.toString(),
        trashPath = partialPayload.toString(),
        sizeBytes = 7L,
        sha256 = null,
        originalMtime = 100L,
        category = Category.SUPERFLUOUS.name,
        ruleId = "TEST",
        deletedAt = currentClockMillis,
        expiresAt = currentClockMillis + TrashEntry.RETENTION_MILLIS,
        state = TrashEntryState.PENDING.name,
      ),
    )

    // Caso 3: Registro ausente do Room mas existente em Documents/LimpaDroid/.lixeira (reinstalação)
    val rebuiltDir = storage.allocateEntryDir(trashRoot, "rebuilt-id", currentClockMillis)
    Files.createDirectories(rebuiltDir)
    val rebuiltPayload = Files.writeString(storage.resolvePayloadPath(rebuiltDir), "reconstruido")
    storage.writeMetadata(
      storage.resolveMetadataPath(rebuiltDir),
      TrashMetadataPayload(
        id = "rebuilt-id",
        originalPath = storageRoot.resolve("Pictures/old.jpg").toString(),
        trashPath = rebuiltPayload.toString(),
        sizeBytes = 12L,
        sha256 = null,
        originalMtime = 200L,
        category = Category.WHATSAPP.name,
        ruleId = "WA",
        deletedAt = currentClockMillis,
        expiresAt = currentClockMillis + TrashEntry.RETENTION_MILLIS,
      ),
    )

    repo.reconcilePendingAndRebuildIndex()

    assertEquals(TrashEntryState.IN_TRASH, repo.getEntry("pending-promoted")?.state)
    assertEquals(TrashEntryState.FAILED, repo.getEntry("pending-rollback")?.state)
    assertTrue(Files.exists(intactOriginal, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(partialPayload, LinkOption.NOFOLLOW_LINKS))

    val rebuilt = repo.getEntry("rebuilt-id")
    assertNotNull(rebuilt)
    assertEquals(TrashEntryState.IN_TRASH, rebuilt?.state)
    assertEquals(12L, rebuilt?.sizeBytes)
  }

  @Test
  fun `repairStaleMediaStore updates MediaScanner without using trash and skips existing or protected paths`() =
    runTest {
      val repo = createRepository()
      val picturesDir = Files.createDirectories(storageRoot.resolve("Pictures"))
      val missingFile = picturesDir.resolve("apagada.jpg")
      val existingFile = Files.writeString(picturesDir.resolve("presente.jpg"), "present")
      val protectedMissing = storageRoot.resolve("Android/data/com.other/stale.jpg")

      val records = listOf(
        io.github.km20197.limpadroid.domain.model.ScanCandidateRecord(
          id = 1L,
          sessionId = "s-ms",
          candidate = Candidate(
            path = missingFile.toString(),
            sizeBytes = 0L,
            lastModified = 100L,
            category = Category.SUPERFLUOUS,
            ruleId = "STALE_MEDIASTORE",
            confidence = Confidence.HIGH,
            preselected = true,
          ),
          reason = "stale",
        ),
        io.github.km20197.limpadroid.domain.model.ScanCandidateRecord(
          id = 2L,
          sessionId = "s-ms",
          candidate = Candidate(
            path = existingFile.toString(),
            sizeBytes = 7L,
            lastModified = 100L,
            category = Category.SUPERFLUOUS,
            ruleId = "STALE_MEDIASTORE",
            confidence = Confidence.HIGH,
            preselected = true,
          ),
          reason = "stale",
        ),
        io.github.km20197.limpadroid.domain.model.ScanCandidateRecord(
          id = 3L,
          sessionId = "s-ms",
          candidate = Candidate(
            path = protectedMissing.toString(),
            sizeBytes = 0L,
            lastModified = 100L,
            category = Category.SUPERFLUOUS,
            ruleId = "STALE_MEDIASTORE",
            confidence = Confidence.HIGH,
            preselected = true,
          ),
          reason = "stale",
        ),
      )

      val summary = repo.repairStaleMediaStore(records)
      assertEquals(listOf(1L), summary.repairedCandidateIds)
      assertEquals(2, summary.rejectedOrExistingCount)
      assertEquals(listOf(missingFile.toString()), scannedPaths)
      assertTrue(repo.inTrashEntries.first().isEmpty())

      val history = repo.recentHistory.first()
      assertEquals(1, history.size)
      assertEquals(OperationType.MEDIASTORE_REPAIR, history.single().operationType)
      assertEquals(Category.SUPERFLUOUS, history.single().category)
      assertEquals(1, history.single().itemCount)
    }

  @Test
  fun `MIGRATION_1_2 creates trash and history tables cleanly on existing v1 database`() = runTest {
    val dbFile = tempFolder.newFile("legacy_v1.db")
    val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `scan_sessions` (
        `id` TEXT NOT NULL,
        `scope` TEXT NOT NULL,
        `status` TEXT NOT NULL,
        `rootPath` TEXT NOT NULL,
        `startedAt` INTEGER NOT NULL,
        `updatedAt` INTEGER NOT NULL,
        `finishedAt` INTEGER,
        `scannedFiles` INTEGER NOT NULL,
        `scannedDirs` INTEGER NOT NULL,
        `scannedBytes` INTEGER NOT NULL,
        `settledFiles` INTEGER NOT NULL,
        `settledDirs` INTEGER NOT NULL,
        `settledBytes` INTEGER NOT NULL,
        `candidatesCount` INTEGER NOT NULL,
        `errorCount` INTEGER NOT NULL,
        `lastProcessedPath` TEXT,
        `completedSubtreesJson` TEXT NOT NULL,
        PRIMARY KEY(`id`)
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `scan_candidates` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `sessionId` TEXT NOT NULL,
        `path` TEXT NOT NULL,
        `sizeBytes` INTEGER NOT NULL,
        `lastModified` INTEGER NOT NULL,
        `category` TEXT NOT NULL,
        `ruleId` TEXT NOT NULL,
        `confidence` TEXT NOT NULL,
        `groupId` TEXT,
        `preselected` INTEGER NOT NULL,
        `reason` TEXT,
        FOREIGN KEY(`sessionId`) REFERENCES `scan_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      "CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_candidates_sessionId_path` ON `scan_candidates` (`sessionId`, `path`)",
    )
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `scan_errors` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `sessionId` TEXT NOT NULL,
        `path` TEXT NOT NULL,
        `errorType` TEXT NOT NULL,
        `message` TEXT NOT NULL,
        `timestamp` INTEGER NOT NULL,
        FOREIGN KEY(`sessionId`) REFERENCES `scan_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_scan_errors_sessionId` ON `scan_errors` (`sessionId`)",
    )
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `hash_cache` (
        `path` TEXT NOT NULL,
        `sizeBytes` INTEGER NOT NULL,
        `lastModified` INTEGER NOT NULL,
        `sha256` TEXT NOT NULL,
        `updatedAt` INTEGER NOT NULL,
        PRIMARY KEY(`path`)
      )
      """.trimIndent(),
    )
    sqlite.version = 1
    sqlite.close()

    val migratedDb = Room.databaseBuilder(context, LimpaDroidDatabase::class.java, dbFile.absolutePath)
      .addMigrations(
        LimpaDroidDatabase.MIGRATION_1_2,
        LimpaDroidDatabase.MIGRATION_2_3,
        LimpaDroidDatabase.MIGRATION_3_4,
      )
      .allowMainThreadQueries()
      .build()
    try {
      migratedDb.trashEntryDao().upsert(
        TrashEntryEntity(
          id = "migrated-1",
          originalPath = "/storage/emulated/0/Download/test.tmp",
          trashPath = "/storage/emulated/0/Documents/LimpaDroid/.lixeira/20261117/migrated-1/payload",
          sizeBytes = 42L,
          sha256 = "deadbeef",
          originalMtime = 100L,
          category = Category.SUPERFLUOUS.name,
          ruleId = "PARTIAL_DL",
          deletedAt = 1_700_000_000_000L,
          expiresAt = 1_702_592_000_000L,
          state = TrashEntryState.IN_TRASH.name,
        ),
      )
      val loaded = migratedDb.trashEntryDao().getById("migrated-1")
      assertNotNull(loaded)
      assertEquals(42L, loaded?.sizeBytes)
      assertTrue(migratedDb.knownAppDao().getAll().isEmpty())
    } finally {
      migratedDb.close()
    }
  }

  @Test
  fun `MIGRATION_3_4 adds isKept column cleanly on existing v3 database`() = runTest {
    val dbFile = tempFolder.newFile("legacy_v3.db")
    val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbFile, null)
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `scan_sessions` (
        `id` TEXT NOT NULL,
        `scope` TEXT NOT NULL,
        `status` TEXT NOT NULL,
        `rootPath` TEXT NOT NULL,
        `startedAt` INTEGER NOT NULL,
        `updatedAt` INTEGER NOT NULL,
        `finishedAt` INTEGER,
        `scannedFiles` INTEGER NOT NULL,
        `scannedDirs` INTEGER NOT NULL,
        `scannedBytes` INTEGER NOT NULL,
        `settledFiles` INTEGER NOT NULL,
        `settledDirs` INTEGER NOT NULL,
        `settledBytes` INTEGER NOT NULL,
        `candidatesCount` INTEGER NOT NULL,
        `errorCount` INTEGER NOT NULL,
        `lastProcessedPath` TEXT,
        `completedSubtreesJson` TEXT NOT NULL,
        PRIMARY KEY(`id`)
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `scan_candidates` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `sessionId` TEXT NOT NULL,
        `path` TEXT NOT NULL,
        `sizeBytes` INTEGER NOT NULL,
        `lastModified` INTEGER NOT NULL,
        `category` TEXT NOT NULL,
        `ruleId` TEXT NOT NULL,
        `confidence` TEXT NOT NULL,
        `groupId` TEXT,
        `preselected` INTEGER NOT NULL,
        `reason` TEXT,
        FOREIGN KEY(`sessionId`) REFERENCES `scan_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      "CREATE UNIQUE INDEX IF NOT EXISTS `index_scan_candidates_sessionId_path` ON `scan_candidates` (`sessionId`, `path`)",
    )
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `scan_errors` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `sessionId` TEXT NOT NULL,
        `path` TEXT NOT NULL,
        `errorType` TEXT NOT NULL,
        `message` TEXT NOT NULL,
        `timestamp` INTEGER NOT NULL,
        FOREIGN KEY(`sessionId`) REFERENCES `scan_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_scan_errors_sessionId` ON `scan_errors` (`sessionId`)",
    )
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `hash_cache` (
        `path` TEXT NOT NULL,
        `sizeBytes` INTEGER NOT NULL,
        `lastModified` INTEGER NOT NULL,
        `sha256` TEXT NOT NULL,
        `updatedAt` INTEGER NOT NULL,
        PRIMARY KEY(`path`)
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `trash_entries` (
        `id` TEXT NOT NULL,
        `originalPath` TEXT NOT NULL,
        `trashPath` TEXT NOT NULL,
        `sizeBytes` INTEGER NOT NULL,
        `sha256` TEXT,
        `originalMtime` INTEGER NOT NULL,
        `category` TEXT NOT NULL,
        `ruleId` TEXT NOT NULL,
        `deletedAt` INTEGER NOT NULL,
        `expiresAt` INTEGER NOT NULL,
        `state` TEXT NOT NULL,
        `restoredAt` INTEGER,
        `restoredPath` TEXT,
        `purgedAt` INTEGER,
        `failureReason` TEXT,
        PRIMARY KEY(`id`)
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_trash_entries_state_expiresAt` ON `trash_entries` (`state`, `expiresAt`)",
    )
    sqlite.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_trash_entries_deletedAt` ON `trash_entries` (`deletedAt`)",
    )
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `operation_history` (
        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `operationType` TEXT NOT NULL,
        `category` TEXT,
        `itemCount` INTEGER NOT NULL,
        `totalBytes` INTEGER NOT NULL,
        `timestamp` INTEGER NOT NULL,
        `detail` TEXT
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_operation_history_timestamp` ON `operation_history` (`timestamp`)",
    )
    sqlite.execSQL(
      """
      CREATE TABLE IF NOT EXISTS `known_apps` (
        `packageName` TEXT NOT NULL,
        `label` TEXT NOT NULL,
        `firstSeen` INTEGER NOT NULL,
        `lastSeen` INTEGER NOT NULL,
        `removedAt` INTEGER,
        PRIMARY KEY(`packageName`)
      )
      """.trimIndent(),
    )
    sqlite.execSQL(
      "CREATE INDEX IF NOT EXISTS `index_known_apps_removedAt` ON `known_apps` (`removedAt`)",
    )
    sqlite.version = 3
    sqlite.close()

    val migratedDb = Room.databaseBuilder(context, LimpaDroidDatabase::class.java, dbFile.absolutePath)
      .addMigrations(
        LimpaDroidDatabase.MIGRATION_1_2,
        LimpaDroidDatabase.MIGRATION_2_3,
        LimpaDroidDatabase.MIGRATION_3_4,
      )
      .allowMainThreadQueries()
      .build()
    try {
      migratedDb.scanSessionDao().upsert(
        io.github.km20197.limpadroid.data.db.ScanSessionEntity(
          id = "s-v3",
          scope = "DUPLICATE",
          status = "COMPLETED",
          rootPath = storageRoot.toString(),
          startedAt = 1L,
          updatedAt = 2L,
          finishedAt = 2L,
          scannedFiles = 1L,
          scannedDirs = 1L,
          scannedBytes = 10L,
          settledFiles = 1L,
          settledDirs = 1L,
          settledBytes = 10L,
          candidatesCount = 1,
          errorCount = 0,
          lastProcessedPath = null,
          completedSubtreesJson = "[]",
        ),
      )
      migratedDb.scanCandidateDao().insertAll(
        listOf(
          io.github.km20197.limpadroid.data.db.ScanCandidateEntity(
            sessionId = "s-v3",
            path = "/storage/emulated/0/DCIM/a.jpg",
            sizeBytes = 4096L,
            lastModified = 10L,
            category = Category.DUPLICATE.name,
            ruleId = "DUPLICATE_PRIORITY",
            confidence = Confidence.HIGH.name,
            groupId = "a".repeat(64),
            preselected = false,
            reason = null,
            isKept = true,
          ),
        ),
      )
      val saved = migratedDb.scanCandidateDao().getForGroup("s-v3", "a".repeat(64)).single()
      assertTrue(saved.isKept)
    } finally {
      migratedDb.close()
    }
  }

  @Test
  fun `moveToTrash aborts DUPLICATE with StaleCandidate when SHA-256 diverges despite matching size and mtime`() =
    runTest {
      val repo = createRepository()
      val dlDir = Files.createDirectories(storageRoot.resolve("Download"))
      val target = Files.writeString(dlDir.resolve("dup.bin"), "conteudo-original")
      val mtime = 1_695_000_000_000L
      Files.setLastModifiedTime(target, FileTime.fromMillis(mtime))
      val originalSha = io.github.km20197.limpadroid.domain.fs.Hasher.sha256(target)

      // Sobrescreve conteúdo mantendo o mesmo tamanho (17 bytes) e o mesmo mtime
      Files.writeString(target, "conteudo-alterado")
      Files.setLastModifiedTime(target, FileTime.fromMillis(mtime))

      val result = repo.moveToTrash(
        Candidate(
          path = target.toString(),
          sizeBytes = 17L,
          lastModified = mtime,
          category = Category.DUPLICATE,
          ruleId = "DUPLICATE_PRIORITY",
          confidence = Confidence.HIGH,
          groupId = originalSha,
          preselected = true,
        ),
      )
      assertTrue(result is TrashMoveResult.StaleCandidate)
      assertTrue(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
    }

  @Test
  fun `moveCandidatesToTrash refuses to move last remaining copy of a duplicate group`() = runTest {
    val scanRepo = io.github.km20197.limpadroid.data.scan.ScanRepository(
      db.scanSessionDao(),
      db.scanCandidateDao(),
      db.scanErrorDao(),
    )
    val trashRepo = createRepository()
    val dcimDir = Files.createDirectories(storageRoot.resolve("DCIM/Camera"))
    val dlDir = Files.createDirectories(storageRoot.resolve("Download"))
    val keptFile = Files.writeString(dcimDir.resolve("img.jpg"), "mesmo-conteudo-duplicado")
    val dupFile = Files.writeString(dlDir.resolve("img_copia.jpg"), "mesmo-conteudo-duplicado")
    val sha = io.github.km20197.limpadroid.domain.fs.Hasher.sha256(keptFile)
    val size = Files.size(keptFile)
    val mtime = Files.getLastModifiedTime(keptFile).toMillis()

    val session = scanRepo.createSession(
      io.github.km20197.limpadroid.domain.model.ScanScope.DUPLICATE,
      storageRoot.toString(),
    )
    scanRepo.appendFoundEventsAndErrors(
      sessionId = session.id,
      foundEvents = listOf(
        io.github.km20197.limpadroid.domain.model.ScanEvent.Found(
          candidate = Candidate(
            path = keptFile.toString(),
            sizeBytes = size,
            lastModified = mtime,
            category = Category.DUPLICATE,
            ruleId = "DUPLICATE_PRIORITY",
            confidence = Confidence.HIGH,
            groupId = sha,
            preselected = false,
          ),
          isKept = true,
        ),
        io.github.km20197.limpadroid.domain.model.ScanEvent.Found(
          candidate = Candidate(
            path = dupFile.toString(),
            sizeBytes = size,
            lastModified = mtime,
            category = Category.DUPLICATE,
            ruleId = "DUPLICATE_PRIORITY",
            confidence = Confidence.HIGH,
            groupId = sha,
            preselected = true,
          ),
          isKept = false,
        ),
      ),
      errors = emptyList(),
    )

    val records = scanRepo.observeCategoryCandidates(Category.DUPLICATE).first()
    assertEquals(2, records.size)

    // Apaga externamente a cópia preservada -> resta apenas dupFile no disco -> deve bloquear remoção
    Files.delete(keptFile)
    val blockedSummary = trashRepo.moveCandidatesToTrash(records)
    assertTrue(blockedSummary.movedCandidateIds.isEmpty())
    assertTrue(blockedSummary.staleOrSkippedCount >= 1)
    assertTrue(Files.exists(dupFile, LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `RoomHashCacheRepository and ScanRepository manage cache batches swapKeptCandidate and solo cleanup`() = runTest {
    val hashCacheRepo = io.github.km20197.limpadroid.data.scan.RoomHashCacheRepository(db.hashCacheDao())
    val entries = (1..450).map { idx ->
      io.github.km20197.limpadroid.domain.scan.CachedHashEntry(
        path = "/storage/emulated/0/Pool/f_$idx.bin",
        sizeBytes = 4096L + idx,
        lastModified = 100L + idx,
        sha256 = "f".repeat(64),
        updatedAt = 500L,
      )
    }
    hashCacheRepo.upsertAll(entries)
    val loadedMap = hashCacheRepo.getByPaths(entries.map { it.path })
    assertEquals(450, loadedMap.size)
    assertEquals(4100L, hashCacheRepo.getByPath("/storage/emulated/0/Pool/f_4.bin")?.sizeBytes)

    val scanRepo = io.github.km20197.limpadroid.data.scan.ScanRepository(
      db.scanSessionDao(),
      db.scanCandidateDao(),
      db.scanErrorDao(),
    )
    val session = scanRepo.createSession(
      io.github.km20197.limpadroid.domain.model.ScanScope.DUPLICATE,
      storageRoot.toString(),
    )
    val sha = "1".repeat(64)
    scanRepo.appendFoundEventsAndErrors(
      sessionId = session.id,
      foundEvents = listOf(
        io.github.km20197.limpadroid.domain.model.ScanEvent.Found(
          candidate = Candidate(
            path = "/storage/emulated/0/DCIM/Camera/a.jpg",
            sizeBytes = 5000L,
            lastModified = 100L,
            category = Category.DUPLICATE,
            ruleId = "DUPLICATE_PRIORITY",
            confidence = Confidence.HIGH,
            groupId = sha,
            preselected = false,
          ),
          isKept = true,
        ),
        io.github.km20197.limpadroid.domain.model.ScanEvent.Found(
          candidate = Candidate(
            path = "/storage/emulated/0/Download/b.jpg",
            sizeBytes = 5000L,
            lastModified = 200L,
            category = Category.DUPLICATE,
            ruleId = "DUPLICATE_PRIORITY",
            confidence = Confidence.HIGH,
            groupId = sha,
            preselected = true,
          ),
          isKept = false,
        ),
      ),
      errors = emptyList(),
    )

    // Resumo contabiliza apenas a cópia removível
    val summaryBefore = scanRepo.observeCategorySummary(Category.DUPLICATE).first()
    assertEquals(1, summaryBefore.candidateCount)
    assertEquals(5000L, summaryBefore.totalBytes)

    val initial = scanRepo.observeCategoryCandidates(Category.DUPLICATE).first()
    val firstKept = initial.single { it.isKept }
    val firstRemovable = initial.single { !it.isKept }

    // Tentativa de marcar a cópia preservada é rejeitada pelo repositório
    scanRepo.setCandidatePreselected(firstKept.id, true)
    assertFalse(scanRepo.observeCategoryCandidates(Category.DUPLICATE).first().single { it.id == firstKept.id }.candidate.preselected)

    // Troca manual da cópia preservada
    scanRepo.swapDuplicateKeptCandidate(session.id, sha, firstRemovable.id)
    val afterSwap = scanRepo.observeCategoryCandidates(Category.DUPLICATE).first()
    assertTrue(afterSwap.single { it.id == firstRemovable.id }.isKept)
    assertFalse(afterSwap.single { it.id == firstRemovable.id }.candidate.preselected)
    assertFalse(afterSwap.single { it.id == firstKept.id }.isKept)
    assertTrue(afterSwap.single { it.id == firstKept.id }.candidate.preselected)

    // Remoção da cópia excedente limpa automaticamente a cópia solitária remanescente do grupo
    scanRepo.removeCleanedCandidates(session.id, listOf(firstKept.id))
    assertTrue(scanRepo.observeCategoryCandidates(Category.DUPLICATE).first().isEmpty())
  }

  @Test
  fun `A1 post-move RuntimeException and CancellationException recover entry as IN_TRASH without stranding payload`() =
    runTest {
      val repo = createRepository()
      val dlDir = Files.createDirectories(storageRoot.resolve("Download"))

      // 1) Falha após Files.move (ex.: DAO ou IO pós-movimentação) -> recupera como IN_TRASH
      val file1 = Files.writeString(dlDir.resolve("pos_erro.tmp"), "payload-erro")
      val mtime1 = 1_690_000_000_000L
      Files.setLastModifiedTime(file1, FileTime.fromMillis(mtime1))
      val res1 = repo.moveToTrashForTestWithPostMoveHook(
        candidate = Candidate(
          path = file1.toString(),
          sizeBytes = 12L,
          lastModified = mtime1,
          category = Category.SUPERFLUOUS,
          ruleId = "PARTIAL_DL",
          confidence = Confidence.HIGH,
        ),
        onBeforePostMovePersistence = { throw IllegalStateException("falha simulada pós-movimentação") },
      )
      assertTrue(res1 is TrashMoveResult.Moved)
      val moved1 = (res1 as TrashMoveResult.Moved).entry
      assertEquals(TrashEntryState.IN_TRASH, repo.getEntry(moved1.id)?.state)
      assertTrue(Files.exists(Paths.get(moved1.trashPath), LinkOption.NOFOLLOW_LINKS))

      // 2) Cancelamento após Files.move -> garante IN_TRASH em NonCancellable e relança CancellationException
      val file2 = Files.writeString(dlDir.resolve("pos_cancel.tmp"), "payload-cancel")
      val mtime2 = 1_690_000_100_000L
      Files.setLastModifiedTime(file2, FileTime.fromMillis(mtime2))
      var cancellationPropagated = false
      try {
        repo.moveToTrashForTestWithPostMoveHook(
          candidate = Candidate(
            path = file2.toString(),
            sizeBytes = 14L,
            lastModified = mtime2,
            category = Category.SUPERFLUOUS,
            ruleId = "PARTIAL_DL",
            confidence = Confidence.HIGH,
          ),
          onBeforePostMovePersistence = { throw CancellationException("cancelamento simulado") },
        )
      } catch (_: CancellationException) {
        cancellationPropagated = true
      }
      assertTrue(cancellationPropagated)
      val activeAfterCancel = repo.inTrashEntries.first()
      assertTrue(activeAfterCancel.any { it.originalPath == file2.toString() && it.state == TrashEntryState.IN_TRASH })

      // 3) Reconcilia entrada FAILED pré-existente cujo payload existe no disco
      val storage = TrashStorage()
      val trashRoot = storage.resolveTrashRoot(storageRoot)
      val failedDir = storage.allocateEntryDir(trashRoot, "failed-with-payload", currentClockMillis)
      Files.createDirectories(failedDir)
      val failedPayload = Files.writeString(storage.resolvePayloadPath(failedDir), "salvo")
      db.trashEntryDao().upsert(
        TrashEntryEntity(
          id = "failed-with-payload",
          originalPath = storageRoot.resolve("Download/ausente.tmp").toString(),
          trashPath = failedPayload.toString(),
          sizeBytes = 5L,
          sha256 = null,
          originalMtime = 100L,
          category = Category.SUPERFLUOUS.name,
          ruleId = "TEST",
          deletedAt = currentClockMillis,
          expiresAt = currentClockMillis + TrashEntry.RETENTION_MILLIS,
          state = TrashEntryState.FAILED.name,
        ),
      )

      repo.reconcilePendingAndRebuildIndex()
      val recovered = repo.getEntry("failed-with-payload")
      assertEquals(TrashEntryState.IN_TRASH, recovered?.state)
      assertTrue(Files.exists(storage.resolveMetadataPath(failedDir), LinkOption.NOFOLLOW_LINKS))
    }

  @Test
  fun `A2 tampered Room trashPath outside lixeira never deletes DCIM Camera on purge`() = runTest {
    val repo = createRepository()
    val dcimCamera = Files.createDirectories(storageRoot.resolve("DCIM/Camera"))
    val victimFile = Files.writeString(dcimCamera.resolve("foto_importante.jpg"), "nao-apagar")

    db.trashEntryDao().upsert(
      TrashEntryEntity(
        id = "tampered-room-id",
        originalPath = victimFile.toString(),
        trashPath = victimFile.toString(),
        sizeBytes = 10L,
        sha256 = null,
        originalMtime = 100L,
        category = Category.SUPERFLUOUS.name,
        ruleId = "TEST",
        deletedAt = currentClockMillis - 40L * TrashEntry.MILLIS_PER_DAY,
        expiresAt = currentClockMillis - TrashEntry.MILLIS_PER_DAY,
        state = TrashEntryState.IN_TRASH.name,
      ),
    )

    val summary = repo.purgeExpired()
    assertEquals(0, summary.purgedCount)
    assertTrue(Files.exists(victimFile, LinkOption.NOFOLLOW_LINKS))
    assertEquals(TrashEntryState.IN_TRASH, repo.getEntry("tampered-room-id")?.state)
  }

  @Test
  fun `A4 concurrent move reconcile and purge execute safely under mutex`() = runTest {
    val repo = createRepository()
    val dlDir = Files.createDirectories(storageRoot.resolve("Download"))
    val files = (1..5).map { idx ->
      val p = Files.writeString(dlDir.resolve("concurrent_$idx.tmp"), "item-$idx")
      Candidate(
        path = p.toString(),
        sizeBytes = Files.size(p),
        lastModified = Files.getLastModifiedTime(p).toMillis(),
        category = Category.SUPERFLUOUS,
        ruleId = "PARTIAL_DL",
        confidence = Confidence.HIGH,
      )
    }

    val jobs = listOf(
      async { repo.moveCandidatesToTrash(files.mapIndexed { idx, c -> io.github.km20197.limpadroid.domain.model.ScanCandidateRecord(idx + 1L, "s-conc", c, null) }) },
      async { repo.reconcilePendingAndRebuildIndex() },
      async { repo.purgeExpired() },
    )
    jobs.awaitAll()

    val inTrash = repo.inTrashEntries.first()
    assertEquals(5, inTrash.size)
    assertTrue(inTrash.all { Files.exists(Paths.get(it.trashPath), LinkOption.NOFOLLOW_LINKS) })
  }

  @Test
  fun `M3 moveCandidatesToTrash batches MediaScanner calls once per batch`() = runTest {
    val repo = createRepository()
    val dlDir = Files.createDirectories(storageRoot.resolve("Download"))
    val records = (1..3).map { idx ->
      val f = Files.writeString(dlDir.resolve("batch_$idx.tmp"), "batch-$idx")
      io.github.km20197.limpadroid.domain.model.ScanCandidateRecord(
        id = idx.toLong(),
        sessionId = "s-batch",
        candidate = Candidate(
          path = f.toString(),
          sizeBytes = Files.size(f),
          lastModified = Files.getLastModifiedTime(f).toMillis(),
          category = Category.SUPERFLUOUS,
          ruleId = "PARTIAL_DL",
          confidence = Confidence.HIGH,
        ),
        reason = null,
      )
    }

    val summary = repo.moveCandidatesToTrash(records)
    assertEquals(3, summary.movedCandidateIds.size)
    assertEquals(1, scanBatchCalls.size)
    assertEquals(3, scanBatchCalls.single().size)
  }

  @Test
  fun `PurgeWorker propagates CancellationException instead of returning retry`() = runTest {
    val storageWithDelay = TrashStorage()
    val dlDir = Files.createDirectories(storageRoot.resolve("Download"))
    val file = Files.writeString(dlDir.resolve("cancel_worker.tmp"), "1234")
    val repo = createRepository()
    repo.moveToTrash(
      Candidate(
        path = file.toString(),
        sizeBytes = 4L,
        lastModified = Files.getLastModifiedTime(file).toMillis(),
        category = Category.SUPERFLUOUS,
        ruleId = "PARTIAL_DL",
        confidence = Confidence.HIGH,
      ),
    )
    // Garante que uma coroutine cancelada propaga CancellationException fora do WorkerFactory normal
    currentClockMillis += 35L * TrashEntry.MILLIS_PER_DAY
    val cancellingResolver = object : StorageVolumeResolver {
      override fun resolvePrimarySharedStorage(): StorageRootResolution {
        throw CancellationException("worker cancelado")
      }
    }
    val cancellingRepo = TrashRepository(
      trashEntryDao = db.trashEntryDao(),
      operationHistoryDao = db.operationHistoryDao(),
      storageVolumeResolver = cancellingResolver,
      mediaScannerHook = MediaScannerHook { },
      scanLogger = LocalScanLogger(logDir),
      applicationId = "io.github.km20197.limpadroid",
      trashStorage = storageWithDelay,
      clock = AppClock { currentClockMillis },
      scanCandidateDao = db.scanCandidateDao(),
    )
    val worker = buildPurgeWorker(cancellingRepo)
    var cancelled = false
    try {
      worker.doWork()
    } catch (_: CancellationException) {
      cancelled = true
    }
    assertTrue(cancelled)
  }

  private fun createRepository(): TrashRepository = TrashRepository(
    trashEntryDao = db.trashEntryDao(),
    operationHistoryDao = db.operationHistoryDao(),
    storageVolumeResolver = object : StorageVolumeResolver {
      override fun resolvePrimarySharedStorage(): StorageRootResolution =
        StorageRootResolution.Available(storageRoot, "Armazenamento de teste")
    },
    mediaScannerHook = MediaScannerHook { paths ->
      scannedPaths.addAll(paths)
      scanBatchCalls.add(paths)
    },
    scanLogger = LocalScanLogger(logDir),
    applicationId = "io.github.km20197.limpadroid",
    clock = AppClock { currentClockMillis },
    scanCandidateDao = db.scanCandidateDao(),
  )

  private fun buildPurgeWorker(repository: TrashRepository): PurgeWorker {
    val factory = object : WorkerFactory() {
      override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
      ): ListenableWorker = PurgeWorker(appContext, workerParameters, repository)
    }
    return TestListenableWorkerBuilder<PurgeWorker>(context)
      .setWorkerFactory(factory)
      .build()
  }
}
