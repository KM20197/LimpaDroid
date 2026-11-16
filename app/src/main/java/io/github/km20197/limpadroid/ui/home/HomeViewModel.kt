package io.github.km20197.limpadroid.ui.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.km20197.limpadroid.BuildConfig
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HomeUiState(
  val hasAllFilesAccess: Boolean = false,
  val hasNotificationPermission: Boolean = false,
  val versionName: String = BuildConfig.VERSION_NAME,
  val versionCode: Int = BuildConfig.VERSION_CODE,
)

class HomeViewModel(
  private val permissionChecker: PermissionChecker,
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    HomeUiState(
      hasAllFilesAccess = permissionChecker.hasAllFilesAccess(),
      hasNotificationPermission = permissionChecker.hasNotificationPermission(),
    ),
  )
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  fun refreshPermissions() {
    _uiState.update { current ->
      current.copy(
        hasAllFilesAccess = permissionChecker.hasAllFilesAccess(),
        hasNotificationPermission = permissionChecker.hasNotificationPermission(),
      )
    }
  }

  fun createAllFilesAccessIntent(): Intent = permissionChecker.createAllFilesAccessIntent()

  class Factory(
    private val permissionChecker: PermissionChecker,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      HomeViewModel(permissionChecker) as T
  }
}
