package io.github.km20197.limpadroid.ui.results

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateKeepPolicy
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateRuleId
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaRules
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanStartResult
import io.github.km20197.limpadroid.trash.TrashController
import java.nio.file.Paths
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CandidateRuleGroup(
  val ruleId: String,
  val confidence: Confidence,
  val items: List<ScanCandidateRecord>,
) {
  val totalBytes: Long get() = items.sumOf { it.candidate.sizeBytes }
  val selectedCount: Int get() = items.count { it.candidate.preselected }
  val selectedBytes: Long get() = items.filter { it.candidate.preselected }.sumOf { it.candidate.sizeBytes }
  val allSelected: Boolean get() = items.isNotEmpty() && items.all { it.candidate.preselected }
}

data class DuplicateCandidateGroup(
  val groupId: String,
  val sizeBytes: Long,
  val hasPriorityMatch: Boolean,
  val confidence: Confidence,
  val items: List<ScanCandidateRecord>,
) {
  val removableItems: List<ScanCandidateRecord> get() = items.filter { !it.isKept }
  val reclaimableBytes: Long get() = removableItems.sumOf { it.candidate.sizeBytes }
  val selectedCount: Int get() = removableItems.count { it.candidate.preselected }
  val allRemovableSelected: Boolean
    get() = removableItems.isNotEmpty() && removableItems.all { it.candidate.preselected }
}

sealed interface CategoryResultsFeedback {
  data class CleanupDone(
    val movedCount: Int,
    val movedBytes: Long,
    val repairedMediaStoreCount: Int,
    val skippedOrFailedCount: Int,
  ) : CategoryResultsFeedback

  data class Message(val text: String) : CategoryResultsFeedback
}

data class CategoryResultsUiState(
  val category: Category,
  val latestSession: ScanSession? = null,
  val groups: List<CandidateRuleGroup> = emptyList(),
  val duplicateGroups: List<DuplicateCandidateGroup> = emptyList(),
  val totalCount: Int = 0,
  val totalBytes: Long = 0L,
  val selectedCount: Int = 0,
  val selectedBytes: Long = 0L,
  val selectedTrashCount: Int = 0,
  val selectedTrashBytes: Long = 0L,
  val selectedMediaStoreCount: Int = 0,
  val isConfirmationVisible: Boolean = false,
  val isProcessing: Boolean = false,
  val feedback: CategoryResultsFeedback? = null,
  val whatsappMinAgeDays: Int = WhatsAppMediaRules.DEFAULT_AGE_DAYS,
  val whatsappAutoPreselect: Boolean = false,
) {
  val isScanning: Boolean
    get() = latestSession?.status == ScanStatus.RUNNING

  val hasResults: Boolean
    get() = if (category == Category.DUPLICATE) duplicateGroups.isNotEmpty() else groups.isNotEmpty()
}

