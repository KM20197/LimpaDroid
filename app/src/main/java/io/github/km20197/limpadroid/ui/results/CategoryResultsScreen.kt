package io.github.km20197.limpadroid.ui.results

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.km20197.limpadroid.R
import io.github.km20197.limpadroid.domain.format.ByteFormatter
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateRuleId
import io.github.km20197.limpadroid.domain.scan.rules.OrphanRuleId
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppRuleId
import io.github.km20197.limpadroid.ui.theme.MonoPathTextStyle
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryResultsScreen(
  viewModel: CategoryResultsViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }

  val doneTrashAndMediaFormat = stringResource(R.string.results_feedback_cleanup_done)
  val doneMediaOnlyFormat = stringResource(R.string.results_feedback_mediastore_only)
  val skippedSuffixFormat = stringResource(R.string.results_feedback_skipped_suffix)

  LaunchedEffect(uiState.feedback) {
    val msg = when (val fb = uiState.feedback) {
      is CategoryResultsFeedback.CleanupDone -> {
        val base = if (fb.movedCount > 0) {
          String.format(
            Locale.getDefault(),
            doneTrashAndMediaFormat,
            fb.movedCount,
            ByteFormatter.format(fb.movedBytes),
          )
        } else {
          String.format(
            Locale.getDefault(),
            doneMediaOnlyFormat,
            fb.repairedMediaStoreCount,
          )
        }
        val mediaNote = if (fb.movedCount > 0 && fb.repairedMediaStoreCount > 0) {
          " " + String.format(Locale.getDefault(), doneMediaOnlyFormat, fb.repairedMediaStoreCount)
        } else {
          ""
        }
        val skippedNote = if (fb.skippedOrFailedCount > 0) {
          " " + String.format(Locale.getDefault(), skippedSuffixFormat, fb.skippedOrFailedCount)
        } else {
          ""
        }
        base + mediaNote + skippedNote
      }
      is CategoryResultsFeedback.Message -> fb.text
      null -> null
    }
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      viewModel.clearFeedback()
    }
  }

  if (uiState.isConfirmationVisible) {
    CleanupConfirmationDialog(
      category = uiState.category,
      selectedTrashCount = uiState.selectedTrashCount,
      selectedTrashBytes = uiState.selectedTrashBytes,
      selectedMediaStoreCount = uiState.selectedMediaStoreCount,
      onConfirm = viewModel::confirmCleanup,
      onDismiss = viewModel::dismissConfirmation,
    )
  }

  val titleRes = when (uiState.category) {
    Category.SUPERFLUOUS -> R.string.cat_superfluous_title
    Category.ORPHAN -> R.string.cat_orphan_title
    Category.DUPLICATE -> R.string.cat_duplicate_title
    Category.WHATSAPP -> R.string.cat_whatsapp_title
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(titleRes)) },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier
              .size(48.dp)
              .testTag("results_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        actions = {
          TextButton(
            onClick = viewModel::startScan,
            enabled = !uiState.isScanning && !uiState.isProcessing,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("results_rescan_button"),
          ) {
            Text(stringResource(R.string.results_analyze_action))
          }
        },
      )
    },
    bottomBar = {
      if (uiState.totalCount > 0 || uiState.hasResults) {
        Surface(
          tonalElevation = 3.dp,
          shadowElevation = 4.dp,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("results_bottom_bar"),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(
                  R.string.results_selected_summary_format,
                  uiState.selectedCount,
                  ByteFormatter.format(uiState.selectedBytes),
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("results_selected_summary"),
              )
              if (uiState.selectedMediaStoreCount > 0 && uiState.selectedTrashCount == 0) {
                Text(
                  text = stringResource(R.string.results_mediastore_no_trash_hint),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Button(
              onClick = viewModel::requestCleanup,
              enabled = uiState.selectedCount > 0 && !uiState.isProcessing,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("results_cleanup_button"),
            ) {
              Text(
                stringResource(
                  if (uiState.selectedTrashCount == 0 && uiState.selectedMediaStoreCount > 0) {
                    R.string.results_repair_mediastore_button
                  } else {
                    R.string.results_move_to_trash_button
                  },
                ),
              )
            }
          }
        }
      }
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
          .testTag("results_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (uiState.isScanning) {
          item {
            ElevatedCard(
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .testTag("results_scanning_banner"),
            ) {
              Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  text = stringResource(R.string.results_scanning_in_progress),
                  style = MaterialTheme.typography.titleSmall,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
              }
            }
          }
        }

        if (uiState.category == Category.WHATSAPP) {
          item(key = "whatsapp_warning_card") {
            WhatsAppResultsNoticeCard(
              minAgeDays = uiState.whatsappMinAgeDays,
              autoPreselect = uiState.whatsappAutoPreselect,
              onToggleAutoPreselect = viewModel::setWhatsAppAutoPreselect,
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp),
            )
          }
        }

        if (!uiState.hasResults && !uiState.isScanning) {
          item {
            OutlinedCard(
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .testTag("results_empty_state"),
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.CleaningServices,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(36.dp),
                )
                Text(
                  text = stringResource(
                    if (uiState.latestSession == null) {
                      R.string.results_never_scanned_title
                    } else {
                      R.string.results_empty_title
                    },
                  ),
                  style = MaterialTheme.typography.titleMedium,
                )
                Text(
                  text = stringResource(
                    if (uiState.latestSession == null) {
                      R.string.results_never_scanned_desc
                    } else {
                      R.string.results_empty_desc
                    },
                  ),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                  onClick = viewModel::startScan,
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("results_empty_analyze_button"),
                ) {
                  Text(stringResource(R.string.results_analyze_action))
                }
              }
            }
          }
        } else if (uiState.category == Category.DUPLICATE) {
          uiState.duplicateGroups.forEach { dupGroup ->
            item(key = "dup_header_${dupGroup.groupId}") {
              DuplicateGroupHeaderCard(
                group = dupGroup,
                onToggleGroup = { viewModel.toggleDuplicateGroup(dupGroup.groupId) },
                modifier = Modifier
                  .fillMaxWidth()
                  .widthIn(max = 900.dp),
              )
            }
            items(
              items = dupGroup.items,
              key = { it.id },
            ) { record ->
              DuplicateCandidateItemRow(
                record = record,
                enabled = !uiState.isProcessing,
                onToggle = { viewModel.toggleCandidate(record) },
                onSetKept = { viewModel.swapKeptCopy(dupGroup.groupId, record.id) },
                modifier = Modifier
                  .fillMaxWidth()
                  .widthIn(max = 900.dp),
              )
            }
          }
        } else {
          uiState.groups.forEach { group ->
            item(key = "header_${group.ruleId}") {
              RuleGroupHeaderCard(
                group = group,
                onToggleGroup = { viewModel.toggleGroup(group.ruleId) },
                modifier = Modifier
                  .fillMaxWidth()
                  .widthIn(max = 900.dp),
              )
            }
            items(
              items = group.items,
              key = { it.id },
            ) { record ->
              CandidateItemRow(
                record = record,
                enabled = !uiState.isProcessing,
                onToggle = { viewModel.toggleCandidate(record) },
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DuplicateGroupHeaderCard(
  group: DuplicateCandidateGroup,
  onToggleGroup: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.testTag("results_duplicate_group_${group.groupId}"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = stringResource(
            R.string.results_duplicate_group_title_format,
            ByteFormatter.format(group.sizeBytes),
            group.items.size,
          ),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        ConfidenceChip(confidence = group.confidence)
      }

      if (!group.hasPriorityMatch) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.testTag("results_duplicate_no_priority_${group.groupId}"),
          ) {
            Text(
              text = stringResource(R.string.results_duplicate_no_priority_badge),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onTertiaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            )
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(
            R.string.results_duplicate_reclaimable_format,
            ByteFormatter.format(group.reclaimableBytes),
            group.removableItems.size,
          ),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.weight(1f),
        )
        TextButton(
          onClick = onToggleGroup,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("results_duplicate_group_toggle_${group.groupId}"),
        ) {
          Text(
            stringResource(
              if (group.allRemovableSelected) {
                R.string.results_duplicate_unselect_excess
              } else {
                R.string.results_duplicate_select_excess
              },
            ),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DuplicateCandidateItemRow(
  record: ScanCandidateRecord,
  enabled: Boolean,
  onToggle: () -> Unit,
  onSetKept: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val fileName = remember(record.candidate.path) {
    Paths.get(record.candidate.path).fileName?.toString() ?: record.candidate.path
  }
  val isKept = record.isKept

  OutlinedCard(
    modifier = modifier
      .clip(MaterialTheme.shapes.medium)
      .let { base ->
        if (!isKept) {
          base.clickable(enabled = enabled, onClick = onToggle)
        } else {
          base
        }
      }
      .testTag("results_candidate_card_${record.id}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
      ) {
        if (isKept) {
          Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
        } else {
          Checkbox(
            checked = record.candidate.preselected,
            onCheckedChange = { if (enabled) onToggle() },
            enabled = enabled,
            modifier = Modifier.testTag("results_candidate_checkbox_${record.id}"),
          )
        }
      }

      CandidateThumbnail(
        path = record.candidate.path,
        ruleId = record.candidate.ruleId,
      )

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = fileName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text = ByteFormatter.format(record.candidate.sizeBytes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Text(
          text = record.candidate.path,
          style = MonoPathTextStyle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
          itemVerticalAlignment = Alignment.CenterVertically,
        ) {
          if (isKept) {
            Surface(
              color = MaterialTheme.colorScheme.secondaryContainer,
              shape = MaterialTheme.shapes.extraSmall,
              modifier = Modifier.testTag("results_duplicate_kept_badge_${record.id}"),
            ) {
              Text(
                text = stringResource(R.string.results_duplicate_kept_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
              )
            }
          } else {
            ConfidenceChip(confidence = record.candidate.confidence)
            OutlinedButton(
              onClick = onSetKept,
              enabled = enabled,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("results_duplicate_keep_button_${record.id}"),
            ) {
              Text(stringResource(R.string.results_duplicate_keep_action))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun RuleGroupHeaderCard(
  group: CandidateRuleGroup,
  onToggleGroup: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val (titleRes, descRes) = ruleTitleAndDescRes(group.ruleId)
  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.testTag("results_group_header_${group.ruleId}"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = stringResource(titleRes),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        ConfidenceChip(confidence = group.confidence)
      }
      Text(
        text = stringResource(descRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(
            R.string.results_group_stats_format,
            group.items.size,
            ByteFormatter.format(group.totalBytes),
          ),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        TextButton(
          onClick = onToggleGroup,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("results_group_toggle_${group.ruleId}"),
        ) {
          Text(
            stringResource(
              if (group.allSelected) R.string.results_unselect_all else R.string.results_select_all,
            ),
          )
        }
      }
    }
  }
}

@Composable
private fun CandidateItemRow(
  record: ScanCandidateRecord,
  enabled: Boolean,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val fileName = remember(record.candidate.path) {
    Paths.get(record.candidate.path).fileName?.toString() ?: record.candidate.path
  }

  OutlinedCard(
    modifier = modifier
      .clip(MaterialTheme.shapes.medium)
      .clickable(enabled = enabled, onClick = onToggle)
      .testTag("results_candidate_card_${record.id}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box(
        modifier = Modifier.size(48.dp),
        contentAlignment = Alignment.Center,
      ) {
        Checkbox(
          checked = record.candidate.preselected,
          onCheckedChange = { if (enabled) onToggle() },
          enabled = enabled,
          modifier = Modifier.testTag("results_candidate_checkbox_${record.id}"),
        )
      }

      CandidateThumbnail(
        path = record.candidate.path,
        ruleId = record.candidate.ruleId,
      )

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(3.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = fileName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.size(8.dp))
          Text(
            text = ByteFormatter.format(record.candidate.sizeBytes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Text(
          text = record.candidate.path,
          style = MonoPathTextStyle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        val reason = record.reason
        if (!reason.isNullOrBlank() &&
          (record.candidate.category == Category.ORPHAN || record.candidate.category == Category.WHATSAPP)
        ) {
          Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("results_candidate_reason_${record.id}"),
          )
        }
        ConfidenceChip(confidence = record.candidate.confidence)
      }
    }
  }
}

@Composable
private fun WhatsAppResultsNoticeCard(
  minAgeDays: Int,
  autoPreselect: Boolean,
  onToggleAutoPreselect: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.tertiaryContainer,
    shape = MaterialTheme.shapes.medium,
    modifier = modifier.testTag("results_whatsapp_warning_banner"),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
      ) {
        Icon(
          imageVector = Icons.Outlined.WarningAmber,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onTertiaryContainer,
          modifier = Modifier.size(20.dp),
        )
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = stringResource(R.string.results_whatsapp_warning_banner),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
          Text(
            text = stringResource(R.string.results_whatsapp_filter_summary_format, minAgeDays),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
          )
        }
      }
      Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          val autoPreselectCd = stringResource(R.string.results_whatsapp_auto_preselect_title)
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              text = autoPreselectCd,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
              text = stringResource(R.string.results_whatsapp_auto_preselect_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = autoPreselect,
            onCheckedChange = onToggleAutoPreselect,
            modifier = Modifier
              .semantics { contentDescription = autoPreselectCd }
              .testTag("results_whatsapp_auto_preselect_switch"),
          )
        }
      }
    }
  }
}

@Composable
private fun CandidateThumbnail(
  path: String,
  ruleId: String,
) {
  val ext = remember(path) { path.substringAfterLast('.', "").lowercase(Locale.ROOT) }
  val isImage = ext in IMAGE_EXTENSIONS
  val isVideo = ext in VIDEO_EXTENSIONS
  val isAudio = ext in AUDIO_EXTENSIONS

  val bitmap by produceState<Bitmap?>(initialValue = null, path, isImage, isVideo) {
    value = if (!isImage && !isVideo) {
      null
    } else {
      withContext(Dispatchers.IO) {
        runCatching {
          val file = File(path)
          if (!file.isFile) return@runCatching null
          val size = Size(128, 128)
          if (isVideo) {
            ThumbnailUtils.createVideoThumbnail(file, size, null)
          } else {
            ThumbnailUtils.createImageThumbnail(file, size, null)
          }
        }.getOrNull()
      }
    }
  }

  Box(
    modifier = Modifier
      .size(48.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    val loaded = bitmap
    if (loaded != null) {
      Image(
        bitmap = loaded.asImageBitmap(),
        contentDescription = stringResource(R.string.cd_candidate_preview),
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      val fallbackIcon = when {
        ruleId == SuperfluousRuleId.STALE_MEDIASTORE.ruleId -> Icons.Filled.BrokenImage
        ruleId == SuperfluousRuleId.EMPTY_DIR.ruleId ||
          Files.isDirectory(Paths.get(path), LinkOption.NOFOLLOW_LINKS) -> Icons.Filled.Folder
        isImage -> Icons.Filled.Image
        isVideo -> Icons.Filled.VideoFile
        isAudio -> Icons.Filled.AudioFile
        ruleId == SuperfluousRuleId.OLD_APK.ruleId -> Icons.Filled.Android
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
      }
      Icon(
        imageVector = fallbackIcon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(24.dp),
      )
    }
  }
}

@Composable
internal fun ConfidenceChip(confidence: Confidence) {
  val (labelRes, bgColor, textColor) = when (confidence) {
    Confidence.HIGH -> Triple(
      R.string.confidence_high,
      MaterialTheme.colorScheme.primaryContainer,
      MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Confidence.MEDIUM -> Triple(
      R.string.confidence_medium,
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer,
    )
    Confidence.LOW -> Triple(
      R.string.confidence_low,
      MaterialTheme.colorScheme.surfaceVariant,
      MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  Surface(
    color = bgColor,
    shape = MaterialTheme.shapes.extraSmall,
  ) {
    Text(
      text = stringResource(labelRes),
      style = MaterialTheme.typography.labelSmall,
      color = textColor,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
  }
}

@Composable
private fun CleanupConfirmationDialog(
  category: Category,
  selectedTrashCount: Int,
  selectedTrashBytes: Long,
  selectedMediaStoreCount: Int,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  val body = when {
    selectedTrashCount > 0 && selectedMediaStoreCount > 0 -> stringResource(
      R.string.results_confirm_mixed_body,
      selectedTrashCount,
      ByteFormatter.format(selectedTrashBytes),
      selectedMediaStoreCount,
    )
    selectedTrashCount > 0 -> stringResource(
      R.string.results_confirm_trash_body,
      selectedTrashCount,
      ByteFormatter.format(selectedTrashBytes),
    )
    else -> stringResource(
      R.string.results_confirm_mediastore_body,
      selectedMediaStoreCount,
    )
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.results_confirm_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(body)
        if (category == Category.WHATSAPP && selectedTrashCount > 0) {
          Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = stringResource(R.string.results_whatsapp_confirm_note),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onTertiaryContainer,
              modifier = Modifier.padding(10.dp),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag("results_confirm_dialog_confirm"),
      ) {
        Text(stringResource(R.string.results_confirm_action))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag("results_confirm_dialog_cancel"),
      ) {
        Text(stringResource(R.string.trash_confirm_cancel))
      }
    },
  )
}

internal fun ruleTitleAndDescRes(ruleId: String): Pair<Int, Int> = when (ruleId) {
  SuperfluousRuleId.THUMBS.ruleId -> R.string.rule_thumbs_title to R.string.rule_thumbs_desc
  SuperfluousRuleId.PARTIAL_DL.ruleId -> R.string.rule_partial_dl_title to R.string.rule_partial_dl_desc
  SuperfluousRuleId.LOST_DIR.ruleId -> R.string.rule_lost_dir_title to R.string.rule_lost_dir_desc
  SuperfluousRuleId.EMPTY_DIR.ruleId -> R.string.rule_empty_dir_title to R.string.rule_empty_dir_desc
  SuperfluousRuleId.ZERO_BYTE.ruleId -> R.string.rule_zero_byte_title to R.string.rule_zero_byte_desc
  SuperfluousRuleId.OLD_APK.ruleId -> R.string.rule_old_apk_title to R.string.rule_old_apk_desc
  SuperfluousRuleId.STALE_MEDIASTORE.ruleId ->
    R.string.rule_stale_mediastore_title to R.string.rule_stale_mediastore_desc
  OrphanRuleId.ORPHAN_MEDIA_PKG.ruleId ->
    R.string.rule_orphan_media_pkg_title to R.string.rule_orphan_media_pkg_desc
  OrphanRuleId.ORPHAN_ROOT_KNOWN.ruleId ->
    R.string.rule_orphan_root_known_title to R.string.rule_orphan_root_known_desc
  OrphanRuleId.ORPHAN_ROOT_PATTERN.ruleId ->
    R.string.rule_orphan_root_pattern_title to R.string.rule_orphan_root_pattern_desc
  DuplicateRuleId.DUPLICATE_PRIORITY.ruleId ->
    R.string.rule_duplicate_priority_title to R.string.rule_duplicate_priority_desc
  DuplicateRuleId.DUPLICATE_NO_PRIORITY.ruleId ->
    R.string.rule_duplicate_no_priority_title to R.string.rule_duplicate_no_priority_desc
  WhatsAppRuleId.WHATSAPP_IMAGES.ruleId ->
    R.string.rule_whatsapp_images_title to R.string.rule_whatsapp_images_desc
  WhatsAppRuleId.WHATSAPP_VIDEO.ruleId ->
    R.string.rule_whatsapp_video_title to R.string.rule_whatsapp_video_desc
  WhatsAppRuleId.WHATSAPP_AUDIO.ruleId ->
    R.string.rule_whatsapp_audio_title to R.string.rule_whatsapp_audio_desc
  WhatsAppRuleId.WHATSAPP_VOICE_NOTES.ruleId ->
    R.string.rule_whatsapp_voice_notes_title to R.string.rule_whatsapp_voice_notes_desc
  WhatsAppRuleId.WHATSAPP_ANIMATED_GIFS.ruleId ->
    R.string.rule_whatsapp_animated_gifs_title to R.string.rule_whatsapp_animated_gifs_desc
  else -> R.string.rule_unknown_title to R.string.rule_unknown_desc
}

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi")
private val AUDIO_EXTENSIONS = setOf("opus", "aac", "m4a", "mp3", "ogg", "amr", "wav", "3ga")
