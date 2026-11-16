package io.github.km20197.limpadroid.ui.scan

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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScanStatusViewModelTest {

  private val dispatcher = UnconfinedTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun `viewModel exposes session transitions and logged errors`() {
    val controller = FakeScanController()
    val checker = object : PermissionChecker {
      override fun hasAllFilesAccess(): Boolean = true
      override fun hasNotificationPermission(): Boolean = true
      override fun createAllFilesAccessIntent(): Intent = Intent("test.ACTION")
    }

    val vm = ScanStatusViewModel(checker, controller)
    vm.startNewScan()
    assertEquals(ScanStatus.RUNNING, vm.uiState.value.session?.status)

    vm.pauseScan()
    assertEquals(ScanStatus.PAUSED, vm.uiState.value.session?.status)

    vm.resumeScan()
    assertEquals(ScanStatus.RUNNING, vm.uiState.value.session?.status)

    vm.cancelScan()
    assertEquals(ScanStatus.CANCELLED, vm.uiState.value.session?.status)
    assertEquals(1, vm.uiState.value.errors.size)
  }

  private class FakeScanController : ScanController {
    private val sessionFlow = MutableStateFlow<ScanSession?>(null)
    private val errorsFlow = MutableStateFlow(
      listOf(WalkError("/storage/emulated/0/Blocked", "AccessDeniedException", "Negado")),
    )

    override val latestSession: Flow<ScanSession?> = sessionFlow
    override fun observeSession(sessionId: String): Flow<ScanSession?> = sessionFlow
    override fun observeRecentErrors(sessionId: String): Flow<List<WalkError>> = errorsFlow
    override suspend fun reconcileInterruptedSessions() = Unit

    override suspend fun startNewScan(scope: ScanScope): ScanStartResult {
      val s = ScanSession("s1", scope, ScanStatus.RUNNING, "/storage/emulated/0", 1L, 1L)
      sessionFlow.value = s
      return ScanStartResult.Started(s.id)
    }

    override suspend fun resumeScan(sessionId: String): Boolean {
      sessionFlow.value = sessionFlow.value?.copy(status = ScanStatus.RUNNING)
      return true
    }

    override suspend fun pauseScan(sessionId: String) {
      sessionFlow.value = sessionFlow.value?.copy(status = ScanStatus.PAUSED)
    }

    override suspend fun cancelScan(sessionId: String) {
      sessionFlow.value = sessionFlow.value?.copy(status = ScanStatus.CANCELLED)
    }
  }
}
