package io.github.km20197.limpadroid.ui.home

import android.content.Intent
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanStartResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

  private val mainDispatcher = UnconfinedTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(mainDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `refreshPermissions updates both storage and notification flags`() {
    var storage = false
    var notifications = false
    val checker = FakePermissionChecker(
      hasStorage = { storage },
      hasNotif = { notifications },
    )

    val vm = HomeViewModel(checker, FakeScanController())
    assertFalse(vm.uiState.value.hasAllFilesAccess)
    assertFalse(vm.uiState.value.hasNotificationPermission)

    storage = true
    notifications = true
    vm.refreshPermissions()

    assertTrue(vm.uiState.value.hasAllFilesAccess)
    assertTrue(vm.uiState.value.hasNotificationPermission)
  }

  @Test
  fun `startInfrastructureScan pauses and resumes active session when storage access granted`() {
    val controller = FakeScanController()
    val checker = FakePermissionChecker(hasStorage = { true }, hasNotif = { true })
    val vm = HomeViewModel(checker, controller)

    vm.startInfrastructureScan()
    assertEquals(ScanStatus.RUNNING, vm.uiState.value.latestScanSession?.status)
    assertNull(vm.uiState.value.scanActionMessage)

    vm.pauseActiveScan()
    assertEquals(ScanStatus.PAUSED, vm.uiState.value.latestScanSession?.status)

    vm.resumePausedScan()
    assertEquals(ScanStatus.RUNNING, vm.uiState.value.latestScanSession?.status)

    vm.cancelActiveOrPausedScan()
    assertEquals(ScanStatus.CANCELLED, vm.uiState.value.latestScanSession?.status)
  }

  @Test
  fun `startInfrastructureScan surfaces error message when storage unavailable`() {
    val controller = FakeScanController().apply {
      nextStartResult = ScanStartResult.StorageUnavailable("Volume ausente")
    }
    val checker = FakePermissionChecker(hasStorage = { true }, hasNotif = { true })
    val vm = HomeViewModel(checker, controller)

    vm.startInfrastructureScan()

    assertEquals("Volume ausente", vm.uiState.value.scanActionMessage)
    vm.clearScanActionMessage()
    assertNull(vm.uiState.value.scanActionMessage)
  }

  private class FakePermissionChecker(
    private val hasStorage: () -> Boolean,
    private val hasNotif: () -> Boolean,
  ) : PermissionChecker {
    override fun hasAllFilesAccess(): Boolean = hasStorage()
    override fun hasNotificationPermission(): Boolean = hasNotif()
    override fun createAllFilesAccessIntent(): Intent = Intent("test.ACTION")
  }

  private class FakeScanController : ScanController {
    private val sessionState = MutableStateFlow<ScanSession?>(null)
    var nextStartResult: ScanStartResult? = null

    override val latestSession: Flow<ScanSession?> = sessionState

    override fun observeSession(sessionId: String): Flow<ScanSession?> = sessionState

    override fun observeRecentErrors(sessionId: String): Flow<List<WalkError>> = flowOf(emptyList())

    override suspend fun reconcileInterruptedSessions() = Unit

    override suspend fun startNewScan(scope: ScanScope): ScanStartResult {
      nextStartResult?.let { return it }
      val created = ScanSession(
        id = "session-1",
        scope = scope,
        status = ScanStatus.RUNNING,
        startedAt = 1_000L,
        updatedAt = 1_000L,
        rootPath = "/storage/emulated/0",
      )
      sessionState.value = created
      return ScanStartResult.Started(created.id)
    }

    override suspend fun resumeScan(sessionId: String): Boolean {
      val current = sessionState.value ?: return false
      sessionState.value = current.copy(status = ScanStatus.RUNNING)
      return true
    }

    override suspend fun pauseScan(sessionId: String) {
      sessionState.value = sessionState.value?.copy(status = ScanStatus.PAUSED)
    }

    override suspend fun cancelScan(sessionId: String) {
      sessionState.value = sessionState.value?.copy(status = ScanStatus.CANCELLED)
    }
  }
}
