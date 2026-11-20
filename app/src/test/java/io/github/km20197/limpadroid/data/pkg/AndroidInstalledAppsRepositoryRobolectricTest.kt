package io.github.km20197.limpadroid.data.pkg

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
import io.github.km20197.limpadroid.data.scan.ScanRepository
import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.pkg.InstalledAppInfo
import io.github.km20197.limpadroid.domain.scan.InfrastructureScanEngine
import io.github.km20197.limpadroid.domain.scan.OrphanScanEngine
import io.github.km20197.limpadroid.domain.scan.rules.OrphanRuleId
import io.github.km20197.limpadroid.scan.ScanWorker
import io.github.km20197.limpadroid.scan.ScanWorkerDependencies
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class AndroidInstalledAppsRepositoryRobolectricTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var context: Context
  private lateinit var db: LimpaDroidDatabase

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, LimpaDroidDatabase::class.java)
      .allowMainThreadQueries()
      .build()
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `syncKnownApps tracks removals preserves archived apps as present and clears removedAt on reinstall`() =
    runTest {
      var now = 1_700_000_000_000L
      var installed = listOf(
        InstalledAppInfo(context.packageName, "LimpaDroid", isArchived = false),
        InstalledAppInfo("com.example.game", "Jogo Removível", isArchived = false),
        InstalledAppInfo("com.example.archived", "App Arquivado", isArchived = true),
      )

      val repo = AndroidInstalledAppsRepository(
        context = context,
        knownAppDao = db.knownAppDao(),
        clock = AppClock { now },
        packagesReader = { installed },
      )

      val firstSync = repo.syncKnownApps()
      assertEquals(3, firstSync.knownApps.size)
      assertTrue(firstSync.knownApps.all { it.removedAt == null })

      // Desinstala com.example.game, mantém com.example.archived arquivado
      now += 86_400_000L
      installed = listOf(
        InstalledAppInfo(context.packageName, "LimpaDroid", isArchived = false),
        InstalledAppInfo("com.example.archived", "App Arquivado", isArchived = true),
      )

      val secondSync = repo.syncKnownApps()
      val byPkgAfterRemove = secondSync.knownApps.associateBy { it.packageName }
      assertEquals(now, byPkgAfterRemove.getValue("com.example.game").removedAt)
      assertNull(byPkgAfterRemove.getValue("com.example.archived").removedAt)

      // Reinstala com.example.game -> limpa removedAt
      now += 86_400_000L
      installed = listOf(
        InstalledAppInfo(context.packageName, "LimpaDroid", isArchived = false),
        InstalledAppInfo("com.example.game", "Jogo Removível", isArchived = false),
        InstalledAppInfo("com.example.archived", "App Arquivado", isArchived = true),
      )

      val thirdSync = repo.syncKnownApps()
      val byPkgAfterReinstall = thirdSync.knownApps.associateBy { it.packageName }
      assertNull(byPkgAfterReinstall.getValue("com.example.game").removedAt)
      assertEquals(1_700_000_000_000L, byPkgAfterReinstall.getValue("com.example.game").firstSeen)
    }

  @Test
  @Config(sdk = [30])
  fun `readPresentPackages executes cleanly on API 30 fallback`() {
    val packages = AndroidInstalledAppsRepository.readPresentPackages(context)
    assertTrue(packages.any { it.packageName == context.packageName })
  }

  @Test
  fun `readPresentPackages includes archived application when flagged on API 35 plus`() {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    info.applicationInfo?.isArchived = true
    val packages = AndroidInstalledAppsRepository.readPresentPackages(context)
    assertTrue(packages.any { it.packageName == context.packageName })
  }

  @Test
  fun `ScanWorker routes ORPHAN scope to OrphanScanEngine and persists candidates`() = runTest {
    val root = tempFolder.newFolder("worker_orphan").toPath().toRealPath()
    val orphanMedia = Files.createDirectories(root.resolve("Android/media/com.removed.player"))
    Files.writeString(orphanMedia.resolve("track.mp3"), "audio-bytes")

    val appsRepo = AndroidInstalledAppsRepository(
      context = context,
      knownAppDao = db.knownAppDao(),
      clock = AppClock { 1_700_000_000_000L },
      packagesReader = { listOf(InstalledAppInfo(context.packageName, "LimpaDroid")) },
    )
    val scanRepo = ScanRepository(db.scanSessionDao(), db.scanCandidateDao(), db.scanErrorDao())
    val session = scanRepo.createSession(ScanScope.ORPHAN, root.toString())

    val deps = ScanWorkerDependencies(
      scanRepository = scanRepo,
      storageVolumeResolver = object : StorageVolumeResolver {
        override fun resolvePrimarySharedStorage(): StorageRootResolution =
          StorageRootResolution.Available(root, "Armazenamento de teste")
      },
      scanEngine = InfrastructureScanEngine(FileWalker()),
      scanLogger = LocalScanLogger(tempFolder.newFolder("logs")),
      applicationId = context.packageName,
      orphanScanEngine = OrphanScanEngine(appsRepo, context.packageName),
    )

    val worker = TestListenableWorkerBuilder<ScanWorker>(context)
      .setWorkerFactory(
        object : WorkerFactory() {
          override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
          ): ListenableWorker = ScanWorker(appContext, workerParameters, deps)
        },
      )
      .setInputData(workDataOf(ScanWorker.KEY_SESSION_ID to session.id))
      .build()

    val result = worker.doWork()
    assertTrue(result is ListenableWorker.Result.Success)
    assertEquals(ScanStatus.COMPLETED, scanRepo.getSession(session.id)?.status)

    val candidates = scanRepo.observeCategoryCandidates(Category.ORPHAN).first()
    assertEquals(1, candidates.size)
    assertEquals(OrphanRuleId.ORPHAN_MEDIA_PKG.ruleId, candidates.single().candidate.ruleId)
    assertEquals(11L, candidates.single().candidate.sizeBytes)
  }

  @Test
  fun `MIGRATION_2_3 creates known_apps table cleanly on existing v2 database`() = runTest {
    val dbFile = tempFolder.newFile("legacy_v2.db")
    val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
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
    sqlite.version = 2
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
      migratedDb.knownAppDao().upsertAll(
        listOf(
          io.github.km20197.limpadroid.data.db.KnownAppEntity(
            packageName = "com.legacy.app",
            label = "Legacy App",
            firstSeen = 100L,
            lastSeen = 200L,
            removedAt = 300L,
          ),
        ),
      )
      val removed = migratedDb.knownAppDao().getRemoved()
      assertEquals(1, removed.size)
      assertNotNull(removed.single().removedAt)
      assertEquals("com.legacy.app", removed.single().packageName)
    } finally {
      migratedDb.close()
    }
  }
}
