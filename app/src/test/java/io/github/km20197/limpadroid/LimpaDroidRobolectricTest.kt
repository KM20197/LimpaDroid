package io.github.km20197.limpadroid

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.km20197.limpadroid.data.db.LimpaDroidDatabase
import io.github.km20197.limpadroid.data.fs.AndroidStorageVolumeResolver
import io.github.km20197.limpadroid.data.fs.DefaultStorageDiagnosticRepository
import io.github.km20197.limpadroid.data.fs.StorageDiagnosticRepository
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.LocalScanLogger
import io.github.km20197.limpadroid.data.permission.AndroidPermissionChecker
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.data.scan.ScanRepository
import io.github.km20197.limpadroid.domain.diagnostic.DirectoryInspector
import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook
import io.github.km20197.limpadroid.domain.model.BatchTrashMoveSummary
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.CategorySummary
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.MediaStoreRepairSummary
import io.github.km20197.limpadroid.domain.model.OperationHistoryRecord
import io.github.km20197.limpadroid.domain.model.OverallCleanupOverview
import io.github.km20197.limpadroid.domain.model.PurgeSummary
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.domain.model.TrashMoveResult
import io.github.km20197.limpadroid.domain.model.TrashRestoreResult
import io.github.km20197.limpadroid.domain.model.TrashStats
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsRepository
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsSnapshot
import io.github.km20197.limpadroid.domain.scan.InfrastructureScanEngine
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateKeepPolicy
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateRuleId
import io.github.km20197.limpadroid.domain.scan.rules.OrphanRuleId
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaRules
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaType
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppRuleId
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanStartResult
import io.github.km20197.limpadroid.scan.ScanWorkerDependencies
import io.github.km20197.limpadroid.trash.TrashController
import io.github.km20197.limpadroid.trash.TrashRepository
import io.github.km20197.limpadroid.ui.navigation.LimpaDroidNavHost
import io.github.km20197.limpadroid.ui.onboarding.OnboardingScreen
import io.github.km20197.limpadroid.ui.onboarding.OnboardingViewModel
import io.github.km20197.limpadroid.ui.theme.LimpaDroidTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LimpaDroidRobolectricTest {

  private val composeTestRule = createComposeRule()
  private val tempFolder = TemporaryFolder()
  private var openDatabase: LimpaDroidDatabase? = null

  private val dbCleanupRule = object : ExternalResource() {
    override fun after() {
      openDatabase?.close()
      openDatabase = null
    }
  }

  @get:Rule
  val ruleChain: RuleChain = RuleChain
    .outerRule(tempFolder)
    .around(dbCleanupRule)
    .around(composeTestRule)

  @Test
  fun `manifest declares expected permissions and excludes INTERNET`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val packageInfo = context.packageManager.getPackageInfo(
      context.packageName,
      PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
    )
    val permissions = packageInfo.requestedPermissions?.toSet().orEmpty()

    assertTrue(permissions.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
    assertTrue(permissions.contains(Manifest.permission.QUERY_ALL_PACKAGES))
    assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
    assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE))
    assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC))
    assertFalse(permissions.contains(Manifest.permission.INTERNET))
    assertEquals("LimpaDroid", context.getString(R.string.app_name))
  }

  @Test
  @Config(sdk = [30])
  fun `android 11 runtime checks handle notifications and storage volume cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val checker = AndroidPermissionChecker(context)
    assertTrue(checker.hasNotificationPermission())

    val resolver = AndroidStorageVolumeResolver(context)
    val resolution = resolver.resolvePrimarySharedStorage()
    assertTrue(
      resolution is StorageRootResolution.Available || resolution is StorageRootResolution.Unavailable,
    )
  }

  @Test
  fun `onboarding allows continuing without notifications once storage access is granted`() {
    val fakeChecker = FakePermissionChecker(storageGranted = false, notificationGranted = false)
    val fakePrefs = FakeAppPreferencesRepository()
    val viewModel = OnboardingViewModel(fakeChecker, fakePrefs)
    var continued = false

    composeTestRule.setContent {
      LimpaDroidTheme(dynamicColor = false) {
        OnboardingScreen(
          viewModel = viewModel,
          onContinueToHome = { continued = true },
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("onboarding_continue_button").assertIsNotEnabled()

    composeTestRule.onNodeWithTag("skip_notifications_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("notifications_optional_warning").assertExists()

    fakeChecker.storageGranted = true
    composeTestRule.runOnIdle { viewModel.refreshPermissions() }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("onboarding_continue_button")
      .performScrollTo()
      .assertIsEnabled()
      .performClick()
    composeTestRule.waitForIdle()

    assertTrue(continued)
  }

  @Test
  fun `long press on version opens diagnostic mode and copies report`() {
    val rootPath = tempFolder.newFolder("emulated").toPath()
    val waMedia = rootPath.resolve("Android/media/com.whatsapp/WhatsApp/Media")
    Files.createDirectories(waMedia.resolve("WhatsApp Images"))

    val fakeChecker = FakePermissionChecker(storageGranted = true, notificationGranted = true)
    val fakePrefs = FakeAppPreferencesRepository()
    val container = createFakeAppContainer(fakeChecker, fakePrefs, rootPath)

    composeTestRule.setContent {
      LimpaDroidTheme(dynamicColor = false) {
        LimpaDroidNavHost(container = container)
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("version_diagnostic_trigger")
      .performScrollTo()
      .performTouchInput { longClick() }
    composeTestRule.waitForIdle()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("diagnostic_summary_card")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule.onNodeWithText("• WhatsApp Images").assertExists()

    composeTestRule.onNodeWithTag("diagnostic_copy_button").performClick()
    composeTestRule.waitForIdle()

    val context = ApplicationProvider.getApplicationContext<Context>()
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
    assertTrue(clipText.contains("WhatsApp Images"))
    assertTrue(clipText.contains("MANAGE_EXTERNAL_STORAGE: CONCEDIDA"))
  }

  @Test
  fun `home scan card starts pauses resumes and opens details with error log`() {
    val rootPath = tempFolder.newFolder("scan_root").toPath()
    val fakeChecker = FakePermissionChecker(storageGranted = true, notificationGranted = true)
    val fakePrefs = FakeAppPreferencesRepository()
    val container = createFakeAppContainer(fakeChecker, fakePrefs, rootPath)

    composeTestRule.setContent {
      LimpaDroidTheme(dynamicColor = false) {
        LimpaDroidNavHost(container = container)
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("home_start_scan_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("home_pause_scan_button")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("home_pause_scan_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("home_resume_scan_button")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("home_resume_scan_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("home_resume_scan_button")
        .fetchSemanticsNodes()
        .isEmpty() &&
        composeTestRule.onAllNodesWithTag("home_scan_details_button")
          .fetchSemanticsNodes()
          .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("home_scan_details_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("scan_session_summary_card")
        .fetchSemanticsNodes()
        .isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("scan_errors_empty_card")
          .fetchSemanticsNodes()
          .isEmpty()
    }

    composeTestRule.onNodeWithTag("scan_status_list")
      .performScrollToNode(hasTestTag("scan_error_item"))
    composeTestRule.onNodeWithTag("scan_error_item").assertExists()
    composeTestRule.onNodeWithText("${rootPath}/Restricted").assertExists()
  }

  @Test
  fun `home opens trash screen restores item with confirmation and logs history`() {
    val rootPath = tempFolder.newFolder("trash_cuj").toPath()
    val downloadDir = Files.createDirectories(rootPath.resolve("Download"))
    val sourceFile = Files.writeString(downloadDir.resolve("teste.tmp"), "conteudo-12345")
    val mtime = Files.getLastModifiedTime(sourceFile).toMillis()

    val fakeChecker = FakePermissionChecker(storageGranted = true, notificationGranted = true)
    val fakePrefs = FakeAppPreferencesRepository()
    val container = createFakeAppContainer(fakeChecker, fakePrefs, rootPath)

    val movedEntry = runBlocking {
      val result = container.trashRepository.moveToTrash(
        Candidate(
          path = sourceFile.toString(),
          sizeBytes = 14L,
          lastModified = mtime,
          category = Category.SUPERFLUOUS,
          ruleId = "PARTIAL_DL",
          confidence = Confidence.HIGH,
        ),
      )
      (result as TrashMoveResult.Moved).entry
    }

    composeTestRule.setContent {
      LimpaDroidTheme(dynamicColor = false) {
        LimpaDroidNavHost(container = container)
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("home_open_trash_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("trash_entry_card_${movedEntry.id}")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("trash_restore_button_${movedEntry.id}").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("trash_confirm_dialog_confirm").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("trash_empty_state")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    assertTrue(Files.exists(sourceFile))

    composeTestRule.onNodeWithTag("trash_back_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("home_open_history_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("history_record_card")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    assertTrue(
      composeTestRule.onAllNodesWithTag("history_record_card")
        .fetchSemanticsNodes()
        .size >= 2,
    )
  }

  @Test
  fun `home opens superfluous results moves selected item to trash and updates settings exclusions`() {
    val rootPath = tempFolder.newFolder("results_cuj").toPath().toRealPath()
    val thumbsDir = Files.createDirectories(rootPath.resolve("DCIM/.thumbnails"))
    val thumbFile = Files.writeString(thumbsDir.resolve("thumb_1.jpg"), "thumb-payload")
    val mtime = Files.getLastModifiedTime(thumbFile).toMillis()

    val fakeChecker = FakePermissionChecker(storageGranted = true, notificationGranted = true)
    val fakePrefs = FakeAppPreferencesRepository()
    val container = createFakeAppContainer(fakeChecker, fakePrefs, rootPath)

    val db = openDatabase!!
    runBlocking {
      val repo = ScanRepository(db.scanSessionDao(), db.scanCandidateDao(), db.scanErrorDao())
      val session = repo.createSession(ScanScope.SUPERFLUOUS, rootPath.toString())
      repo.appendCandidatesAndErrors(
        sessionId = session.id,
        candidates = listOf(
          Candidate(
            path = thumbFile.toString(),
            sizeBytes = 13L,
            lastModified = mtime,
            category = Category.SUPERFLUOUS,
            ruleId = SuperfluousRuleId.THUMBS.ruleId,
            confidence = Confidence.HIGH,
            preselected = true,
          ) to "Miniatura em cache",
        ),
        errors = emptyList(),
      )
      repo.finalizeWorkerRun(
        sessionId = session.id,
        targetStatusIfStillRunning = ScanStatus.COMPLETED,
        scannedFiles = 1L,
        scannedDirs = 1L,
        scannedBytes = 13L,
        checkpoint = WalkCheckpoint(settledFiles = 1L, settledDirs = 1L, settledBytes = 13L),
      )
    }

    composeTestRule.setContent {
      LimpaDroidTheme(dynamicColor = false) {
        LimpaDroidNavHost(container = container)
      }
    }
    composeTestRule.waitForIdle()

    // Abre Configurações e adiciona exceção personalizada
    composeTestRule.onNodeWithTag("home_settings_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_add_exclusion_button"))
    composeTestRule.onNodeWithTag("settings_add_exclusion_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_exclusion_input")
      .performTextInput("${rootPath}/Documents/Privado")
    composeTestRule.onNodeWithTag("settings_exclusion_confirm").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("${rootPath}/Documents/Privado").assertExists()

    composeTestRule.onNodeWithTag("settings_back_button").performClick()
    composeTestRule.waitForIdle()

    // Abre resultados de dados supérfluos e move o item selecionado para a lixeira
    composeTestRule.onNodeWithTag("home_open_superfluous_results_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("results_group_header_${SuperfluousRuleId.THUMBS.ruleId}")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithTag("results_cleanup_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("results_confirm_dialog_confirm").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("results_empty_state")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    assertFalse(Files.exists(thumbFile))
  }

  @Test
  fun `home opens orphan results shows removed app reason and moves HIGH folder to trash keeping MEDIUM unselected`() {
    val rootPath = tempFolder.newFolder("orphan_cuj").toPath().toRealPath()
    val mediaPkgDir = Files.createDirectories(rootPath.resolve("Android/media/com.old.game"))
    Files.writeString(mediaPkgDir.resolve("save.dat"), "save-payload")
    val docsKnownDir = Files.createDirectories(rootPath.resolve("Documents/Antigo Editor"))
    Files.writeString(docsKnownDir.resolve("notes.txt"), "doc-payload")

    val fakeChecker = FakePermissionChecker(storageGranted = true, notificationGranted = true)
    val fakePrefs = FakeAppPreferencesRepository()
    val container = createFakeAppContainer(fakeChecker, fakePrefs, rootPath)

    val db = openDatabase!!
    runBlocking {
      val repo = ScanRepository(db.scanSessionDao(), db.scanCandidateDao(), db.scanErrorDao())
      val session = repo.createSession(ScanScope.ORPHAN, rootPath.toString())
      repo.appendCandidatesAndErrors(
        sessionId = session.id,
        candidates = listOf(
          Candidate(
            path = mediaPkgDir.toString(),
            sizeBytes = 12L,
            lastModified = Files.getLastModifiedTime(mediaPkgDir).toMillis(),
            category = Category.ORPHAN,
            ruleId = OrphanRuleId.ORPHAN_MEDIA_PKG.ruleId,
            confidence = Confidence.HIGH,
            groupId = "com.old.game",
            preselected = true,
          ) to "Aplicativo removido: Old Game (com.old.game) · Removido em 16/11/2026",
          Candidate(
            path = docsKnownDir.toString(),
            sizeBytes = 11L,
            lastModified = Files.getLastModifiedTime(docsKnownDir).toMillis(),
            category = Category.ORPHAN,
            ruleId = OrphanRuleId.ORPHAN_ROOT_KNOWN.ruleId,
            confidence = Confidence.MEDIUM,
            groupId = "com.antigo.editor",
            preselected = false,
          ) to "Aplicativo removido: Antigo Editor (com.antigo.editor) · Removido em 15/11/2026",
        ),
        errors = emptyList(),
      )
      repo.finalizeWorkerRun(
        sessionId = session.id,
        targetStatusIfStillRunning = ScanStatus.COMPLETED,
        scannedFiles = 2L,
        scannedDirs = 2L,
        scannedBytes = 23L,
        checkpoint = WalkCheckpoint(settledFiles = 2L, settledDirs = 2L, settledBytes = 23L),
      )
    }

    composeTestRule.setContent {
      LimpaDroidTheme(dynamicColor = false) {
        LimpaDroidNavHost(container = container)
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("home_open_orphan_results_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("results_group_header_${OrphanRuleId.ORPHAN_MEDIA_PKG.ruleId}")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    composeTestRule.onNodeWithText("Aplicativo removido: Old Game (com.old.game) · Removido em 16/11/2026")
      .assertExists()

    composeTestRule.onNodeWithTag("results_cleanup_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("results_confirm_dialog_confirm").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("results_group_header_${OrphanRuleId.ORPHAN_MEDIA_PKG.ruleId}")
        .fetchSemanticsNodes()
        .isEmpty()
    }

    assertFalse(Files.exists(mediaPkgDir))
    assertTrue(Files.exists(docsKnownDir))
  }

  @Test
  fun `home opens duplicate results swaps preserved copy moves excess to trash and configures duplicate settings`() {
    val rootPath = tempFolder.newFolder("dup_cuj").toPath().toRealPath()
    val tempA = Files.createDirectories(rootPath.resolve("TempA"))
    val tempB = Files.createDirectories(rootPath.resolve("TempB"))
    val file1 = Files.writeString(tempA.resolve("doc_1.bin"), "conteudo-duplicado-4kb-" + "x".repeat(4100))
    val file2 = Files.writeString(tempB.resolve("doc_2.bin"), "conteudo-duplicado-4kb-" + "x".repeat(4100))
    val mtime1 = Files.getLastModifiedTime(file1).toMillis()
    val mtime2 = Files.getLastModifiedTime(file2).toMillis()
    val size = Files.size(file1)
    val sha = runBlocking { io.github.km20197.limpadroid.domain.fs.Hasher.sha256(file1) }

    val fakeChecker = FakePermissionChecker(storageGranted = true, notificationGranted = true)
    val fakePrefs = FakeAppPreferencesRepository()
    val container = createFakeAppContainer(fakeChecker, fakePrefs, rootPath)

    val db = openDatabase!!
    var keptInitialId = 0L
    var removableInitialId = 0L
    runBlocking {
      val repo = ScanRepository(db.scanSessionDao(), db.scanCandidateDao(), db.scanErrorDao())
      val session = repo.createSession(ScanScope.DUPLICATE, rootPath.toString())
      repo.appendFoundEventsAndErrors(
        sessionId = session.id,
        foundEvents = listOf(
          io.github.km20197.limpadroid.domain.model.ScanEvent.Found(
            candidate = Candidate(
              path = file1.toString(),
              sizeBytes = size,
              lastModified = mtime1,
              category = Category.DUPLICATE,
              ruleId = DuplicateRuleId.DUPLICATE_NO_PRIORITY.ruleId,
              confidence = Confidence.MEDIUM,
              groupId = sha,
              preselected = false,
            ),
            isKept = true,
          ),
          io.github.km20197.limpadroid.domain.model.ScanEvent.Found(
            candidate = Candidate(
              path = file2.toString(),
              sizeBytes = size,
              lastModified = mtime2,
              category = Category.DUPLICATE,
              ruleId = DuplicateRuleId.DUPLICATE_NO_PRIORITY.ruleId,
              confidence = Confidence.MEDIUM,
              groupId = sha,
              preselected = false,
            ),
            isKept = false,
          ),
        ),
        errors = emptyList(),
      )
      repo.finalizeWorkerRun(
        sessionId = session.id,
        targetStatusIfStillRunning = ScanStatus.COMPLETED,
        scannedFiles = 2L,
        scannedDirs = 2L,
        scannedBytes = size * 2L,
        checkpoint = WalkCheckpoint(settledFiles = 2L, settledDirs = 2L, settledBytes = size * 2L),
      )
      val groupRows = db.scanCandidateDao().getForGroup(session.id, sha)
      keptInitialId = groupRows.single { it.isKept }.id
      removableInitialId = groupRows.single { !it.isKept }.id
    }

    composeTestRule.setContent {
      LimpaDroidTheme(dynamicColor = false) {
        LimpaDroidNavHost(container = container)
      }
    }
    composeTestRule.waitForIdle()

    // Abre resultados de duplicados
    composeTestRule.onNodeWithTag("home_open_duplicate_results_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("results_duplicate_group_$sha")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    composeTestRule.onNodeWithTag("results_duplicate_no_priority_$sha").assertExists()

    // Troca a cópia preservada para file2 via botão "Preservar esta"
    composeTestRule.onNodeWithTag("results_list")
      .performScrollToNode(hasTestTag("results_duplicate_keep_button_$removableInitialId"))
    composeTestRule.onNodeWithTag("results_duplicate_keep_button_$removableInitialId").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("results_duplicate_kept_badge_$removableInitialId")
        .fetchSemanticsNodes()
        .isNotEmpty() ||
        composeTestRule.onAllNodesWithTag("results_duplicate_keep_button_$keptInitialId")
          .fetchSemanticsNodes()
          .isNotEmpty()
    }
    composeTestRule.onNodeWithTag("results_list")
      .performScrollToNode(hasTestTag("results_duplicate_kept_badge_$removableInitialId"))
    composeTestRule.onNodeWithTag("results_duplicate_kept_badge_$removableInitialId").assertExists()

    // Seleciona o excedente (agora file1) via toggle do grupo e move para a lixeira
    composeTestRule.onNodeWithTag("results_list")
      .performScrollToNode(hasTestTag("results_duplicate_group_toggle_$sha"))
    composeTestRule.onNodeWithTag("results_duplicate_group_toggle_$sha").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      val nodes = composeTestRule.onAllNodesWithTag("results_cleanup_button").fetchSemanticsNodes()
      nodes.isNotEmpty() && !nodes.first().config.contains(SemanticsProperties.Disabled)
    }
    composeTestRule.onNodeWithTag("results_cleanup_button").assertIsEnabled().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("results_confirm_dialog_confirm").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("results_empty_state")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }

    // Somente file1 foi movido; file2 foi preservado no disco
    assertFalse(Files.exists(file1))
    assertTrue(Files.exists(file2))

    // Volta e verifica configurações de duplicados (tamanho mínimo + adicionar pasta de prioridade)
    composeTestRule.onNodeWithTag("results_back_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_settings_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_duplicate_min_size_65536"))
    composeTestRule.onNodeWithTag("settings_duplicate_min_size_65536").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_add_priority_folder_button"))
    composeTestRule.onNodeWithTag("settings_add_priority_folder_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_priority_input").performTextInput("Download/VIP")
    composeTestRule.onNodeWithTag("settings_priority_confirm").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_priority_folder_6"))
    composeTestRule.onNodeWithText("Download/VIP").assertExists()
  }

  @Test
  fun `home opens whatsapp results shows warning keeps first run unchecked moves to trash and configures whatsapp settings`() {
    val rootPath = tempFolder.newFolder("wa_cuj").toPath().toRealPath()
    val sentDir = Files.createDirectories(
      rootPath.resolve("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent"),
    )
    val sentFile = Files.writeString(sentDir.resolve("IMG-SENT-OLD.jpg"), "wa-image-payload")
    val mtime = Files.getLastModifiedTime(sentFile).toMillis()

    val fakeChecker = FakePermissionChecker(storageGranted = true, notificationGranted = true)
    val fakePrefs = FakeAppPreferencesRepository()
    val container = createFakeAppContainer(fakeChecker, fakePrefs, rootPath)

    val db = openDatabase!!
    var candidateId = 0L
    runBlocking {
      val repo = ScanRepository(db.scanSessionDao(), db.scanCandidateDao(), db.scanErrorDao())
      val session = repo.createSession(ScanScope.WHATSAPP, rootPath.toString())
      repo.appendCandidatesAndErrors(
        sessionId = session.id,
        candidates = listOf(
          Candidate(
            path = sentFile.toString(),
            sizeBytes = 16L,
            lastModified = mtime,
            category = Category.WHATSAPP,
            ruleId = WhatsAppRuleId.WHATSAPP_IMAGES.ruleId,
            confidence = Confidence.HIGH,
            groupId = "WhatsApp:IMAGES",
            preselected = false,
          ) to "WhatsApp · Enviado (Sent) · 120 dias",
        ),
        errors = emptyList(),
      )
      repo.finalizeWorkerRun(
        sessionId = session.id,
        targetStatusIfStillRunning = ScanStatus.COMPLETED,
        scannedFiles = 1L,
        scannedDirs = 1L,
        scannedBytes = 16L,
        checkpoint = WalkCheckpoint(settledFiles = 1L, settledDirs = 1L, settledBytes = 16L),
      )
      candidateId = db.scanCandidateDao().getForGroup(session.id, "WhatsApp:IMAGES").single().id
    }

    composeTestRule.setContent {
      LimpaDroidTheme(dynamicColor = false) {
        LimpaDroidNavHost(container = container)
      }
    }
    composeTestRule.waitForIdle()

    // Abre resultados do WhatsApp na Home
    composeTestRule.onNodeWithTag("home_open_whatsapp_results_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("results_whatsapp_warning_banner")
        .fetchSemanticsNodes()
        .isNotEmpty() &&
        composeTestRule.onAllNodesWithTag("results_bottom_bar")
          .fetchSemanticsNodes()
          .isNotEmpty()
    }

    // Verifica texto obrigatório da seção 7.5 e estado inicial desmarcado mesmo com Confidence.HIGH
    val context = ApplicationProvider.getApplicationContext<Context>()
    composeTestRule.onNodeWithText(context.getString(R.string.results_whatsapp_warning_banner))
      .assertExists()
    composeTestRule.onNodeWithTag("results_cleanup_button").assertIsNotEnabled()

    // Ativa preferência de pré-seleção nas próximas varreduras
    composeTestRule.onNodeWithTag("results_whatsapp_auto_preselect_switch").performClick()
    composeTestRule.waitForIdle()

    // Seleciona manualmente o arquivo e move para a lixeira após diálogo com aviso do WhatsApp
    composeTestRule.onNodeWithTag("results_list")
      .performScrollToNode(hasTestTag("results_candidate_checkbox_$candidateId"))
    composeTestRule.onNodeWithTag("results_candidate_checkbox_$candidateId").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      val nodes = composeTestRule.onAllNodesWithTag("results_cleanup_button").fetchSemanticsNodes()
      nodes.isNotEmpty() && !nodes.first().config.contains(SemanticsProperties.Disabled)
    }

    composeTestRule.onNodeWithTag("results_cleanup_button").assertIsEnabled().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText(context.getString(R.string.results_whatsapp_confirm_note))
      .assertExists()
    composeTestRule.onNodeWithTag("results_confirm_dialog_confirm").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("results_empty_state")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    assertFalse(Files.exists(sentFile))

    // Volta, abre Lixeira e restaura o arquivo do WhatsApp
    composeTestRule.onNodeWithTag("results_back_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_open_trash_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("trash_empty_state")
        .fetchSemanticsNodes()
        .isEmpty()
    }
    val trashId = runBlocking { db.trashEntryDao().getByState("IN_TRASH").single().id }
    composeTestRule.onNodeWithTag("trash_items_list")
      .performScrollToNode(hasTestTag("trash_restore_button_$trashId"))
    composeTestRule.onNodeWithTag("trash_restore_button_$trashId").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("trash_confirm_dialog_confirm").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("trash_empty_state")
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    assertTrue(Files.exists(sentFile))

    // Volta à Home, abre Configurações e ajusta idade mínima e GIFs Animados do WhatsApp
    composeTestRule.onNodeWithTag("trash_back_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_settings_button").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_whatsapp_age_preset_30"))
    composeTestRule.onNodeWithTag("settings_whatsapp_age_preset_30").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Idade mínima da mídia: 30 dias").assertExists()

    composeTestRule.onNodeWithTag("settings_list")
      .performScrollToNode(hasTestTag("settings_whatsapp_type_switch_ANIMATED_GIFS"))
    composeTestRule.onNodeWithTag("settings_whatsapp_type_switch_ANIMATED_GIFS").performClick()
    composeTestRule.waitForIdle()
  }

  @Test
  fun `simplified home shows scan and total cleanup buttons moves preselected HIGH items and displays CC BY-NC license`() {
    val rootPath = tempFolder.newFolder("total_cleanup_cuj").toPath().toRealPath()
    val thumbsDir = Files.createDirectories(rootPath.resolve("DCIM/.thumbnails"))
    val thumbFile = Files.writeString(thumbsDir.resolve("thumb_high.jpg"), "thumb-bytes-high")
    val emptyDir = Files.createDirectories(rootPath.resolve("PastaVazia"))

    val fakeChecker = FakePermissionChecker(storageGranted = true, notificationGranted = true)
    val fakePrefs = FakeAppPreferencesRepository()
    val container = createFakeAppContainer(fakeChecker, fakePrefs, rootPath)

    val db = openDatabase!!
    runBlocking {
      val repo = ScanRepository(db.scanSessionDao(), db.scanCandidateDao(), db.scanErrorDao())
      val session = repo.createSession(ScanScope.FULL, rootPath.toString())
      repo.appendCandidatesAndErrors(
        sessionId = session.id,
        candidates = listOf(
          Candidate(
            path = thumbFile.toString(),
            sizeBytes = 16L,
            lastModified = Files.getLastModifiedTime(thumbFile).toMillis(),
            category = Category.SUPERFLUOUS,
            ruleId = SuperfluousRuleId.THUMBS.ruleId,
            confidence = Confidence.HIGH,
            preselected = true,
          ) to "Miniatura em cache",
          Candidate(
            path = emptyDir.toString(),
            sizeBytes = 0L,
            lastModified = Files.getLastModifiedTime(emptyDir).toMillis(),
            category = Category.SUPERFLUOUS,
            ruleId = SuperfluousRuleId.EMPTY_DIR.ruleId,
            confidence = Confidence.MEDIUM,
            preselected = false,
          ) to "Diretório vazio",
        ),
        errors = emptyList(),
      )
      repo.finalizeWorkerRun(
        sessionId = session.id,
        targetStatusIfStillRunning = ScanStatus.COMPLETED,
        scannedFiles = 2L,
        scannedDirs = 2L,
        scannedBytes = 16L,
        checkpoint = WalkCheckpoint(settledFiles = 2L, settledDirs = 2L, settledBytes = 16L),
      )
    }

    composeTestRule.setContent {
      LimpaDroidTheme(dynamicColor = false) {
        LimpaDroidNavHost(container = container)
      }
    }
    composeTestRule.waitForIdle()

    // Garante que apenas os 2 botões principais existem e os botões por categoria foram removidos
    composeTestRule.onNodeWithTag("home_start_scan_button").assertExists().assertIsEnabled()
    composeTestRule.onNodeWithTag("home_total_cleanup_button").assertExists().assertIsEnabled()
    assertTrue(
      composeTestRule.onAllNodesWithTag("home_analyze_superfluous_button")
        .fetchSemanticsNodes()
        .isEmpty(),
    )
    composeTestRule.onNodeWithText("Licença CC BY-NC 4.0 · Ricardo Coutinho Mello").assertExists()

    val snapshotBefore = runBlocking { container.scanController.getPreselectedCleanupRecordsBySession() }
    assertEquals(1, snapshotBefore.values.flatten().size)

    composeTestRule.onNodeWithTag("home_total_cleanup_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_total_cleanup_confirm_dialog").assertExists()
    composeTestRule.onNodeWithTag("home_total_cleanup_confirm_button").performClick()

    composeTestRule.waitUntil(timeoutMillis = 5_000L) {
      composeTestRule.onAllNodesWithTag("home_cleanup_manual_only_hint")
        .fetchSemanticsNodes()
        .isNotEmpty() && !Files.exists(thumbFile)
    }
    assertFalse(Files.exists(thumbFile))
    assertTrue(Files.exists(emptyDir))
  }

  private fun createFakeAppContainer(
    permissionChecker: PermissionChecker,
    preferencesRepository: AppPreferencesRepository,
    rootPath: Path,
  ): AppContainer {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, LimpaDroidDatabase::class.java)
      .allowMainThreadQueries()
      .build()
      .also { openDatabase = it }
    val repository = ScanRepository(
      sessionDao = db.scanSessionDao(),
      candidateDao = db.scanCandidateDao(),
      errorDao = db.scanErrorDao(),
    )
    val logDir = tempFolder.newFolder("logs_${System.nanoTime()}")
    val scanLogger = LocalScanLogger(logDir)
    val volumeResolver = object : StorageVolumeResolver {
      override fun resolvePrimarySharedStorage(): StorageRootResolution =
        StorageRootResolution.Available(rootPath = rootPath, volumeDescription = "Armazenamento de teste")
    }
    val trashRepository = TrashRepository(
      trashEntryDao = db.trashEntryDao(),
      operationHistoryDao = db.operationHistoryDao(),
      storageVolumeResolver = volumeResolver,
      mediaScannerHook = MediaScannerHook { },
      scanLogger = scanLogger,
      applicationId = "io.github.km20197.limpadroid",
      scanCandidateDao = db.scanCandidateDao(),
    )
    return FakeAppContainer(
      permissionChecker = permissionChecker,
      preferencesRepository = preferencesRepository,
      rootPath = rootPath,
      scanRepository = repository,
      trashRepository = trashRepository,
      volumeResolver = volumeResolver,
      scanLogger = scanLogger,
    )
  }

  private class FakePermissionChecker(
    var storageGranted: Boolean,
    var notificationGranted: Boolean,
  ) : PermissionChecker {
    override fun hasAllFilesAccess(): Boolean = storageGranted
    override fun hasNotificationPermission(): Boolean = notificationGranted
    override fun createAllFilesAccessIntent(): Intent = Intent("android.settings.TEST_SETTINGS")
  }

  private class FakeAppPreferencesRepository : AppPreferencesRepository {
    private val state = MutableStateFlow(false)
    private val rulesState = MutableStateFlow(
      io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId.DEFAULT_ENABLED_IDS,
    )
    private val exclusionsState = MutableStateFlow<Set<String>>(emptySet())
    private val dupMinSizeState = MutableStateFlow(DuplicateKeepPolicy.DEFAULT_MIN_SIZE_BYTES)
    private val dupPriorityFoldersState = MutableStateFlow(DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS)
    private val waMinAgeDaysState = MutableStateFlow(WhatsAppMediaRules.DEFAULT_AGE_DAYS)
    private val waMediaTypesState = MutableStateFlow(WhatsAppMediaType.DEFAULT_ENABLED_TYPE_NAMES)
    private val waAutoPreselectState = MutableStateFlow(false)

    override val notificationPromptHandled: Flow<Boolean> = state
    override val enabledSuperfluousRuleIds: Flow<Set<String>> = rulesState
    override val userExcludedPaths: Flow<Set<String>> = exclusionsState
    override val duplicateMinSizeBytes: Flow<Long> = dupMinSizeState
    override val duplicatePriorityFolders: Flow<List<String>> = dupPriorityFoldersState
    override val whatsappMinAgeDays: Flow<Int> = waMinAgeDaysState
    override val whatsappEnabledMediaTypes: Flow<Set<String>> = waMediaTypesState
    override val whatsappAutoPreselect: Flow<Boolean> = waAutoPreselectState

    override suspend fun setNotificationPromptHandled(handled: Boolean) {
      state.value = handled
    }

    override suspend fun setSuperfluousRuleEnabled(ruleId: String, enabled: Boolean) {
      val next = rulesState.value.toMutableSet()
      if (enabled) next.add(ruleId) else next.remove(ruleId)
      rulesState.value = next
    }

    override suspend fun addUserExcludedPath(path: String) {
      val trimmed = path.trim()
      if (trimmed.isNotEmpty()) {
        exclusionsState.value = exclusionsState.value + trimmed
      }
    }

    override suspend fun removeUserExcludedPath(path: String) {
      exclusionsState.value = exclusionsState.value - path
    }

    override suspend fun setDuplicateMinSizeBytes(bytes: Long) {
      dupMinSizeState.value = bytes.coerceAtLeast(1L)
    }

    override suspend fun setDuplicatePriorityFolders(folders: List<String>) {
      dupPriorityFoldersState.value = folders
        .map { DuplicateKeepPolicy.normalizeFolderSegments(it).joinToString("/") }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
    }

    override suspend fun setWhatsAppMinAgeDays(days: Int) {
      waMinAgeDaysState.value = WhatsAppMediaRules.clampAgeDays(days)
    }

    override suspend fun setWhatsAppMediaTypeEnabled(typeName: String, enabled: Boolean) {
      val next = waMediaTypesState.value.toMutableSet()
      if (enabled) next.add(typeName) else next.remove(typeName)
      waMediaTypesState.value = next
    }

    override suspend fun setWhatsAppAutoPreselect(enabled: Boolean) {
      waAutoPreselectState.value = enabled
    }
  }

  private class FakeAppContainer(
    override val permissionChecker: PermissionChecker,
    override val preferencesRepository: AppPreferencesRepository,
    private val rootPath: Path,
    private val scanRepository: ScanRepository,
    override val trashRepository: TrashRepository,
    volumeResolver: StorageVolumeResolver,
    scanLogger: LocalScanLogger,
  ) : AppContainer {
    override val installedAppsRepository: InstalledAppsRepository = object : InstalledAppsRepository {
      override suspend fun syncKnownApps(): InstalledAppsSnapshot =
        InstalledAppsSnapshot(emptyList(), emptyList())

      override suspend fun loadSnapshot(): InstalledAppsSnapshot =
        InstalledAppsSnapshot(emptyList(), emptyList())
    }

    override val diagnosticRepository: StorageDiagnosticRepository =
      DefaultStorageDiagnosticRepository(
        permissionChecker = permissionChecker,
        volumeResolver = volumeResolver,
        inspector = DirectoryInspector(),
      )

    override val scanWorkerDependencies: ScanWorkerDependencies =
      ScanWorkerDependencies(
        scanRepository = scanRepository,
        storageVolumeResolver = volumeResolver,
        scanEngine = InfrastructureScanEngine(FileWalker()),
        scanLogger = scanLogger,
        applicationId = "io.github.km20197.limpadroid",
      )

    override val trashCleanupDependencies: io.github.km20197.limpadroid.trash.TrashCleanupDependencies =
      io.github.km20197.limpadroid.trash.TrashCleanupDependencies(
        trashRepository = trashRepository,
        scanRepository = scanRepository,
        scanLogger = scanLogger,
        cleanupJobsDir = java.io.File(rootPath.toFile(), ".cleanup_jobs"),
      )

    override val trashController: TrashController = object : TrashController {
      override val inTrashEntries: Flow<List<TrashEntry>> = trashRepository.inTrashEntries
      override val trashStats: Flow<TrashStats> = trashRepository.trashStats
      override val recentHistory: Flow<List<OperationHistoryRecord>> = trashRepository.recentHistory
      override suspend fun reconcileAndPurgeOnStartup() {
        trashRepository.reconcilePendingAndRebuildIndex()
        trashRepository.purgeExpired()
      }
      override suspend fun restoreEntry(entryId: String): TrashRestoreResult =
        trashRepository.restoreEntry(entryId)
      override suspend fun deletePermanently(entryId: String): PurgeSummary =
        trashRepository.deletePermanently(entryId)
      override suspend fun emptyTrash(): PurgeSummary =
        trashRepository.emptyTrash()
      override suspend fun moveCandidatesToTrash(
        records: List<ScanCandidateRecord>,
        userExcludedPaths: Set<Path>,
      ): BatchTrashMoveSummary =
        trashRepository.moveCandidatesToTrash(records, userExcludedPaths)
      override suspend fun repairStaleMediaStore(
        records: List<ScanCandidateRecord>,
        userExcludedPaths: Set<Path>,
      ): MediaStoreRepairSummary =
        trashRepository.repairStaleMediaStore(records, userExcludedPaths)
    }

    override val scanController: ScanController = object : ScanController {
      override val latestSession: Flow<ScanSession?> = scanRepository.latestSession

      override fun observeSession(sessionId: String): Flow<ScanSession?> =
        scanRepository.observeSession(sessionId)

      override fun observeRecentErrors(sessionId: String): Flow<List<WalkError>> =
        scanRepository.observeRecentErrors(sessionId)

      override fun observeCategorySummary(category: Category): Flow<CategorySummary> =
        scanRepository.observeCategorySummary(category)

      override fun observeCategoryCandidates(category: Category): Flow<List<ScanCandidateRecord>> =
        scanRepository.observeCategoryCandidates(category)

      override fun observeLatestSessionForCategory(category: Category): Flow<ScanSession?> =
        scanRepository.observeLatestSessionForCategory(category)

      override fun observeOverallCleanupOverview(): Flow<OverallCleanupOverview> =
        scanRepository.observeOverallCleanupOverview()

      override suspend fun getPreselectedCleanupRecordsBySession(): Map<String, List<ScanCandidateRecord>> =
        scanRepository.getPreselectedCleanupRecordsBySession()

      override suspend fun setCandidatePreselected(candidateId: Long, preselected: Boolean) {
        scanRepository.setCandidatePreselected(candidateId, preselected)
      }

      override suspend fun setCandidatesPreselected(candidateIds: List<Long>, preselected: Boolean) {
        scanRepository.setCandidatesPreselected(candidateIds, preselected)
      }

      override suspend fun swapDuplicateKeptCandidate(
        sessionId: String,
        groupId: String,
        newKeepCandidateId: Long,
      ) {
        scanRepository.swapDuplicateKeptCandidate(sessionId, groupId, newKeepCandidateId)
      }

      override suspend fun removeCleanedCandidates(sessionId: String?, candidateIds: List<Long>) {
        scanRepository.removeCleanedCandidates(sessionId, candidateIds)
      }

      override suspend fun reconcileInterruptedSessions() = Unit

      override suspend fun startNewScan(scope: ScanScope): ScanStartResult {
        val created = scanRepository.createSession(scope = scope, rootPath = rootPath.toString())
        scanRepository.saveProgress(
          sessionId = created.id,
          scannedFiles = 12L,
          scannedDirs = 2L,
          scannedBytes = 2048L,
          checkpoint = WalkCheckpoint(
            lastProcessedPath = "${rootPath}/Documents/a.txt",
            completedSubtrees = setOf("Documents"),
            settledFiles = 12L,
            settledDirs = 2L,
            settledBytes = 2048L,
          ),
        )
        return ScanStartResult.Started(created.id)
      }

      override suspend fun resumeScan(sessionId: String): Boolean {
        val current = scanRepository.getSession(sessionId) ?: return false
        scanRepository.markRunningForResume(sessionId)
        scanRepository.appendCandidatesAndErrors(
          sessionId = sessionId,
          candidates = emptyList(),
          errors = listOf(
            WalkError(
              path = "${rootPath}/Restricted",
              errorType = "AccessDeniedException",
              message = "Acesso negado",
            ),
          ),
        )
        scanRepository.finalizeWorkerRun(
          sessionId = sessionId,
          targetStatusIfStillRunning = ScanStatus.COMPLETED,
          scannedFiles = current.settledFiles + 5L,
          scannedDirs = current.settledDirs + 1L,
          scannedBytes = current.settledBytes + 1024L,
          checkpoint = WalkCheckpoint(
            lastProcessedPath = "${rootPath}/Pictures/b.jpg",
            completedSubtrees = current.completedSubtrees + "Pictures",
            settledFiles = current.settledFiles + 5L,
            settledDirs = current.settledDirs + 1L,
            settledBytes = current.settledBytes + 1024L,
          ),
        )
        return true
      }

      override suspend fun pauseScan(sessionId: String) {
        val current = scanRepository.getSession(sessionId) ?: return
        scanRepository.finalizeWorkerRun(
          sessionId = sessionId,
          targetStatusIfStillRunning = ScanStatus.PAUSED,
          scannedFiles = current.scannedFiles,
          scannedDirs = current.scannedDirs,
          scannedBytes = current.scannedBytes,
          checkpoint = WalkCheckpoint(
            lastProcessedPath = current.lastProcessedPath,
            completedSubtrees = current.completedSubtrees,
            settledFiles = current.settledFiles,
            settledDirs = current.settledDirs,
            settledBytes = current.settledBytes,
          ),
        )
      }

      override suspend fun cancelScan(sessionId: String) {
        scanRepository.markRequestedStatus(sessionId = sessionId, status = ScanStatus.CANCELLED)
      }
    }
  }
}
