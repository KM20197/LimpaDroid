package io.github.km20197.limpadroid.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.km20197.limpadroid.domain.model.OperationHistoryRecord
import io.github.km20197.limpadroid.trash.TrashController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HistoryUiState(
  val records: List<OperationHistoryRecord> = emptyList(),
)

class HistoryViewModel(
  private val trashController: TrashController,
) : ViewModel() {

  private val _uiState = MutableStateFlow(HistoryUiState())
  val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      trashController.recentHistory.collect { list ->
        _uiState.value = HistoryUiState(records = list)
      }
    }
  }

  class Factory(
    private val trashController: TrashController,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      HistoryViewModel(trashController) as T
  }
}
