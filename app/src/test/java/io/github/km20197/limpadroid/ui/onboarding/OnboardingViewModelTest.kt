package io.github.km20197.limpadroid.ui.onboarding

import android.content.Intent
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `skipNotifications marks prompt handled and reveals warning while allowing continue when storage granted`() =
    runTest(testDispatcher) {
      val checker = FakePermissionChecker(storageGranted = false, notificationGranted = false)
      val prefs = FakePreferences()
      val vm = OnboardingViewModel(checker, prefs)

      val collectJob = backgroundScope.launch { vm.uiState.collect {} }
      advanceUntilIdle()

      assertFalse(vm.uiState.value.canContinue)
      assertFalse(vm.uiState.value.showNotificationWarning)

      vm.skipNotifications()
      advanceUntilIdle()

      assertTrue(vm.uiState.value.notificationPromptHandled)
      assertTrue(vm.uiState.value.showNotificationWarning)
      assertFalse(vm.uiState.value.canContinue)

      checker.storageGranted = true
      vm.refreshPermissions()
      advanceUntilIdle()

      assertTrue(vm.uiState.value.canContinue)
      assertTrue(prefs.notificationPromptHandled.first())
      collectJob.cancel()
    }

  @Test
  fun `granting notifications hides warning and marks prompt handled`() =
    runTest(testDispatcher) {
      val checker = FakePermissionChecker(storageGranted = true, notificationGranted = false)
      val prefs = FakePreferences()
      val vm = OnboardingViewModel(checker, prefs)

      val collectJob = backgroundScope.launch { vm.uiState.collect {} }
      advanceUntilIdle()

      vm.onNotificationPermissionResult(granted = true)
      advanceUntilIdle()

      assertTrue(vm.uiState.value.hasNotificationPermission)
      assertFalse(vm.uiState.value.showNotificationWarning)
      assertTrue(vm.uiState.value.canContinue)
      collectJob.cancel()
    }

  private class FakePermissionChecker(
    var storageGranted: Boolean,
    var notificationGranted: Boolean,
  ) : PermissionChecker {
    override fun hasAllFilesAccess(): Boolean = storageGranted
    override fun hasNotificationPermission(): Boolean = notificationGranted
    override fun createAllFilesAccessIntent(): Intent = Intent("test.ACTION")
  }

  private class FakePreferences : AppPreferencesRepository {
    private val flow = MutableStateFlow(false)
    private val rulesFlow = MutableStateFlow(
      io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId.DEFAULT_ENABLED_IDS,
    )
    private val exclusionsFlow = MutableStateFlow<Set<String>>(emptySet())

    override val notificationPromptHandled: Flow<Boolean> = flow
    override val enabledSuperfluousRuleIds: Flow<Set<String>> = rulesFlow
    override val userExcludedPaths: Flow<Set<String>> = exclusionsFlow

    override suspend fun setNotificationPromptHandled(handled: Boolean) {
      flow.value = handled
    }

    override suspend fun setSuperfluousRuleEnabled(ruleId: String, enabled: Boolean) {
      val updated = rulesFlow.value.toMutableSet()
      if (enabled) updated.add(ruleId) else updated.remove(ruleId)
      rulesFlow.value = updated
    }

    override suspend fun addUserExcludedPath(path: String) {
      val trimmed = path.trim()
      if (trimmed.isNotEmpty()) {
        exclusionsFlow.value = exclusionsFlow.value + trimmed
      }
    }

    override suspend fun removeUserExcludedPath(path: String) {
      exclusionsFlow.value = exclusionsFlow.value - path
    }
  }
}