class CategoryResultsViewModel(
  private val category: Category,
  private val scanController: ScanController,
  private val trashController: TrashController,
  private val preferencesRepository: AppPreferencesRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(CategoryResultsUiState(category = category))
  val uiState: StateFlow<CategoryResultsUiState> = _uiState.asStateFlow()

  private var allRecords: List<ScanCandidateRecord> = emptyList()

  init {
    viewModelScope.launch {
      scanController.observeLatestSessionForCategory(category).collect { session ->
        _uiState.update { it.copy(latestSession = session) }
      }
    }
    if (category == Category.WHATSAPP) {
      viewModelScope.launch {
        preferencesRepository.whatsappMinAgeDays.collect { days ->
          _uiState.update { it.copy(whatsappMinAgeDays = days) }
        }
      }
      viewModelScope.launch {
        preferencesRepository.whatsappAutoPreselect.collect { autoPreselect ->
          _uiState.update { it.copy(whatsappAutoPreselect = autoPreselect) }
        }
      }
    }
    viewModelScope.launch {
      scanController.observeCategoryCandidates(category).collect { records ->
        allRecords = records

        if (category == Category.DUPLICATE) {
          val dupGroups = records
            .groupBy { it.candidate.groupId ?: it.candidate.path }
            .values
            .filter { it.size >= 2 }
            .map { rawItems ->
              val safeSelection = DuplicateKeepPolicy.enforceKeepAtLeastOneCopy(
                groupRecords = rawItems,
                desiredSelectionById = rawItems.associate { it.id to it.candidate.preselected },
              )
              val normalizedItems = rawItems
                .map { record ->
                  val safeSelected = safeSelection[record.id] ?: false
                  if (record.candidate.preselected == safeSelected) {
                    record
                  } else {
                    record.copy(candidate = record.candidate.copy(preselected = safeSelected))
                  }
                }
                .sortedWith(
                  compareByDescending<ScanCandidateRecord> { it.isKept }
                    .thenBy { it.candidate.path },
                )
              val first = normalizedItems.first()
              val hasPriority = normalizedItems.none {
                it.candidate.ruleId == DuplicateRuleId.DUPLICATE_NO_PRIORITY.ruleId
              }
              DuplicateCandidateGroup(
                groupId = first.candidate.groupId ?: first.candidate.path,
                sizeBytes = first.candidate.sizeBytes,
                hasPriorityMatch = hasPriority,
                confidence = first.candidate.confidence,
                items = normalizedItems,
              )
            }
            .sortedWith(
              compareBy<DuplicateCandidateGroup> { confidenceOrder(it.confidence) }
                .thenByDescending { it.reclaimableBytes }
                .thenBy { it.groupId },
            )

          val removableRecords = dupGroups.flatMap { it.removableItems }
          val selected = removableRecords.filter { it.candidate.preselected }

          _uiState.update { current ->
            current.copy(
              groups = emptyList(),
              duplicateGroups = dupGroups,
              totalCount = removableRecords.size,
              totalBytes = removableRecords.sumOf { it.candidate.sizeBytes },
              selectedCount = selected.size,
              selectedBytes = selected.sumOf { it.candidate.sizeBytes },
              selectedTrashCount = selected.size,
              selectedTrashBytes = selected.sumOf { it.candidate.sizeBytes },
              selectedMediaStoreCount = 0,
            )
          }
        } else {
          val groups = records
            .groupBy { it.candidate.ruleId }
            .map { (ruleId, items) ->
              CandidateRuleGroup(
                ruleId = ruleId,
                confidence = items.first().candidate.confidence,
                items = items,
              )
            }
            .sortedWith(
              compareBy<CandidateRuleGroup> { confidenceOrder(it.confidence) }
                .thenByDescending { it.totalBytes }
                .thenBy { it.ruleId },
            )

          val selected = records.filter { it.candidate.preselected }
          val selectedMediaStore = selected.filter {
            it.candidate.ruleId == SuperfluousRuleId.STALE_MEDIASTORE.ruleId
          }
          val selectedTrash = selected.filter {
            it.candidate.ruleId != SuperfluousRuleId.STALE_MEDIASTORE.ruleId
          }

          _uiState.update { current ->
            current.copy(
              groups = groups,
              duplicateGroups = emptyList(),
              totalCount = records.size,
              totalBytes = records.sumOf { it.candidate.sizeBytes },
              selectedCount = selected.size,
              selectedBytes = selected.sumOf { it.candidate.sizeBytes },
              selectedTrashCount = selectedTrash.size,
              selectedTrashBytes = selectedTrash.sumOf { it.candidate.sizeBytes },
              selectedMediaStoreCount = selectedMediaStore.size,
            )
          }
        }
      }
    }
  }

  fun startScan() {
    viewModelScope.launch {
      val scope = when (category) {
        Category.SUPERFLUOUS -> ScanScope.SUPERFLUOUS
        Category.ORPHAN -> ScanScope.ORPHAN
        Category.DUPLICATE -> ScanScope.DUPLICATE
        Category.WHATSAPP -> ScanScope.WHATSAPP
      }
      when (val res = scanController.startNewScan(scope)) {
        is ScanStartResult.Started, is ScanStartResult.AlreadyRunning -> Unit
        is ScanStartResult.StorageUnavailable -> {
          _uiState.update { it.copy(feedback = CategoryResultsFeedback.Message(res.reason)) }
        }
      }
    }
  }

  fun toggleCandidate(record: ScanCandidateRecord) {
    if (category == Category.DUPLICATE && record.isKept) return
    viewModelScope.launch {
      scanController.setCandidatePreselected(record.id, !record.candidate.preselected)
    }
  }

  fun toggleGroup(ruleId: String) {
    val group = _uiState.value.groups.firstOrNull { it.ruleId == ruleId } ?: return
    val targetSelected = !group.allSelected
    val ids = group.items.map { it.id }
    viewModelScope.launch {
      scanController.setCandidatesPreselected(ids, targetSelected)
    }
  }

  fun toggleDuplicateGroup(groupId: String) {
    val group = _uiState.value.duplicateGroups.firstOrNull { it.groupId == groupId } ?: return
    val targetSelected = !group.allRemovableSelected
    val ids = group.removableItems.map { it.id }
    if (ids.isEmpty()) return
    viewModelScope.launch {
      scanController.setCandidatesPreselected(ids, targetSelected)
    }
  }

  fun swapKeptCopy(groupId: String, newKeepCandidateId: Long) {
    val group = _uiState.value.duplicateGroups.firstOrNull { it.groupId == groupId } ?: return
    val target = group.items.firstOrNull { it.id == newKeepCandidateId } ?: return
    if (target.isKept) return
    val sessionId = _uiState.value.latestSession?.id ?: target.sessionId
    viewModelScope.launch {
      scanController.swapDuplicateKeptCandidate(
        sessionId = sessionId,
        groupId = groupId,
        newKeepCandidateId = newKeepCandidateId,
      )
    }
  }

  fun setWhatsAppAutoPreselect(enabled: Boolean) {
    viewModelScope.launch {
      preferencesRepository.setWhatsAppAutoPreselect(enabled)
    }
  }

  fun requestCleanup() {
    if (_uiState.value.selectedCount == 0 || _uiState.value.isProcessing) return
    _uiState.update { it.copy(isConfirmationVisible = true) }
  }

  fun dismissConfirmation() {
    _uiState.update { it.copy(isConfirmationVisible = false) }
  }

  fun confirmCleanup() {
    val selected = if (category == Category.DUPLICATE) {
      _uiState.value.duplicateGroups.flatMap { it.removableItems }.filter { it.candidate.preselected }
    } else {
      allRecords.filter { it.candidate.preselected }
    }
    if (selected.isEmpty()) {
      _uiState.update { it.copy(isConfirmationVisible = false) }
      return
    }
    _uiState.update { it.copy(isConfirmationVisible = false, isProcessing = true) }

    viewModelScope.launch {
      val excludedPaths = runCatching {
        preferencesRepository.userExcludedPaths.first()
          .mapNotNull { raw -> runCatching { Paths.get(raw) }.getOrNull() }
          .toSet()
      }.getOrDefault(emptySet())

      val staleRecords = selected.filter {
        it.candidate.ruleId == SuperfluousRuleId.STALE_MEDIASTORE.ruleId
      }
      val trashRecords = selected.filter {
        it.candidate.ruleId != SuperfluousRuleId.STALE_MEDIASTORE.ruleId
      }

      val trashSummary = trashController.moveCandidatesToTrash(
        records = trashRecords,
        userExcludedPaths = excludedPaths,
      )
      val repairSummary = trashController.repairStaleMediaStore(
        records = staleRecords,
        userExcludedPaths = excludedPaths,
      )

      val cleanedIds = trashSummary.movedCandidateIds + repairSummary.repairedCandidateIds
      val sessionId = _uiState.value.latestSession?.id ?: selected.firstOrNull()?.sessionId
      scanController.removeCleanedCandidates(sessionId, cleanedIds)

      val skippedOrFailed = trashSummary.staleOrSkippedCount +
        trashSummary.failedCount +
        repairSummary.rejectedOrExistingCount

      _uiState.update {
        it.copy(
          isProcessing = false,
          feedback = CategoryResultsFeedback.CleanupDone(
            movedCount = trashSummary.movedCandidateIds.size,
            movedBytes = trashSummary.movedBytes,
            repairedMediaStoreCount = repairSummary.repairedCandidateIds.size,
            skippedOrFailedCount = skippedOrFailed,
          ),
        )
      }
    }
  }

  fun clearFeedback() {
    _uiState.update { it.copy(feedback = null) }
  }

  private fun confidenceOrder(confidence: Confidence): Int = when (confidence) {
    Confidence.HIGH -> 0
    Confidence.MEDIUM -> 1
    Confidence.LOW -> 2
  }

  class Factory(
    private val category: Category,
    private val scanController: ScanController,
    private val trashController: TrashController,
    private val preferencesRepository: AppPreferencesRepository,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      CategoryResultsViewModel(
        category = category,
        scanController = scanController,
        trashController = trashController,
        preferencesRepository = preferencesRepository,
      ) as T
  }
}
