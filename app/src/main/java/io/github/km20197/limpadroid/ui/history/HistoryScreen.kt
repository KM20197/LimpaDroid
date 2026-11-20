package io.github.km20197.limpadroid.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.km20197.limpadroid.R
import io.github.km20197.limpadroid.domain.format.ByteFormatter
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.OperationHistoryRecord
import io.github.km20197.limpadroid.domain.model.OperationType
import io.github.km20197.limpadroid.ui.theme.MonoPathTextStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
  viewModel: HistoryViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.history_screen_title)) },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier
              .size(48.dp)
              .testTag("history_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
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
          .testTag("history_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        if (uiState.records.isEmpty()) {
          item {
            OutlinedCard(
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .testTag("history_empty_state"),
            ) {
              Text(
                text = stringResource(R.string.history_empty_state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
              )
            }
          }
        } else {
          items(
            items = uiState.records,
            key = { "${it.id}_${it.timestamp}" },
          ) { record ->
            HistoryRecordCard(
              record = record,
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

@Composable
private fun HistoryRecordCard(
  record: OperationHistoryRecord,
  modifier: Modifier = Modifier,
) {
  val titleRes = when (record.operationType) {
    OperationType.MOVE_TO_TRASH -> R.string.history_op_move
    OperationType.RESTORE -> R.string.history_op_restore
    OperationType.PURGE_EXPIRED -> R.string.history_op_purge_expired
    OperationType.PURGE_MANUAL -> R.string.history_op_purge_manual
    OperationType.EMPTY_TRASH -> R.string.history_op_empty_trash
    OperationType.MEDIASTORE_REPAIR -> R.string.history_op_mediastore_repair
  }
  val categoryLabel = when (record.category) {
    Category.SUPERFLUOUS -> stringResource(R.string.cat_superfluous_title)
    Category.ORPHAN -> stringResource(R.string.cat_orphan_title)
    Category.DUPLICATE -> stringResource(R.string.cat_duplicate_title)
    Category.WHATSAPP -> stringResource(R.string.cat_whatsapp_title)
    null -> stringResource(R.string.history_category_general)
  }
  val formattedDate = remember(record.timestamp) {
    HISTORY_DATE_FORMATTER.format(Instant.ofEpochMilli(record.timestamp))
  }

  OutlinedCard(
    modifier = modifier.testTag("history_record_card"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(titleRes),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = ByteFormatter.format(record.totalBytes),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Text(
        text = stringResource(
          R.string.history_item_meta_format,
          record.itemCount,
          formattedDate,
          categoryLabel,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      record.detail?.let { detail ->
        Text(
          text = detail,
          style = MonoPathTextStyle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

private val HISTORY_DATE_FORMATTER: DateTimeFormatter =
  DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault())
