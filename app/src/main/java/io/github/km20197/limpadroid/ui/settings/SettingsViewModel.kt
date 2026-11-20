package io.github.km20197.limpadroid.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateKeepPolicy
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaRules
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
  val enabledSuperfluousRules: Set<String> = SuperfluousRuleId.DEFAULT_ENABLED_IDS,
  val userExcludedPaths: List<String> = emptyList(),
  val duplicateMinSizeBytes: Long = DuplicateKeepPolicy.DEFAULT_MIN_SIZE_BYTES,
  val duplicatePriorityFolders: List<String> = DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS,
  val whatsappMinAgeDays: Int = WhatsAppMediaRules.DEFAULT_AGE_DAYS,
  val whatsappEnabledMediaTypes: Set<String> = WhatsAppMediaType.DEFAULT_ENABLED_TYPE_NAMES,
  val whatsappAutoPreselect: Boolean = false,
  val isAddExclusionVisible: Boolean = false,
  val newExclusionInput: String = "",
  val isAddPriorityFolderVisible: Boolean = false,
  val newPriorityFolderInput: String = "",
)

class SettingsViewModel(
  private val preferencesRepository: AppPreferencesRepository,
) : ViewModel() {

  private val _uiState = MutableStateFlow(SettingsUiState())
  val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      val generalAndDupFlow = combine(
        preferencesRepository.enabledSuperfluousRuleIds,
        preferencesRepository.userExcludedPaths,
        preferencesRepository.duplicateMinSizeBytes,
        preferencesRepository.duplicatePriorityFolders,
      ) { rules, exclusions, minSizeBytes, priorityFolders ->
        GeneralAndDuplicatePrefs(
          rules = rules,
          exclusions = exclusions.sorted(),
          minSizeBytes = minSizeBytes,
          priorityFolders = priorityFolders,
        )
      }
      val whatsappFlow = combine(
        preferencesRepository.whatsappMinAgeDays,
        preferencesRepository.whatsappEnabledMediaTypes,
        preferencesRepository.whatsappAutoPreselect,
      ) { minAgeDays, mediaTypes, autoPreselect ->
        WhatsAppPrefs(
          minAgeDays = WhatsAppMediaRules.clampAgeDays(minAgeDays),
          mediaTypes = mediaTypes,
          autoPreselect = autoPreselect,
        )
      }
      combine(generalAndDupFlow, whatsappFlow) { general, whatsapp ->
        general to whatsapp
      }.collect { (general, whatsapp) ->
        _uiState.update { current ->
          current.copy(
            enabledSuperfluousRules = general.rules,
            userExcludedPaths = general.exclusions,
            duplicateMinSizeBytes = general.minSizeBytes,
            duplicatePriorityFolders = general.priorityFolders,
            whatsappMinAgeDays = whatsapp.minAgeDays,
            whatsappEnabledMediaTypes = whatsapp.mediaTypes,
            whatsappAutoPreselect = whatsapp.autoPreselect,
          )
        }
      }
    }
  }

  fun setSuperfluousRuleEnabled(ruleId: String, enabled: Boolean) {
    viewModelScope.launch {
      preferencesRepository.setSuperfluousRuleEnabled(ruleId, enabled)
    }
  }

  fun setDuplicateMinSizeBytes(bytes: Long) {
    viewModelScope.launch {
      preferencesRepository.setDuplicateMinSizeBytes(bytes.coerceAtLeast(1L))
    }
  }

  fun movePriorityFolderUp(index: Int) {
    val list = _uiState.value.duplicatePriorityFolders.toMutableList()
    if (index <= 0 || index >= list.size) return
    val item = list.removeAt(index)
    list.add(index - 1, item)
    viewModelScope.launch {
      preferencesRepository.setDuplicatePriorityFolders(list)
    }
  }

  fun movePriorityFolderDown(index: Int) {
    val list = _uiState.value.duplicatePriorityFolders.toMutableList()
    if (index < 0 || index >= list.size - 1) return
    val item = list.removeAt(index)
    list.add(index + 1, item)
    viewModelScope.launch {
      preferencesRepository.setDuplicatePriorityFolders(list)
    }
  }

  fun removePriorityFolder(index: Int) {
    val list = _uiState.value.duplicatePriorityFolders.toMutableList()
    if (index !in list.indices) return
    list.removeAt(index)
    viewModelScope.launch {
      preferencesRepository.setDuplicatePriorityFolders(list)
    }
  }

  fun resetPriorityFolders() {
    viewModelScope.launch {
      preferencesRepository.setDuplicatePriorityFolders(DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS)
    }
  }

  fun showAddPriorityFolderDialog() {
    _uiState.update { it.copy(isAddPriorityFolderVisible = true, newPriorityFolderInput = "") }
  }

  fun dismissAddPriorityFolderDialog() {
    _uiState.update { it.copy(isAddPriorityFolderVisible = false, newPriorityFolderInput = "") }
  }

  fun updateNewPriorityFolderInput(value: String) {
    _uiState.update { it.copy(newPriorityFolderInput = value) }
  }

  fun confirmAddPriorityFolder() {
    val normalized = DuplicateKeepPolicy.normalizeFolderSegments(_uiState.value.newPriorityFolderInput)
      .joinToString("/")
    _uiState.update { it.copy(isAddPriorityFolderVisible = false, newPriorityFolderInput = "") }
    if (normalized.isEmpty()) return
    val current = _uiState.value.duplicatePriorityFolders
    if (current.any { it.equals(normalized, ignoreCase = true) }) return
    viewModelScope.launch {
      preferencesRepository.setDuplicatePriorityFolders(current + normalized)
    }
  }

  fun showAddExclusionDialog() {
    _uiState.update { it.copy(isAddExclusionVisible = true, newExclusionInput = "") }
  }

  fun dismissAddExclusionDialog() {
    _uiState.update { it.copy(isAddExclusionVisible = false, newExclusionInput = "") }
  }

  fun updateNewExclusionInput(value: String) {
    _uiState.update { it.copy(newExclusionInput = value) }
  }

  fun confirmAddExclusion() {
    val raw = _uiState.value.newExclusionInput.trim()
    _uiState.update { it.copy(isAddExclusionVisible = false, newExclusionInput = "") }
    if (raw.isEmpty()) return
    viewModelScope.launch {
      preferencesRepository.addUserExcludedPath(raw)
    }
  }

  fun removeUserExclusion(path: String) {
    viewModelScope.launch {
      preferencesRepository.removeUserExcludedPath(path)
    }
  }

  fun setWhatsAppMinAgeDays(days: Int) {
    val clamped = WhatsAppMediaRules.clampAgeDays(days)
    viewModelScope.launch {
      preferencesRepository.setWhatsAppMinAgeDays(clamped)
    }
  }

  fun setWhatsAppMediaTypeEnabled(type: WhatsAppMediaType, enabled: Boolean) {
    viewModelScope.launch {
      preferencesRepository.setWhatsAppMediaTypeEnabled(type.name, enabled)
    }
  }

  fun setWhatsAppAutoPreselect(enabled: Boolean) {
    viewModelScope.launch {
      preferencesRepository.setWhatsAppAutoPreselect(enabled)
    }
  }

  private data class GeneralAndDuplicatePrefs(
    val rules: Set<String>,
    val exclusions: List<String>,
    val minSizeBytes: Long,
    val priorityFolders: List<String>,
  )

  private data class WhatsAppPrefs(
    val minAgeDays: Int,
    val mediaTypes: Set<String>,
    val autoPreselect: Boolean,
  )

  class Factory(
    private val preferencesRepository: AppPreferencesRepository,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      SettingsViewModel(preferencesRepository) as T
  }
}
