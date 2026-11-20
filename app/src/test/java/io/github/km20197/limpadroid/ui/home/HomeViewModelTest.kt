package io.github.km20197.limpadroid.ui.home

import android.content.Intent
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.CategorySummary
import io.github.km20197.limpadroid.domain.model.OperationHistoryRecord
import io.github.km20197.limpadroid.domain.model.PurgeSummary
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.domain.model.TrashRestoreResult
import io.github.km20197.limpadroid.domain.model.TrashStats
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsRepository
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsSnapshot
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanStartResult
import io.github.km20197.limpadroid.trash.TrashController
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

    val vm = HomeViewModel(checker, FakeScanController(), FakeTrashController())
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
    val trashController = FakeTrashController().apply {
      statsFlow.value = TrashStats(inTrashCount = 2, inTrashBytes = 4096L, freedBytes = 1024L)
    }
    val checker = FakePermissionChecker(hasStorage = { true }, hasNotif = { true })
    val vm = HomeViewModel(checker, controller, trashController)

    assertEquals(2, vm.uiState.value.trashStats.inTrashCount)
    assertEquals(1024L, vm.uiState.value.trashStats.freedBytes)

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
  fun `startCategoryScan uses SUPERFLUOUS scope and reflects live summary`() {
    val controller = FakeScanController().apply {
      superfluousSummaryFlow.value = CategorySummary(
        category = Category.SUPERFLUOUS,
        latestSessionId = "s-sup",
        candidateCount = 5,
        totalBytes = 8192L,
      )
    }
    val checker = FakePermissionChecker(hasStorage = { true }, hasNotif = { true })
    val vm = HomeViewModel(checker, controller, FakeTrashController())

    assertEquals(5, vm.uiState.value.superfluousSummary.candidateCount)
    assertEquals(8192L, vm.uiState.value.superfluousSummary.totalBytes)

    vm.startCategoryScan(Category.SUPERFLUOUS)
    assertEquals(ScanScope.SUPERFLUOUS, vm.uiState.value.latestScanSession?.scope)
  }

  @Test
  fun `init syncs known apps and observes ORPHAN summary`() {
    val controller = FakeScanController().apply {
      orphanSummaryFlow.value = CategorySummary(
        category = Category.ORPHAN,
        latestSessionId = "s-orp",
        candidateCount = 3,
        totalBytes = 12_288L,
      )
    }
    var syncCalls = 0
    val appsRepo = object : InstalledAppsRepository {
      override suspend fun syncKnownApps(): InstalledAppsSnapshot {
        syncCalls += 1
        return InstalledAppsSnapshot(emptyList(), emptyList())
      }

      override suspend fun loadSnapshot(): InstalledAppsSnapshot =
        InstalledAppsSnapshot(emptyList(), emptyList())
    }
    val checker = FakePermissionChecker(hasStorage = { true }, hasNotif = { true })
    val vm = HomeViewModel(checker, controller, FakeTrashController(), appsRepo)

    assertEquals(1, syncCalls)
    assertEquals(3, vm.uiState.value.orphanSummary.candidateCount)
    assertEquals(12_288L, vm.uiState.value.orphanSummary.totalBytes)

    vm.startCategoryScan(Category.ORPHAN)
    assertEquals(ScanScope.ORPHAN, vm.uiState.value.latestScanSession?.scope)
  }

  @Test
  fun `startInfrastructureScan surfaces error message when storage unavailable`() {
    val controller = FakeScanController().apply {
      nextStartResult = ScanStartResult.StorageUnavailable("Volume ausente")
    }
    val checker = FakePermissionChecker(hasStorage = { true }, hasNotif = { true })
    val vm = HomeViewModel(checker, controller, FakeTrashController())

    vm.startInfrastructureScan()

    assertEquals("Volume ausente", vm.uiState.value.scanActionMessage)
    vm.clearScanActionMessage()
    assertNull(vm.uiState.value.scanActionMessage)
  }

  @Test
  fun `startCategoryScan uses DUPLICATE scope and reflects live summary`() {
    val controller = FakeScanController().apply {
      duplicateSummaryFlow.value = CategorySummary(
        category = Category.DUPLICATE,
        latestSessionId = "s-dup",
        candidateCount = 4,
        totalBytes = 16_384L,
      )
    }
    val checker = FakePermissionChecker(hasStorage = { true }, hasNotif = { true })
    val vm = HomeViewModel(checker, controller, FakeTrashController())

    assertEquals(4, vm.uiState.value.duplicateSummary.candidateCount)
    assertEquals(16_384L, vm.uiState.value.duplicateSummary.totalBytes)

    vm.startCategoryScan(Category.DUPLICATE)
    assertEquals(ScanScope.DUPLICATE, vm.uiState.value.latestScanSession?.scope)
  }

  @Test
  fun `startCategoryScan uses WHATSAPP scope and reflects live summary`() {
    val controller = FakeScanController().apply {
      whatsappSummaryFlow.value = CategorySummary(
        category = Category.WHATSAPP,
        latestSessionId = "s-wa",
        candidateCount = 7,
        totalBytes = 32_768L,
      )
    }
    val checker = FakePermissionChecker(hasStorage = { true }, hasNotif = { true })
    val vm = HomeViewModel(checker, controller, FakeTrashController())

    assertEquals(7, vm.uiState.value.whatsappSummary.candidateCount)
    assertEquals(32_768L, vm.uiState.value.whatsappSummary.totalBytes)

    vm.startCategoryScan(Category.WHATSAPP)
    assertEquals(ScanScope.WHATSAPP, vm.uiState.value.latestScanSession?.scope)
  }

  private class FakePermissionChecker(
    private val hasStorage: () -> Boolean,
    private val hasNotif: () -> Boolean,
  ) : PermissionChecker {
    override fun hasAllFilesAccess(): Boolean = hasStorage()
    override fun hasNotificationPermission(): Boolean = hasNotif()
    override fun createAllFilesAccessIntent(): Intent = Intent("test.ACTION")
  }

  private class FakeTrashController : TrashController {
    val statsFlow = MutableStateFlow(TrashStats())
    override val inTrashEntries: Flow<List<TrashEntry>> = flowOf(emptyList())
    override val trashStats: Flow<TrashStats> = statsFlow
    override val recentHistory: Flow<List<OperationHistoryRecord>> = flowOf(emptyList())
    override suspend fun reconcileAndPurgeOnStartup() = Unit
    override suspend fun restoreEntry(entryId: String): TrashRestoreResult =
      TrashRestoreResult.MissingPayload("empty")
    override suspend fun deletePermanently(entryId: String): PurgeSummary = PurgeSummary(0, 0L)
    override suspend fun emptyTrash(): PurgeSummary = PurgeSummary(0, 0L)
  }

  private class FakeScanController : ScanController {
    private val sessionState = MutableStateFlow<ScanSession?>(null)
    val superfluousSummaryFlow = MutableStateFlow(CategorySummary(Category.SUPERFLUOUS))
    val orphanSummaryFlow = MutableStateFlow(CategorySummary(Category.ORPHAN))
    val duplicateSummaryFlow = MutableStateFlow(CategorySummary(Category.DUPLICATE))
    val whatsappSummaryFlow = MutableStateFlow(CategorySummary(Category.WHATSAPP))
    var nextStartResult: ScanStartResult? = null

    override val latestSession: Flow<ScanSession?> = sessionState

    override fun observeSession(sessionId: String): Flow<ScanSession?> = sessionState

    override fun observeRecentErrors(sessionId: String): Flow<List<WalkError>> = flowOf(emptyList())

    override fun observeCategorySummary(category: Category): Flow<CategorySummary> = when (category) {
      Category.SUPERFLUOUS -> superfluousSummaryFlow
      Category.ORPHAN -> orphanSummaryFlow
      Category.DUPLICATE -> duplicateSummaryFlow
      Category.WHATSAPP -> whatsappSummaryFlow
    }

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
