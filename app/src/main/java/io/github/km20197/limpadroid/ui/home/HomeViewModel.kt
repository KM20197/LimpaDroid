package io.github.km20197.limpadroid.ui.home

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.km20197.limpadroid.BuildConfig
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.CategorySummary
import io.github.km20197.limpadroid.domain.model.OverallCleanupOverview
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.TrashStats
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsRepository
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanStartResult
import io.github.km20197.limpadroid.trash.TrashController
import io.github.km20197.limpadroid.ui.results.CategoryResultsFeedback
import java.nio.file.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
  val hasAllFilesAccess: Boolean = false,
  val hasNotificationPermission: Boolean = false,
  val versionName: String = BuildConfig.VERSION_NAME,
  val versionCode: Int = BuildConfig.VERSION_CODE,
  val latestScanSession: ScanSession? = null,
  val scanActionMessage: String? = null,
  val trashStats: TrashStats = TrashStats(),
  val superfluousSummary: CategorySummary = CategorySummary(Category.SUPERFLUOUS),
  val orphanSummary: CategorySummary = CategorySummary(Category.ORPHAN),
  val duplicateSummary: CategorySummary = CategorySummary(Category.DUPLICATE),
  val whatsappSummary: CategorySummary = CategorySummary(Category.WHATSAPP),
  val cleanupOverview: OverallCleanupOverview = OverallCleanupOverview(),
  val isCleanupRunning: Boolean = false,
  val isTotalCleanupConfirmationVisible: Boolean = false,
  val totalCleanupFeedback: CategoryResultsFeedback? = null,
)

