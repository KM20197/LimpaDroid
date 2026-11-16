package io.github.km20197.limpadroid

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.km20197.limpadroid.data.fs.AndroidStorageVolumeResolver
import io.github.km20197.limpadroid.data.fs.DefaultStorageDiagnosticRepository
import io.github.km20197.limpadroid.data.fs.StorageDiagnosticRepository
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.permission.AndroidPermissionChecker
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.domain.diagnostic.DirectoryInspector
import io.github.km20197.limpadroid.ui.navigation.LimpaDroidNavHost
import io.github.km20197.limpadroid.ui.onboarding.OnboardingScreen
import io.github.km20197.limpadroid.ui.onboarding.OnboardingViewModel
import io.github.km20197.limpadroid.ui.theme.LimpaDroidTheme
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    val container = FakeAppContainer(fakeChecker, fakePrefs, rootPath)

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
    rootPath: java.nio.file.Path,
  ) : AppContainer {
    override val diagnosticRepository: StorageDiagnosticRepository =
      DefaultStorageDiagnosticRepository(
        permissionChecker = permissionChecker,
        volumeResolver = object : StorageVolumeResolver {
          override fun resolvePrimarySharedStorage(): StorageRootResolution =
            StorageRootResolution.Available(rootPath = rootPath, volumeDescription = "Armazenamento de teste")
        },
        inspector = DirectoryInspector(),
      )
  }
}
