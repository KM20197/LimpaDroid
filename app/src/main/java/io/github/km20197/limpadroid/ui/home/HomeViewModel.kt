package io.github.km20197.limpadroid.ui.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.km20197.limpadroid.BuildConfig
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanStartResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
  val hasAllFilesAccess: Boolean = false,
  val hasNotificationPermission: Boolean = false,
  val versionName: String = BuildConfig.VERSION_NAME,
  val versionCode: Int = BuildConfig.VERSION_CODE,
  val latestScanSession: ScanSession? = null,
  val scanActionMessage: String? = null,
)

class HomeViewModel(
  private val permissionChecker: PermissionChecker,
  private val scanController: ScanController,
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    HomeUiState(
      hasAllFilesAccess = permissionChecker.hasAllFilesAccess(),
      hasNotificationPermission = permissionChecker.hasNotificationPermission(),
    ),
  )
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      scanController.reconcileInterruptedSessions()
    }
    viewModelScope.launch {
      scanController.latestSession.collect { session ->
        _uiState.update { current ->
          current.copy(latestScanSession = session)
        }
      }
    }
  }

  fun refreshPermissions() {
    _uiState.update { current ->
      current.copy(
        hasAllFilesAccess = permissionChecker.hasAllFilesAccess(),
        hasNotificationPermission = permissionChecker.hasNotificationPermission(),
      )
    }
  }

  fun startInfrastructureScan() {
    viewModelScope.launch {
      if (!permissionChecker.hasAllFilesAccess()) return@launch
      when (val result = scanController.startNewScan(ScanScope.INFRASTRUCTURE)) {
        is ScanStartResult.Started, is ScanStartResult.AlreadyRunning -> {
          _uiState.update { it.copy(scanActionMessage = null) }
        }
        is ScanStartResult.StorageUnavailable -> {
          _uiState.update { it.copy(scanActionMessage = result.reason) }
        }
      }
    }
  }

  fun pauseActiveScan() {
    val sessionId = _uiState.value.latestScanSession?.id ?: return
    viewModelScope.launch {
      scanController.pauseScan(sessionId)
    }
  }

  fun resumePausedScan() {
    val sessionId = _uiState.value.latestScanSession?.id ?: return
    viewModelScope.launch {
      scanController.resumeScan(sessionId)
    }
  }

  fun cancelActiveOrPausedScan() {
    val sessionId = _uiState.value.latestScanSession?.id ?: return
    viewModelScope.launch {
      scanController.cancelScan(sessionId)
    }
  }

  fun clearScanActionMessage() {
    _uiState.update { it.copy(scanActionMessage = null) }
  }

  fun createAllFilesAccessIntent(): Intent = permissionChecker.createAllFilesAccessIntent()

  class Factory(
    private val permissionChecker: PermissionChecker,
    private val scanController: ScanController,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      HomeViewModel(permissionChecker, scanController) as T
  }
}