class HomeViewModel(
  private val permissionChecker: PermissionChecker,
  private val scanController: ScanController,
  private val trashController: TrashController,
  private val installedAppsRepository: InstalledAppsRepository? = null,
  private val preferencesRepository: AppPreferencesRepository? = null,
) : ViewModel() {

  private val _uiState = MutableStateFlow(
    HomeUiState(
      hasAllFilesAccess = permissionChecker.hasAllFilesAccess(),
      hasNotificationPermission = permissionChecker.hasNotificationPermission(),
    ),
  )
  val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

  private val isSubmittingTotalCleanup = MutableStateFlow(false)

  init {
    viewModelScope.launch {
      scanController.reconcileInterruptedSessions()
    }
    viewModelScope.launch {
      trashController.reconcileAndPurgeOnStartup()
    }
    viewModelScope.launch {
      combine(isSubmittingTotalCleanup, trashController.isBatchCleanupRunning) { local, remote ->
        local || remote
      }.collect { running ->
        _uiState.update { it.copy(isCleanupRunning = running) }
      }
    }
    viewModelScope.launch {
      scanController.latestSession.collect { session ->
        _uiState.update { current ->
          current.copy(latestScanSession = session)
        }
      }
    }
    viewModelScope.launch {
      trashController.trashStats.collect { stats ->
        _uiState.update { current ->
          current.copy(trashStats = stats)
        }
      }
    }
    viewModelScope.launch {
      installedAppsRepository?.let { repo ->
        runCatching { repo.syncKnownApps() }
      }
    }
    viewModelScope.launch {
      scanController.observeOverallCleanupOverview().collect { overview ->
        _uiState.update { current ->
          current.copy(cleanupOverview = overview)
        }
      }
    }
    viewModelScope.launch {
      scanController.observeCategorySummary(Category.SUPERFLUOUS).collect { summary ->
        _uiState.update { current ->
          current.copy(superfluousSummary = summary)
        }
      }
    }
    viewModelScope.launch {
      scanController.observeCategorySummary(Category.ORPHAN).collect { summary ->
        _uiState.update { current ->
          current.copy(orphanSummary = summary)
        }
      }
    }
    viewModelScope.launch {
      scanController.observeCategorySummary(Category.DUPLICATE).collect { summary ->
        _uiState.update { current ->
          current.copy(duplicateSummary = summary)
        }
      }
    }
    viewModelScope.launch {
      scanController.observeCategorySummary(Category.WHATSAPP).collect { summary ->
        _uiState.update { current ->
          current.copy(whatsappSummary = summary)
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

  fun startFullScan() {
    startScanWithScope(ScanScope.FULL)
  }

  fun startInfrastructureScan() {
    startScanWithScope(ScanScope.INFRASTRUCTURE)
  }

  fun startCategoryScan(category: Category) {
    val scope = when (category) {
      Category.SUPERFLUOUS -> ScanScope.SUPERFLUOUS
      Category.ORPHAN -> ScanScope.ORPHAN
      Category.DUPLICATE -> ScanScope.DUPLICATE
      Category.WHATSAPP -> ScanScope.WHATSAPP
    }
    startScanWithScope(scope)
  }

  private fun startScanWithScope(scope: ScanScope) {
    viewModelScope.launch {
      if (!permissionChecker.hasAllFilesAccess()) return@launch
      when (val result = scanController.startNewScan(scope)) {
        is ScanStartResult.Started, is ScanStartResult.AlreadyRunning -> {
          _uiState.update { it.copy(scanActionMessage = null) }
        }
        is ScanStartResult.StorageUnavailable -> {
          _uiState.update { it.copy(scanActionMessage = result.reason) }
        }
      }
    }
  }

  fun requestTotalCleanup() {
    val state = _uiState.value
    if (!state.hasAllFilesAccess || state.isCleanupRunning || !state.cleanupOverview.hasPreselected) return
    _uiState.update { it.copy(isTotalCleanupConfirmationVisible = true) }
  }

  fun dismissTotalCleanupConfirmation() {
    _uiState.update { it.copy(isTotalCleanupConfirmationVisible = false) }
  }

  fun confirmTotalCleanup() {
    val current = _uiState.value
    if (!current.hasAllFilesAccess || isSubmittingTotalCleanup.value) return
    _uiState.update { it.copy(isTotalCleanupConfirmationVisible = false) }
    isSubmittingTotalCleanup.value = true

    viewModelScope.launch {
      try {
        val groupedBySession = scanController.getPreselectedCleanupRecordsBySession()
        if (groupedBySession.isEmpty()) {
          return@launch
        }

        val excludedPaths = runCatching {
          preferencesRepository?.userExcludedPaths?.first().orEmpty()
            .mapNotNull { raw -> runCatching { Paths.get(raw) }.getOrNull() }
            .toSet()
        }.getOrDefault(emptySet())

        var totalMovedCount = 0
        var totalMovedBytes = 0L
        var totalRepairedCount = 0
        var totalSkippedOrFailed = 0
        var lastErrorMessage: String? = null
        var anyFailed = false

        for ((sessionId, records) in groupedBySession) {
          if (records.isEmpty()) continue
          val result = trashController.runBatchCleanup(
            sessionId = sessionId,
            records = records,
            userExcludedPaths = excludedPaths,
          )
          if (!result.candidatesAlreadyRemovedFromRepo && result.cleanedCandidateIds.isNotEmpty()) {
            scanController.removeCleanedCandidates(sessionId, result.cleanedCandidateIds)
          }
          if (result.failed) {
            anyFailed = true
            lastErrorMessage = result.errorMessage
          } else {
            totalMovedCount += result.movedCount
            totalMovedBytes += result.movedBytes
            totalRepairedCount += result.repairedMediaStoreCount
            totalSkippedOrFailed += result.skippedOrFailedCount
          }
        }

        val feedback = if (anyFailed && totalMovedCount == 0 && totalRepairedCount == 0) {
          CategoryResultsFeedback.Message(
            lastErrorMessage ?: "Não foi possível concluir a limpeza em segundo plano.",
          )
        } else {
          CategoryResultsFeedback.CleanupDone(
            movedCount = totalMovedCount,
            movedBytes = totalMovedBytes,
            repairedMediaStoreCount = totalRepairedCount,
            skippedOrFailedCount = totalSkippedOrFailed,
          )
        }

        _uiState.update { it.copy(totalCleanupFeedback = feedback) }
      } finally {
        isSubmittingTotalCleanup.value = false
      }
    }
  }

  fun clearTotalCleanupFeedback() {
    _uiState.update { it.copy(totalCleanupFeedback = null) }
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
    private val trashController: TrashController,
    private val installedAppsRepository: InstalledAppsRepository? = null,
    private val preferencesRepository: AppPreferencesRepository? = null,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      HomeViewModel(
        permissionChecker = permissionChecker,
        scanController = scanController,
        trashController = trashController,
        installedAppsRepository = installedAppsRepository,
        preferencesRepository = preferencesRepository,
      ) as T
  }
}
