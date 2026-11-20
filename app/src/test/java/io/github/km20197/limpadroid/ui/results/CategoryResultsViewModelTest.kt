package io.github.km20197.limpadroid.ui.results

import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.domain.model.BatchTrashMoveSummary
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.MediaStoreRepairSummary
import io.github.km20197.limpadroid.domain.model.OperationHistoryRecord
import io.github.km20197.limpadroid.domain.model.PurgeSummary
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.domain.model.TrashRestoreResult
import io.github.km20197.limpadroid.domain.model.TrashStats
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaRules
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppRuleId
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanStartResult
import io.github.km20197.limpadroid.trash.TrashController
import java.nio.file.Path
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryResultsViewModelTest {

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
  fun `groups candidates by rule confidence and toggles candidate and group selection`() {
    val scanController = FakeScanController(
      initialRecords = listOf(
        record(1L, "/storage/emulated/0/Download/old.apk", 5_000L, SuperfluousRuleId.OLD_APK.ruleId, Confidence.LOW, false),
        record(2L, "/storage/emulated/0/Download/a.part", 2_000L, SuperfluousRuleId.PARTIAL_DL.ruleId, Confidence.HIGH, true),
        record(3L, "/storage/emulated/0/Download/b.part", 1_000L, SuperfluousRuleId.PARTIAL_DL.ruleId, Confidence.HIGH, true),
      ),
    )
    val vm = CategoryResultsViewModel(
      category = Category.SUPERFLUOUS,
      scanController = scanController,
      trashController = FakeTrashController(),
      preferencesRepository = FakePrefs(),
    )

    val state = vm.uiState.value
    assertEquals(2, state.groups.size)
    // HIGH ordered before LOW
    assertEquals(SuperfluousRuleId.PARTIAL_DL.ruleId, state.groups[0].ruleId)
    assertEquals(SuperfluousRuleId.OLD_APK.ruleId, state.groups[1].ruleId)
    assertEquals(2, state.selectedCount)
    assertEquals(3_000L, state.selectedBytes)

    // Toggle LOW candidate on
    vm.toggleCandidate(state.groups[1].items.single())
    assertEquals(3, vm.uiState.value.selectedCount)
    assertEquals(8_000L, vm.uiState.value.selectedBytes)

    // Toggle HIGH group off
    vm.toggleGroup(SuperfluousRuleId.PARTIAL_DL.ruleId)
    assertEquals(1, vm.uiState.value.selectedCount)
    assertEquals(5_000L, vm.uiState.value.selectedBytes)
  }

  @Test
  fun `confirmCleanup separates MediaStore repair from trash move passes exclusions and cleans candidates`() {
    val scanController = FakeScanController(
      initialRecords = listOf(
        record(10L, "/storage/emulated/0/Download/temp.part", 2_048L, SuperfluousRuleId.PARTIAL_DL.ruleId, Confidence.HIGH, true),
        record(11L, "/storage/emulated/0/Pictures/stale.jpg", 0L, SuperfluousRuleId.STALE_MEDIASTORE.ruleId, Confidence.HIGH, true),
        record(12L, "/storage/emulated/0/Download/keep.apk", 4_096L, SuperfluousRuleId.OLD_APK.ruleId, Confidence.LOW, false),
      ),
    )
    val trashController = FakeTrashController()
    val prefs = FakePrefs(exclusions = setOf("/storage/emulated/0/Documents/Privado"))
    val vm = CategoryResultsViewModel(
      category = Category.SUPERFLUOUS,
      scanController = scanController,
      trashController = trashController,
      preferencesRepository = prefs,
    )

    vm.requestCleanup()
    assertTrue(vm.uiState.value.isConfirmationVisible)
    assertEquals(1, vm.uiState.value.selectedTrashCount)
    assertEquals(1, vm.uiState.value.selectedMediaStoreCount)

    vm.confirmCleanup()
    assertFalse(vm.uiState.value.isConfirmationVisible)
    assertEquals(listOf(10L), trashController.lastMovedIds)
    assertEquals(listOf(11L), trashController.lastRepairedIds)
    assertTrue(trashController.lastExcludedPaths.contains("/storage/emulated/0/Documents/Privado"))

    val feedback = vm.uiState.value.feedback as CategoryResultsFeedback.CleanupDone
    assertEquals(1, feedback.movedCount)
    assertEquals(2_048L, feedback.movedBytes)
    assertEquals(1, feedback.repairedMediaStoreCount)
    assertEquals(0, feedback.skippedOrFailedCount)

    // Only unselected item 12L remains
    assertEquals(1, vm.uiState.value.totalCount)
    assertEquals(12L, vm.uiState.value.groups.single().items.single().id)
  }

  @Test
  fun `duplicate category groups by groupId enforces kept invariant refuses toggling kept copy and swaps kept copy`() {
    val shaHigh = "a".repeat(64)
    val shaMed = "b".repeat(64)
    val scanController = FakeScanController(
      initialRecords = listOf(
        duplicateRecord(1L, "/storage/emulated/0/DCIM/Camera/orig.jpg", 4_096L, shaHigh, "DUPLICATE_PRIORITY", Confidence.HIGH, preselected = false, isKept = true),
        duplicateRecord(2L, "/storage/emulated/0/Download/copia.jpg", 4_096L, shaHigh, "DUPLICATE_PRIORITY", Confidence.HIGH, preselected = true, isKept = false),
        duplicateRecord(3L, "/storage/emulated/0/Temp/x.bin", 8_192L, shaMed, "DUPLICATE_NO_PRIORITY", Confidence.MEDIUM, preselected = false, isKept = true),
        duplicateRecord(4L, "/storage/emulated/0/Outros/y.bin", 8_192L, shaMed, "DUPLICATE_NO_PRIORITY", Confidence.MEDIUM, preselected = false, isKept = false),
      ),
    )
    val vm = CategoryResultsViewModel(
      category = Category.DUPLICATE,
      scanController = scanController,
      trashController = FakeTrashController(),
      preferencesRepository = FakePrefs(),
    )

    val state = vm.uiState.value
    assertEquals(2, state.duplicateGroups.size)
    // HIGH ordered before MEDIUM
    assertEquals(shaHigh, state.duplicateGroups[0].groupId)
    assertTrue(state.duplicateGroups[0].hasPriorityMatch)
    assertEquals(shaMed, state.duplicateGroups[1].groupId)
    assertFalse(state.duplicateGroups[1].hasPriorityMatch)
    // Apenas cópias removíveis entram no total/seleção (1 de HIGH + 1 de MEDIUM = 2 removíveis; 1 pré-selecionado)
    assertEquals(2, state.totalCount)
    assertEquals(1, state.selectedCount)
    assertEquals(4_096L, state.selectedBytes)

    // Tentar desmarcar/marcar a cópia preservada não altera nada
    val keptHigh = state.duplicateGroups[0].items.single { it.isKept }
    vm.toggleCandidate(keptHigh)
    assertEquals(1, vm.uiState.value.selectedCount)
    assertFalse(vm.uiState.value.duplicateGroups[0].items.single { it.id == keptHigh.id }.candidate.preselected)

    // Toggle do grupo sem prioridade marca apenas a cópia removível
    vm.toggleDuplicateGroup(shaMed)
    assertEquals(2, vm.uiState.value.selectedCount)
    assertEquals(12_288L, vm.uiState.value.selectedBytes)
    assertFalse(vm.uiState.value.duplicateGroups[1].items.single { it.isKept }.candidate.preselected)
    assertTrue(vm.uiState.value.duplicateGroups[1].items.single { !it.isKept }.candidate.preselected)

    // Troca manual da cópia preservada no grupo HIGH
    vm.swapKeptCopy(shaHigh, 2L)
    val updatedHighGroup = vm.uiState.value.duplicateGroups.first { it.groupId == shaHigh }
    assertTrue(updatedHighGroup.items.single { it.id == 2L }.isKept)
    assertFalse(updatedHighGroup.items.single { it.id == 2L }.candidate.preselected)
    assertFalse(updatedHighGroup.items.single { it.id == 1L }.isKept)
    assertTrue(updatedHighGroup.items.single { it.id == 1L }.candidate.preselected)
  }

  @Test
  fun `whatsapp category exposes age and autoPreselect settings keeps first run unchecked and cleans selected`() {
    val imgRecord = ScanCandidateRecord(
      id = 21L,
      sessionId = "session-wa",
      candidate = Candidate(
        path = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/IMG-1.jpg",
        sizeBytes = 6_000L,
        lastModified = 100L,
        category = Category.WHATSAPP,
        ruleId = WhatsAppRuleId.WHATSAPP_IMAGES.ruleId,
        confidence = Confidence.HIGH,
        groupId = "WhatsApp:IMAGES",
        preselected = false,
      ),
      reason = "WhatsApp · Enviado (Sent) · 120 dias",
    )
    val scanController = FakeScanController(initialRecords = listOf(imgRecord))
    val trashController = FakeTrashController()
    val prefs = FakePrefs(whatsappMinAgeDays = 60, whatsappAutoPreselect = false)
    val vm = CategoryResultsViewModel(
      category = Category.WHATSAPP,
      scanController = scanController,
      trashController = trashController,
      preferencesRepository = prefs,
    )

    assertEquals(60, vm.uiState.value.whatsappMinAgeDays)
    assertFalse(vm.uiState.value.whatsappAutoPreselect)
    assertEquals(1, vm.uiState.value.totalCount)
    assertEquals(0, vm.uiState.value.selectedCount)

    vm.setWhatsAppAutoPreselect(true)
    assertTrue(vm.uiState.value.whatsappAutoPreselect)

    vm.toggleCandidate(vm.uiState.value.groups.single().items.single())
    assertEquals(1, vm.uiState.value.selectedCount)
    assertEquals(6_000L, vm.uiState.value.selectedBytes)

    vm.requestCleanup()
    assertTrue(vm.uiState.value.isConfirmationVisible)
    vm.confirmCleanup()

    assertEquals(listOf(21L), trashController.lastMovedIds)
    assertEquals(0, vm.uiState.value.totalCount)
  }

  private fun record(
    id: Long,
    path: String,
    size: Long,
    ruleId: String,
    confidence: Confidence,
    preselected: Boolean,
  ): ScanCandidateRecord = ScanCandidateRecord(
    id = id,
    sessionId = "session-1",
    candidate = Candidate(
      path = path,
      sizeBytes = size,
      lastModified = 100L,
      category = Category.SUPERFLUOUS,
      ruleId = ruleId,
      confidence = confidence,
      preselected = preselected,
    ),
    reason = "teste",
  )

  private fun duplicateRecord(
    id: Long,
    path: String,
    size: Long,
    groupId: String,
    ruleId: String,
    confidence: Confidence,
    preselected: Boolean,
    isKept: Boolean,
  ): ScanCandidateRecord = ScanCandidateRecord(
    id = id,
    sessionId = "session-1",
    candidate = Candidate(
      path = path,
      sizeBytes = size,
      lastModified = 100L,
      category = Category.DUPLICATE,
      ruleId = ruleId,
      confidence = confidence,
      groupId = groupId,
      preselected = preselected,
    ),
    reason = null,
    isKept = isKept,
  )

  private class FakeScanController(
    initialRecords: List<ScanCandidateRecord>,
  ) : ScanController {
    private val recordsFlow = MutableStateFlow(initialRecords)
    private val sessionFlow = MutableStateFlow<ScanSession?>(
      ScanSession(
        id = "session-1",
        scope = ScanScope.SUPERFLUOUS,
        status = ScanStatus.COMPLETED,
        startedAt = 10L,
        updatedAt = 20L,
        rootPath = "/storage/emulated/0",
      ),
    )

    override val latestSession: Flow<ScanSession?> = sessionFlow
    override fun observeSession(sessionId: String): Flow<ScanSession?> = sessionFlow
    override fun observeRecentErrors(sessionId: String): Flow<List<WalkError>> = flowOf(emptyList())
    override fun observeCategoryCandidates(category: Category): Flow<List<ScanCandidateRecord>> = recordsFlow
    override fun observeLatestSessionForCategory(category: Category): Flow<ScanSession?> = sessionFlow

    override suspend fun setCandidatePreselected(candidateId: Long, preselected: Boolean) {
      recordsFlow.value = recordsFlow.value.map { rec ->
        if (rec.id == candidateId && !rec.isKept) {
          rec.copy(candidate = rec.candidate.copy(preselected = preselected))
        } else {
          rec
        }
      }
    }

    override suspend fun setCandidatesPreselected(candidateIds: List<Long>, preselected: Boolean) {
      val set = candidateIds.toSet()
      recordsFlow.value = recordsFlow.value.map { rec ->
        if (rec.id in set && !rec.isKept) rec.copy(candidate = rec.candidate.copy(preselected = preselected)) else rec
      }
    }

    override suspend fun swapDuplicateKeptCandidate(
      sessionId: String,
      groupId: String,
      newKeepCandidateId: Long,
    ) {
      recordsFlow.value = recordsFlow.value.map { rec ->
        if (rec.candidate.groupId != groupId) {
          rec
        } else if (rec.id == newKeepCandidateId) {
          rec.copy(isKept = true, candidate = rec.candidate.copy(preselected = false))
        } else if (rec.isKept) {
          rec.copy(
            isKept = false,
            candidate = rec.candidate.copy(preselected = rec.candidate.confidence == Confidence.HIGH),
          )
        } else {
          rec
        }
      }
    }

    override suspend fun removeCleanedCandidates(sessionId: String?, candidateIds: List<Long>) {
      val set = candidateIds.toSet()
      recordsFlow.value = recordsFlow.value.filterNot { it.id in set }
    }

    override suspend fun reconcileInterruptedSessions() = Unit
    override suspend fun startNewScan(scope: ScanScope): ScanStartResult = ScanStartResult.Started("session-2")
    override suspend fun resumeScan(sessionId: String): Boolean = true
    override suspend fun pauseScan(sessionId: String) = Unit
    override suspend fun cancelScan(sessionId: String) = Unit
  }

  private class FakeTrashController : TrashController {
    var lastMovedIds: List<Long> = emptyList()
    var lastRepairedIds: List<Long> = emptyList()
    var lastExcludedPaths: Set<String> = emptySet()

    override val inTrashEntries: Flow<List<TrashEntry>> = flowOf(emptyList())
    override val trashStats: Flow<TrashStats> = flowOf(TrashStats())
    override val recentHistory: Flow<List<OperationHistoryRecord>> = flowOf(emptyList())
    override suspend fun reconcileAndPurgeOnStartup() = Unit
    override suspend fun restoreEntry(entryId: String): TrashRestoreResult =
      TrashRestoreResult.MissingPayload("none")
    override suspend fun deletePermanently(entryId: String): PurgeSummary = PurgeSummary(0, 0L)
    override suspend fun emptyTrash(): PurgeSummary = PurgeSummary(0, 0L)

    override suspend fun moveCandidatesToTrash(
      records: List<ScanCandidateRecord>,
      userExcludedPaths: Set<Path>,
    ): BatchTrashMoveSummary {
      lastMovedIds = records.map { it.id }
      lastExcludedPaths = userExcludedPaths.map { it.toString() }.toSet()
      return BatchTrashMoveSummary(
        movedCandidateIds = lastMovedIds,
        movedBytes = records.sumOf { it.candidate.sizeBytes },
        staleOrSkippedCount = 0,
        failedCount = 0,
      )
    }

    override suspend fun repairStaleMediaStore(
      records: List<ScanCandidateRecord>,
      userExcludedPaths: Set<Path>,
    ): MediaStoreRepairSummary {
      lastRepairedIds = records.map { it.id }
      lastExcludedPaths = lastExcludedPaths + userExcludedPaths.map { it.toString() }
      return MediaStoreRepairSummary(repairedCandidateIds = lastRepairedIds, rejectedOrExistingCount = 0)
    }
  }

  private class FakePrefs(
    exclusions: Set<String> = emptySet(),
    whatsappMinAgeDays: Int = WhatsAppMediaRules.DEFAULT_AGE_DAYS,
    whatsappAutoPreselect: Boolean = false,
  ) : AppPreferencesRepository {
    private val waAgeFlow = MutableStateFlow(whatsappMinAgeDays)
    private val waAutoPreselectFlow = MutableStateFlow(whatsappAutoPreselect)

    override val notificationPromptHandled: Flow<Boolean> = flowOf(true)
    override val enabledSuperfluousRuleIds: Flow<Set<String>> = flowOf(SuperfluousRuleId.DEFAULT_ENABLED_IDS)
    override val userExcludedPaths: Flow<Set<String>> = flowOf(exclusions)
    override val whatsappMinAgeDays: Flow<Int> = waAgeFlow
    override val whatsappAutoPreselect: Flow<Boolean> = waAutoPreselectFlow

    override suspend fun setNotificationPromptHandled(handled: Boolean) = Unit
    override suspend fun setSuperfluousRuleEnabled(ruleId: String, enabled: Boolean) = Unit
    override suspend fun addUserExcludedPath(path: String) = Unit
    override suspend fun removeUserExcludedPath(path: String) = Unit
    override suspend fun setWhatsAppMinAgeDays(days: Int) {
      waAgeFlow.value = WhatsAppMediaRules.clampAgeDays(days)
    }
    override suspend fun setWhatsAppAutoPreselect(enabled: Boolean) {
      waAutoPreselectFlow.value = enabled
    }
  }
}
