package io.github.km20197.limpadroid.ui.trash

import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.OperationHistoryRecord
import io.github.km20197.limpadroid.domain.model.PurgeSummary
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.domain.model.TrashEntryState
import io.github.km20197.limpadroid.domain.model.TrashRestoreResult
import io.github.km20197.limpadroid.domain.model.TrashStats
import io.github.km20197.limpadroid.trash.TrashController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashViewModelTest {

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
  fun `restore flow requires confirmation and emits restored feedback`() = runTest {
    val entry = sampleEntry("entry-restore", "/storage/emulated/0/Download/a.tmp")
    val fake = FakeTrashController(initialEntries = listOf(entry))
    val viewModel = TrashViewModel(fake)
    advanceUntilIdle()

    assertEquals(1, viewModel.uiState.value.entries.size)
    assertTrue(fake.startupCalled)

    viewModel.requestRestore(entry)
    assertEquals(TrashConfirmation.Restore(entry), viewModel.uiState.value.pendingConfirmation)

    viewModel.confirmPendingAction()
    advanceUntilIdle()

    assertNull(viewModel.uiState.value.pendingConfirmation)
    assertEquals(TrashFeedback.Restored(entry.originalPath), viewModel.uiState.value.feedback)
    assertEquals(listOf("entry-restore"), fake.restoredIds)

    viewModel.clearFeedback()
    assertNull(viewModel.uiState.value.feedback)
  }

  @Test
  fun `delete forever and empty trash require confirmation and update feedback`() = runTest {
    val entry = sampleEntry("entry-del", "/storage/emulated/0/Download/b.tmp")
    val fake = FakeTrashController(initialEntries = listOf(entry))
    val viewModel = TrashViewModel(fake)
    advanceUntilIdle()

    viewModel.requestDeleteForever(entry)
    assertEquals(TrashConfirmation.DeleteForever(entry), viewModel.uiState.value.pendingConfirmation)
    viewModel.dismissConfirmation()
    assertNull(viewModel.uiState.value.pendingConfirmation)

    viewModel.requestDeleteForever(entry)
    viewModel.confirmPendingAction()
    advanceUntilIdle()
    assertTrue(viewModel.uiState.value.feedback is TrashFeedback.PurgedSingle)
    assertEquals(listOf("entry-del"), fake.purgedIds)

    fake.entriesFlow.value = listOf(entry)
    advanceUntilIdle()
    viewModel.requestEmptyTrash()
    assertEquals(TrashConfirmation.EmptyTrash, viewModel.uiState.value.pendingConfirmation)
    viewModel.confirmPendingAction()
    advanceUntilIdle()

    assertEquals(
      TrashFeedback.Emptied(count = 1, formattedBytes = "1,0 KiB"),
      viewModel.uiState.value.feedback,
    )
    assertTrue(fake.emptied)
  }

  private fun sampleEntry(id: String, path: String) = TrashEntry(
    id = id,
    originalPath = path,
    trashPath = "/storage/emulated/0/Documents/LimpaDroid/.lixeira/20261117/$id/payload",
    sizeBytes = 1024L,
    sha256 = "abc",
    originalMtime = 100L,
    category = Category.SUPERFLUOUS,
    ruleId = "PARTIAL_DL",
    deletedAt = 1_700_000_000_000L,
    expiresAt = 1_702_592_000_000L,
    state = TrashEntryState.IN_TRASH,
  )

  private class FakeTrashController(
    initialEntries: List<TrashEntry>,
  ) : TrashController {
    val entriesFlow = MutableStateFlow(initialEntries)
    val statsFlow = MutableStateFlow(
      TrashStats(
        inTrashCount = initialEntries.size,
        inTrashBytes = initialEntries.sumOf { it.sizeBytes },
      ),
    )
    val historyFlow = MutableStateFlow<List<OperationHistoryRecord>>(emptyList())

    var startupCalled = false
    val restoredIds = mutableListOf<String>()
    val purgedIds = mutableListOf<String>()
    var emptied = false

    override val inTrashEntries: Flow<List<TrashEntry>> = entriesFlow
    override val trashStats: Flow<TrashStats> = statsFlow
    override val recentHistory: Flow<List<OperationHistoryRecord>> = historyFlow

    override suspend fun reconcileAndPurgeOnStartup() {
      startupCalled = true
    }

    override suspend fun restoreEntry(entryId: String): TrashRestoreResult {
      restoredIds.add(entryId)
      val found = entriesFlow.value.first { it.id == entryId }
      entriesFlow.value = entriesFlow.value.filterNot { it.id == entryId }
      return TrashRestoreResult.Restored(found, found.originalPath)
    }

    override suspend fun deletePermanently(entryId: String): PurgeSummary {
      purgedIds.add(entryId)
      entriesFlow.value = entriesFlow.value.filterNot { it.id == entryId }
      return PurgeSummary(purgedCount = 1, freedBytes = 1024L)
    }

    override suspend fun emptyTrash(): PurgeSummary {
      emptied = true
      val count = entriesFlow.value.size
      entriesFlow.value = emptyList()
      return PurgeSummary(purgedCount = count, freedBytes = count * 1024L)
    }
  }
}
