package io.github.km20197.limpadroid.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanStartResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ScanStatusUiState(
  val hasAllFilesAccess: Boolean = false,
  val session: ScanSession? = null,
  val errors: List<WalkError> = emptyList(),
  val errorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ScanStatusViewModel(
  private val permissionChecker: PermissionChecker,
  private val scanController: ScanController,
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    ScanStatusUiState(
      hasAllFilesAccess = permissionChecker.hasAllFilesAccess(),
    ),
  )
  val uiState: StateFlow<ScanStatusUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      scanController.reconcileInterruptedSessions()
    }
    viewModelScope.launch {
      scanController.latestSession.collect { session ->
        _uiState.update { it.copy(session = session) }
      }
    }
    viewModelScope.launch {
      scanController.latestSession
        .flatMapLatest { session ->
          if (session == null) flowOf(emptyList()) else scanController.observeRecentErrors(session.id)
        }
        .collect { errors ->
          _uiState.update { it.copy(errors = errors) }
        }
    }
  }

  fun refreshPermissions() {
    _uiState.update { it.copy(hasAllFilesAccess = permissionChecker.hasAllFilesAccess()) }
  }

  fun startNewScan() {
    viewModelScope.launch {
      if (!permissionChecker.hasAllFilesAccess()) return@launch
      when (val result = scanController.startNewScan(ScanScope.INFRASTRUCTURE)) {
        is ScanStartResult.Started, is ScanStartResult.AlreadyRunning -> {
          _uiState.update { it.copy(errorMessage = null) }
        }
        is ScanStartResult.StorageUnavailable -> {
          _uiState.update { it.copy(errorMessage = result.reason) }
        }
      }
    }
  }

  fun pauseScan() {
    val sessionId = _uiState.value.session?.id ?: return
    viewModelScope.launch {
      scanController.pauseScan(sessionId)
    }
  }

  fun resumeScan() {
    val sessionId = _uiState.value.session?.id ?: return
    viewModelScope.launch {
      scanController.resumeScan(sessionId)
    }
  }

  fun cancelScan() {
    val sessionId = _uiState.value.session?.id ?: return
    viewModelScope.launch {
      scanController.cancelScan(sessionId)
    }
  }

  class Factory(
    private val permissionChecker: PermissionChecker,
    private val scanController: ScanController,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      ScanStatusViewModel(permissionChecker, scanController) as T
  }
}
