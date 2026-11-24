package io.github.km20197.limpadroid.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.km20197.limpadroid.R
import io.github.km20197.limpadroid.domain.format.ByteFormatter
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.ui.theme.MonoPathTextStyle
import java.nio.file.Paths
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrashScreen(
  viewModel: TrashViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  val restoredTemplate = stringResource(R.string.trash_restored_feedback)
  val purgedTemplate = stringResource(R.string.trash_purged_feedback)
  val emptiedTemplate = stringResource(R.string.trash_emptied_feedback)

  LaunchedEffect(uiState.feedback) {
    val msg = when (val fb = uiState.feedback) {
      is TrashFeedback.Restored ->
        String.format(java.util.Locale.getDefault(), restoredTemplate, fb.restoredPath)
      is TrashFeedback.PurgedSingle ->
        String.format(java.util.Locale.getDefault(), purgedTemplate, fb.formattedBytes)
      is TrashFeedback.Emptied ->
        String.format(java.util.Locale.getDefault(), emptiedTemplate, fb.count, fb.formattedBytes)
      is TrashFeedback.Error -> fb.message
      null -> null
    }
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearFeedback()
    }
  }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    viewModel.refreshSystemTrash()
  }

  uiState.pendingConfirmation?.let { confirmation ->
    TrashConfirmationDialog(
      confirmation = confirmation,
      totalInTrashCount = uiState.stats.inTrashCount,
      totalInTrashBytes = uiState.stats.inTrashBytes,
      systemTrashCount = uiState.stats.systemTrashCount,
      systemTrashBytes = uiState.stats.systemTrashBytes,
      onConfirm = viewModel::confirmPendingAction,
      onDismiss = viewModel::dismissConfirmation,
    )
  }

  val hasAnyTrash = uiState.entries.isNotEmpty() || uiState.stats.systemTrashCount > 0

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.trash_screen_title)) },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier
              .size(48.dp)
              .testTag("trash_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        actions = {
          if (hasAnyTrash) {
            TextButton(
              onClick = viewModel::requestEmptyTrash,
              enabled = !uiState.isBusy,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("trash_empty_all_button"),
            ) {
              Text(stringResource(R.string.trash_empty_all_button))
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .testTag("trash_items_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          ElevatedCard(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .testTag("trash_summary_card"),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column {
                  Text(
                    text = stringResource(R.string.trash_in_trash_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text = ByteFormatter.format(uiState.stats.inTrashBytes),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("trash_in_trash_bytes"),
                  )
                }
                Column(horizontalAlignment = Alignment.End) {
                  Text(
                    text = stringResource(R.string.trash_freed_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    text = ByteFormatter.format(uiState.stats.freedBytes),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.testTag("trash_freed_bytes"),
                  )
                }
              }
              if (uiState.stats.systemTrashCount > 0) {
                Text(
                  text = stringResource(
                    R.string.trash_system_summary_format,
                    ByteFormatter.format(uiState.stats.systemTrashBytes),
                    uiState.stats.systemTrashCount,
                  ),
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.testTag("trash_system_summary"),
                )
              }
              if (uiState.stats.expiringSoonCount > 0) {
                Text(
                  text = stringResource(
                    R.string.trash_expiring_soon_format,
                    uiState.stats.expiringSoonCount,
                  ),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.error,
                )
              }
              Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = stringResource(R.string.trash_accounting_notice),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(10.dp),
                )
              }
            }
          }
        }

        if (uiState.entries.isEmpty()) {
          item {
            OutlinedCard(
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .testTag("trash_empty_state"),
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.AutoDelete,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(36.dp),
                )
                Text(
                  text = stringResource(R.string.trash_empty_state_title),
                  style = MaterialTheme.typography.titleMedium,
                )
                if (uiState.stats.systemTrashCount > 0) {
                  Text(
                    text = stringResource(
                      R.string.trash_system_only_pending_desc,
                      uiState.stats.systemTrashCount,
                      ByteFormatter.format(uiState.stats.systemTrashBytes),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  FilledTonalButton(
                    onClick = viewModel::requestEmptyTrash,
                    enabled = !uiState.isBusy,
                    modifier = Modifier
                      .heightIn(min = 48.dp)
                      .testTag("trash_empty_system_button"),
                  ) {
                    Text(stringResource(R.string.trash_empty_all_button))
                  }
                } else {
                  Text(
                    text = stringResource(R.string.trash_empty_state_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        } else {
          items(
            items = uiState.entries,
            key = { it.id },
          ) { entry ->
            TrashEntryCard(
              entry = entry,
              isBusy = uiState.isBusy,
              onRestore = { viewModel.requestRestore(entry) },
              onDeleteForever = { viewModel.requestDeleteForever(entry) },
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp),
            )
          }
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrashEntryCard(
  entry: TrashEntry,
  isBusy: Boolean,
  onRestore: () -> Unit,
  onDeleteForever: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val now = remember { System.currentTimeMillis() }
  val fileName = remember(entry.originalPath) {
    Paths.get(entry.originalPath).fileName?.toString() ?: entry.originalPath
  }
  val deletedDate = remember(entry.deletedAt) {
    DATE_FORMATTER.format(Instant.ofEpochMilli(entry.deletedAt))
  }
  val remainingDays = entry.remainingDays(now)

  OutlinedCard(
    modifier = modifier.testTag("trash_entry_card_${entry.id}"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = fileName,
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = ByteFormatter.format(entry.sizeBytes),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Text(
        text = entry.originalPath,
        style = MonoPathTextStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.trash_deleted_at_format, deletedDate),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = stringResource(R.string.trash_remaining_days_format, remainingDays),
          style = MaterialTheme.typography.labelMedium,
          color = if (remainingDays <= 3) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        FilledTonalButton(
          onClick = onRestore,
          enabled = !isBusy,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("trash_restore_button_${entry.id}"),
        ) {
          Icon(
            imageVector = Icons.Filled.RestoreFromTrash,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(stringResource(R.string.trash_restore_button))
        }
        OutlinedButton(
          onClick = onDeleteForever,
          enabled = !isBusy,
          colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
          ),
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("trash_delete_button_${entry.id}"),
        ) {
          Icon(
            imageVector = Icons.Filled.DeleteForever,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.size(6.dp))
          Text(stringResource(R.string.trash_delete_forever_button))
        }
      }
    }
  }
}

@Composable
private fun TrashConfirmationDialog(
  confirmation: TrashConfirmation,
  totalInTrashCount: Int,
  totalInTrashBytes: Long,
  systemTrashCount: Int,
  systemTrashBytes: Long,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  val (title, body, confirmLabel) = when (confirmation) {
    is TrashConfirmation.Restore -> Triple(
      stringResource(R.string.trash_confirm_restore_title),
      stringResource(R.string.trash_confirm_restore_body, confirmation.entry.originalPath),
      stringResource(R.string.trash_confirm_restore_action),
    )
    is TrashConfirmation.DeleteForever -> {
      val name = Paths.get(confirmation.entry.originalPath).fileName?.toString()
        ?: confirmation.entry.originalPath
      Triple(
        stringResource(R.string.trash_confirm_delete_title),
        stringResource(
          R.string.trash_confirm_delete_body,
          name,
          ByteFormatter.format(confirmation.entry.sizeBytes),
        ),
        stringResource(R.string.trash_confirm_delete_action),
      )
    }
    TrashConfirmation.EmptyTrash -> {
      val bodyText = when {
        totalInTrashCount > 0 && systemTrashCount > 0 -> stringResource(
          R.string.trash_confirm_empty_both_body,
          totalInTrashCount,
          ByteFormatter.format(totalInTrashBytes),
          systemTrashCount,
          ByteFormatter.format(systemTrashBytes),
        )
        totalInTrashCount == 0 && systemTrashCount > 0 -> stringResource(
          R.string.trash_confirm_empty_system_only_body,
          systemTrashCount,
          ByteFormatter.format(systemTrashBytes),
        )
        else -> stringResource(
          R.string.trash_confirm_empty_body,
          totalInTrashCount,
          ByteFormatter.format(totalInTrashBytes),
        )
      }
      Triple(
        stringResource(R.string.trash_confirm_empty_title),
        bodyText,
        stringResource(R.string.trash_confirm_empty_action),
      )
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = { Text(body) },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag("trash_confirm_dialog_confirm"),
      ) {
        Text(confirmLabel)
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag("trash_confirm_dialog_cancel"),
      ) {
        Text(stringResource(R.string.trash_confirm_cancel))
      }
    },
  )
}

private val DATE_FORMATTER: DateTimeFormatter =
  DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault())
