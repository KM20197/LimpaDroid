package io.github.km20197.limpadroid.ui.diagnostic

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.km20197.limpadroid.BuildConfig
import io.github.km20197.limpadroid.data.fs.StorageDiagnosticRepository
import io.github.km20197.limpadroid.domain.diagnostic.DirectoryInspector
import io.github.km20197.limpadroid.domain.model.DiagnosticReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DiagnosticUiState {
  data object Loading : DiagnosticUiState
  data class Success(
    val report: DiagnosticReport,
    val formattedText: String,
  ) : DiagnosticUiState
  data class Error(val message: String) : DiagnosticUiState
}

class DiagnosticViewModel(
  private val repository: StorageDiagnosticRepository,
  private val appVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
  private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ViewModel() {

  private val _uiState = MutableStateFlow<DiagnosticUiState>(DiagnosticUiState.Loading)
  val uiState: StateFlow<DiagnosticUiState> = _uiState.asStateFlow()

  init {
    refresh()
  }

  fun refresh() {
    _uiState.value = DiagnosticUiState.Loading
    viewModelScope.launch {
      try {
        val report = repository.inspectStorage(appVersion = appVersion, sdkInt = sdkInt)
        val formatted = DirectoryInspector.formatReportAsText(report)
        _uiState.value = DiagnosticUiState.Success(
          report = report,
          formattedText = formatted,
        )
      } catch (e: Exception) {
        _uiState.value = DiagnosticUiState.Error(
          message = e.message ?: e.javaClass.simpleName,
        )
      }
    }
  }

  class Factory(
    private val repository: StorageDiagnosticRepository,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      DiagnosticViewModel(repository) as T
  }
}
