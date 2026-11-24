package io.github.km20197.limpadroid.ui.trash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.km20197.limpadroid.domain.format.ByteFormatter
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.domain.model.TrashRestoreResult
import io.github.km20197.limpadroid.domain.model.TrashStats
import io.github.km20197.limpadroid.trash.TrashController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TrashConfirmation {
  data class Restore(val entry: TrashEntry) : TrashConfirmation
  data class DeleteForever(val entry: TrashEntry) : TrashConfirmation
  data object EmptyTrash : TrashConfirmation
}

sealed interface TrashFeedback {
  data class Restored(val restoredPath: String) : TrashFeedback
  data class PurgedSingle(val formattedBytes: String) : TrashFeedback
  data class Emptied(val count: Int, val formattedBytes: String) : TrashFeedback
  data class Error(val message: String) : TrashFeedback
}

data class TrashUiState(
  val entries: List<TrashEntry> = emptyList(),
  val stats: TrashStats = TrashStats(),
  val pendingConfirmation: TrashConfirmation? = null,
  val isBusy: Boolean = false,
  val feedback: TrashFeedback? = null,
)

class TrashViewModel(
  private val trashController: TrashController,
) : ViewModel() {

  private val _uiState = MutableStateFlow(TrashUiState())
  val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      // A manutenção inicial é auxiliar: sua falha não pode derrubar a tela (a lista segue observável).
      try {
        trashController.reconcileAndPurgeOnStartup()
      } catch (ce: CancellationException) {
        throw ce
      } catch (_: Throwable) {
        // O PurgeWorker periódico repete a manutenção.
      }
    }
    viewModelScope.launch {
      trashController.inTrashEntries.collect { list ->
        _uiState.update { it.copy(entries = list) }
      }
    }
    viewModelScope.launch {
      trashController.trashStats.collect { stats ->
        _uiState.update { it.copy(stats = stats) }
      }
    }
  }

  fun requestRestore(entry: TrashEntry) {
    _uiState.update { it.copy(pendingConfirmation = TrashConfirmation.Restore(entry)) }
  }

  fun requestDeleteForever(entry: TrashEntry) {
    _uiState.update { it.copy(pendingConfirmation = TrashConfirmation.DeleteForever(entry)) }
  }

  fun requestEmptyTrash() {
    if (_uiState.value.entries.isEmpty()) return
    _uiState.update { it.copy(pendingConfirmation = TrashConfirmation.EmptyTrash) }
  }

  fun dismissConfirmation() {
    _uiState.update { it.copy(pendingConfirmation = null) }
  }

  fun clearFeedback() {
    _uiState.update { it.copy(feedback = null) }
  }

  fun confirmPendingAction() {
    val action = _uiState.value.pendingConfirmation ?: return
    _uiState.update { it.copy(pendingConfirmation = null, isBusy = true) }

    viewModelScope.launch {
      val feedback = try {
        when (action) {
          is TrashConfirmation.Restore -> {
            when (val result = trashController.restoreEntry(action.entry.id)) {
              is TrashRestoreResult.Restored -> TrashFeedback.Restored(result.restoredPath)
              is TrashRestoreResult.MissingPayload -> TrashFeedback.Error(result.reason)
              is TrashRestoreResult.Rejected -> TrashFeedback.Error(result.reason)
              is TrashRestoreResult.Failed -> TrashFeedback.Error(result.reason)
            }
          }
          is TrashConfirmation.DeleteForever -> {
            val summary = trashController.deletePermanently(action.entry.id)
            if (summary.purgedCount > 0) {
              TrashFeedback.PurgedSingle(ByteFormatter.format(summary.freedBytes))
            } else {
              TrashFeedback.Error(DELETE_FAILED_MESSAGE)
            }
          }
          TrashConfirmation.EmptyTrash -> {
            val summary = trashController.emptyTrash()
            TrashFeedback.Emptied(
              count = summary.purgedCount,
              formattedBytes = ByteFormatter.format(summary.freedBytes),
            )
          }
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        TrashFeedback.Error(t.message ?: OPERATION_FAILED_MESSAGE)
      }
      _uiState.update { it.copy(isBusy = false, feedback = feedback) }
    }
  }

  private companion object {
    const val DELETE_FAILED_MESSAGE = "Não foi possível excluir definitivamente este item. Nada foi apagado."
    const val OPERATION_FAILED_MESSAGE = "Não foi possível concluir a operação da lixeira."
  }

  class Factory(
    private val trashController: TrashController,
  ) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
      TrashViewModel(trashController) as T
  }
}
