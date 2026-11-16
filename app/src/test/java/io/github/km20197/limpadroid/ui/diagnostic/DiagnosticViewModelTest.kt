package io.github.km20197.limpadroid.ui.diagnostic

import io.github.km20197.limpadroid.data.fs.StorageDiagnosticRepository
import io.github.km20197.limpadroid.domain.model.DiagnosticReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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
          DiagnosticReport(
            storageRoot = "/storage/emulated/0",
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
}
