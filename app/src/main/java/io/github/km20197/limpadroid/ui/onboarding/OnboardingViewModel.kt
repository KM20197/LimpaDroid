package io.github.km20197.limpadroid.ui.onboarding

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OnboardingUiState(
  val hasAllFilesAccess: Boolean = false,
  val hasNotificationPermission: Boolean = false,
  val notificationPromptHandled: Boolean = false,
) {
  val canContinue: Boolean
    get() = hasAllFilesAccess

  val showNotificationWarning: Boolean
    get() = !hasNotificationPermission && notificationPromptHandled
}

class OnboardingViewModel(
  private val permissionChecker: PermissionChecker,
  private val preferencesRepository: AppPreferencesRepository,
) : ViewModel() {

  private val allFilesAccess = MutableStateFlow(permissionChecker.hasAllFilesAccess())
  private val notificationGranted = MutableStateFlow(permissionChecker.hasNotificationPermission())

  val uiState: StateFlow<OnboardingUiState> =
    combine(
      allFilesAccess,
      notificationGranted,
      preferencesRepository.notificationPromptHandled,
    ) { storageOk, notifOk, promptHandled ->
      OnboardingUiState(
        hasAllFilesAccess = storageOk,
        hasNotificationPermission = notifOk,
        notificationPromptHandled = promptHandled,
      )
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = OnboardingUiState(
        hasAllFilesAccess = permissionChecker.hasAllFilesAccess(),
        hasNotificationPermission = permissionChecker.hasNotificationPermission(),
        notificationPromptHandled = false,
      ),
    )

  fun refreshPermissions() {
    allFilesAccess.value = permissionChecker.hasAllFilesAccess()
    notificationGranted.value = permissionChecker.hasNotificationPermission()
  }

  fun createAllFilesAccessIntent(): Intent = permissionChecker.createAllFilesAccessIntent()

  fun onNotificationPermissionResult(granted: Boolean) {
    notificationGranted.value = granted
    viewModelScope.launch {
      preferencesRepository.setNotificationPromptHandled(true)
    }
  }

  fun skipNotifications() {
    viewModelScope.launch {
      preferencesRepository.setNotificationPromptHandled(true)
    }
  }

  class Factory(
    private val permissionChecker: PermissionChecker,
    private val preferencesRepository: AppPreferencesRepository,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      OnboardingViewModel(permissionChecker, preferencesRepository) as T
  }
}
