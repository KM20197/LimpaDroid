package io.github.km20197.limpadroid

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.scan.InfrastructureScanEngine
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanStartResult
import io.github.km20197.limpadroid.scan.ScanWorkerDependencies
import io.github.km20197.limpadroid.ui.navigation.LimpaDroidNavHost
import io.github.km20197.limpadroid.ui.onboarding.OnboardingScreen
import io.github.km20197.limpadroid.ui.onboarding.OnboardingViewModel
import io.github.km20197.limpadroid.ui.theme.LimpaDroidTheme
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class LimpaDroidRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @get:Rule
  val tempFolder = TemporaryFolder()

  private var openDatabase: LimpaDroidDatabase? = null

  @After
  fun tearDown() {
    openDatabase?.close()
    openDatabase = null
  }

  @Test
  fun `manifest declares expected permissions and excludes INTERNET`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val packageInfo = context.packageManager.getPackageInfo(
      context.packageName,
      PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
    )
    val permissions = packageInfo.requestedPermissions?.toSet().orEmpty()

    assertTrue(permissions.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE))
    assertFalse(permissions.contains(Manifest.permission.QUERY_ALL_PACKAGES))
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
    val volumeResolver = object : StorageVolumeResolver {
      override fun resolvePrimarySharedStorage(): StorageRootResolution =
        StorageRootResolution.Available(rootPath = rootPath, volumeDescription = "Armazenamento de teste")
    }
    return FakeAppContainer(
      permissionChecker = permissionChecker,
      preferencesRepository = preferencesRepository,
      rootPath = rootPath,
      scanRepository = repository,
      volumeResolver = volumeResolver,
      logDir = logDir,
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
    override val notificationPromptHandled: Flow<Boolean> = state
    override suspend fun setNotificationPromptHandled(handled: Boolean) {
      state.value = handled
    }
  }

  private class FakeAppContainer(
    override val permissionChecker: PermissionChecker,
    override val preferencesRepository: AppPreferencesRepository,
    private val rootPath: Path,
    private val scanRepository: ScanRepository,
    volumeResolver: StorageVolumeResolver,
    logDir: java.io.File,
  ) : AppContainer {
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
        scanLogger = LocalScanLogger(logDir),
        applicationId = "io.github.km20197.limpadroid",
      )

    override val scanController: ScanController = object : ScanController {
      override val latestSession: Flow<ScanSession?> = scanRepository.latestSession

      override fun observeSession(sessionId: String): Flow<ScanSession?> =
        scanRepository.observeSession(sessionId)

      override fun observeRecentErrors(sessionId: String): Flow<List<WalkError>> =
        scanRepository.observeRecentErrors(sessionId)

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
