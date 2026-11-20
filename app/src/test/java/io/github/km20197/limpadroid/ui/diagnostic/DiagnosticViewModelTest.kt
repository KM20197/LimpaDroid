package io.github.km20197.limpadroid.ui.diagnostic

import io.github.km20197.limpadroid.data.fs.StorageDiagnosticRepository
import io.github.km20197.limpadroid.domain.model.DiagnosticReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticViewModelTest {

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
  fun `refresh emits Success when repository returns report`() =
    runTest(testDispatcher) {
      val repo = object : StorageDiagnosticRepository {
        override suspend fun inspectStorage(appVersion: String, sdkInt: Int): DiagnosticReport =
          sampleReport(appVersion, sdkInt, "/storage/emulated/0")
      }

      val vm = DiagnosticViewModel(repository = repo, appVersion = "0.1.0 (1)", sdkInt = 37)
      advanceUntilIdle()

      val state = vm.uiState.value
      assertTrue(state is DiagnosticUiState.Success)
      assertTrue((state as DiagnosticUiState.Success).formattedText.contains("Android SDK: 37"))
    }

  @Test
  fun `refresh emits Error when repository throws`() =
    runTest(testDispatcher) {
      val repo = object : StorageDiagnosticRepository {
        override suspend fun inspectStorage(appVersion: String, sdkInt: Int): DiagnosticReport {
          throw IllegalStateException("Falha simulada")
        }
      }

      val vm = DiagnosticViewModel(repository = repo, appVersion = "0.1.0 (1)", sdkInt = 37)
      advanceUntilIdle()

      val state = vm.uiState.value
      assertTrue(state is DiagnosticUiState.Error)
      assertTrue((state as DiagnosticUiState.Error).message.contains("Falha simulada"))
    }

  @Test
  fun `rapid refresh cancels previous job so stale result cannot overwrite newer one`() =
    runTest(testDispatcher) {
      val firstGate = CompletableDeferred<Unit>()
      var callCount = 0
      val repo = object : StorageDiagnosticRepository {
        override suspend fun inspectStorage(appVersion: String, sdkInt: Int): DiagnosticReport {
          callCount += 1
          return if (callCount == 1) {
            firstGate.await()
            sampleReport(appVersion, sdkInt, "/stale/root")
          } else {
            sampleReport(appVersion, sdkInt, "/fresh/root")
          }
        }
      }

      val vm = DiagnosticViewModel(repository = repo, appVersion = "0.1.0 (1)", sdkInt = 37)
      runCurrent()
      vm.refresh()
      advanceUntilIdle()
      firstGate.complete(Unit)
      advanceUntilIdle()

      val state = vm.uiState.value as DiagnosticUiState.Success
      assertEquals("/fresh/root", state.report.storageRoot)
    }

  private fun sampleReport(appVersion: String, sdkInt: Int, root: String): DiagnosticReport =
    DiagnosticReport(
      storageRoot = root,
      hasAllFilesAccess = true,
      androidMediaAccessible = true,
      rootEntries = emptyList(),
      androidMediaEntries = emptyList(),
      whatsappKnownRoots = emptyList(),
      generatedAtIso = "2026-11-16T00:00:00Z",
      appVersion = appVersion,
      sdkInt = sdkInt,
    )
}
