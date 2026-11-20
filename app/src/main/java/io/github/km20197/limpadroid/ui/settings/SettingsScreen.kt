package io.github.km20197.limpadroid.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.km20197.limpadroid.R
import io.github.km20197.limpadroid.domain.format.ByteFormatter
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaRules
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaType
import io.github.km20197.limpadroid.ui.results.ConfidenceChip
import io.github.km20197.limpadroid.ui.results.ruleTitleAndDescRes
import io.github.km20197.limpadroid.ui.theme.MonoPathTextStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
  viewModel: SettingsViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  if (uiState.isAddExclusionVisible) {
    AddPathDialog(
      title = stringResource(R.string.settings_add_exclusion_title),
      description = stringResource(R.string.settings_add_exclusion_desc),
      label = stringResource(R.string.settings_add_exclusion_hint),
      value = uiState.newExclusionInput,
      inputTestTag = "settings_exclusion_input",
      confirmTestTag = "settings_exclusion_confirm",
      onValueChange = viewModel::updateNewExclusionInput,
      onConfirm = viewModel::confirmAddExclusion,
      onDismiss = viewModel::dismissAddExclusionDialog,
    )
  }

  if (uiState.isAddPriorityFolderVisible) {
    AddPathDialog(
      title = stringResource(R.string.settings_duplicate_add_folder_title),
      description = stringResource(R.string.settings_duplicate_add_folder_desc),
      label = stringResource(R.string.settings_duplicate_add_folder_hint),
      value = uiState.newPriorityFolderInput,
      inputTestTag = "settings_priority_input",
      confirmTestTag = "settings_priority_confirm",
      onValueChange = viewModel::updateNewPriorityFolderInput,
      onConfirm = viewModel::confirmAddPriorityFolder,
      onDismiss = viewModel::dismissAddPriorityFolderDialog,
    )
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.settings_title)) },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier
              .size(48.dp)
              .testTag("settings_back_button"),
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
          .testTag("settings_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Text(
            text = stringResource(R.string.settings_superfluous_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .semantics { heading() },
          )
        }

        items(
          items = SuperfluousRuleId.entries,
          key = { it.ruleId },
        ) { rule ->
          val enabled = rule.ruleId in uiState.enabledSuperfluousRules
          val (titleRes, descRes) = ruleTitleAndDescRes(rule.ruleId)
          val switchLabel = stringResource(titleRes)
          ElevatedCard(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .testTag("settings_rule_card_${rule.ruleId}"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = switchLabel,
                    style = MaterialTheme.typography.titleSmall,
                  )
                  ConfidenceChip(confidence = rule.confidence)
                }
                Text(
                  text = stringResource(descRes),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                  viewModel.setSuperfluousRuleEnabled(rule.ruleId, checked)
                },
                modifier = Modifier
                  .semantics { contentDescription = switchLabel }
                  .testTag("settings_rule_switch_${rule.ruleId}"),
              )
            }
          }
        }

        item {
          Text(
            text = stringResource(R.string.settings_duplicate_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .padding(top = 8.dp)
              .semantics { heading() },
          )
        }

        item {
          ElevatedCard(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .testTag("settings_duplicate_min_size_card"),
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = stringResource(R.string.settings_duplicate_min_size_title),
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = stringResource(R.string.settings_duplicate_min_size_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                DUPLICATE_MIN_SIZE_PRESETS.forEach { presetBytes ->
                  val selected = uiState.duplicateMinSizeBytes == presetBytes
                  FilterChip(
                    selected = selected,
                    onClick = { viewModel.setDuplicateMinSizeBytes(presetBytes) },
                    label = { Text(ByteFormatter.format(presetBytes)) },
                    modifier = Modifier
                      .heightIn(min = 48.dp)
                      .testTag("settings_duplicate_min_size_$presetBytes"),
                  )
                }
              }
            }
          }
        }

        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = stringResource(R.string.settings_duplicate_priority_title),
              style = MaterialTheme.typography.titleSmall,
            )
            Text(
              text = stringResource(R.string.settings_duplicate_priority_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              OutlinedButton(
                onClick = viewModel::showAddPriorityFolderDialog,
                modifier = Modifier
                  .heightIn(min = 48.dp)
                  .testTag("settings_add_priority_folder_button"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Add,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Text(
                  text = stringResource(R.string.settings_add_exclusion),
                  modifier = Modifier.padding(start = 6.dp),
                )
              }
              TextButton(
                onClick = viewModel::resetPriorityFolders,
                modifier = Modifier
                  .heightIn(min = 48.dp)
                  .testTag("settings_reset_priority_folders_button"),
              ) {
                Text(stringResource(R.string.settings_duplicate_reset_folders))
              }
            }
          }
        }

        if (uiState.duplicatePriorityFolders.isEmpty()) {
          item {
            OutlinedCard(
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .testTag("settings_priority_folders_empty"),
            ) {
              Text(
                text = stringResource(R.string.settings_duplicate_priority_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
              )
            }
          }
        } else {
          itemsIndexed(
            items = uiState.duplicatePriorityFolders,
            key = { _, folder -> "prio_${folder.lowercase()}" },
          ) { index, folder ->
            OutlinedCard(
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .testTag("settings_priority_folder_$index"),
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  text = "${index + 1}.",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                )
                Text(
                  text = folder,
                  style = MonoPathTextStyle,
                  modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                )
                IconButton(
                  onClick = { viewModel.movePriorityFolderUp(index) },
                  enabled = index > 0,
                  modifier = Modifier
                    .size(48.dp)
                    .testTag("settings_priority_up_$index"),
                ) {
                  Icon(
                    imageVector = Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.settings_duplicate_move_up_cd),
                  )
                }
                IconButton(
                  onClick = { viewModel.movePriorityFolderDown(index) },
                  enabled = index < uiState.duplicatePriorityFolders.lastIndex,
                  modifier = Modifier
                    .size(48.dp)
                    .testTag("settings_priority_down_$index"),
                ) {
                  Icon(
                    imageVector = Icons.Filled.ArrowDownward,
                    contentDescription = stringResource(R.string.settings_duplicate_move_down_cd),
                  )
                }
                IconButton(
                  onClick = { viewModel.removePriorityFolder(index) },
                  modifier = Modifier
                    .size(48.dp)
                    .testTag("settings_priority_remove_$index"),
                ) {
                  Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.settings_duplicate_remove_folder_cd),
                  )
                }
              }
            }
          }
        }

        item {
          Text(
            text = stringResource(R.string.settings_whatsapp_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .padding(top = 8.dp)
              .semantics { heading() },
          )
        }

        item {
          val sliderCd = stringResource(R.string.settings_whatsapp_age_slider_cd)
          ElevatedCard(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .testTag("settings_whatsapp_age_card"),
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = stringResource(
                  R.string.settings_whatsapp_min_age_title_format,
                  uiState.whatsappMinAgeDays,
                ),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("settings_whatsapp_age_label"),
              )
              Text(
                text = stringResource(R.string.settings_whatsapp_min_age_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Slider(
                value = uiState.whatsappMinAgeDays.toFloat(),
                onValueChange = { raw ->
                  viewModel.setWhatsAppMinAgeDays(raw.roundToInt())
                },
                valueRange = WhatsAppMediaRules.MIN_AGE_DAYS.toFloat()..
                  WhatsAppMediaRules.MAX_AGE_DAYS.toFloat(),
                modifier = Modifier
                  .fillMaxWidth()
                  .semantics { contentDescription = sliderCd }
                  .testTag("settings_whatsapp_age_slider"),
              )
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                WHATSAPP_AGE_PRESETS.forEach { presetDays ->
                  val selected = uiState.whatsappMinAgeDays == presetDays
                  FilterChip(
                    selected = selected,
                    onClick = { viewModel.setWhatsAppMinAgeDays(presetDays) },
                    label = {
                      Text(stringResource(R.string.settings_whatsapp_age_chip_format, presetDays))
                    },
                    modifier = Modifier
                      .heightIn(min = 48.dp)
                      .testTag("settings_whatsapp_age_preset_$presetDays"),
                  )
                }
              }
            }
          }
        }

        item {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            Text(
              text = stringResource(R.string.settings_whatsapp_types_title),
              style = MaterialTheme.typography.titleSmall,
            )
            Text(
              text = stringResource(R.string.settings_whatsapp_types_desc),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        items(
          items = WhatsAppMediaType.entries,
          key = { "wa_type_${it.name}" },
        ) { mediaType ->
          val enabled = mediaType.name in uiState.whatsappEnabledMediaTypes
          val (labelRes, descRes) = whatsappMediaTypeLabelAndDesc(mediaType)
          val mediaSwitchLabel = stringResource(labelRes)
          ElevatedCard(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .testTag("settings_whatsapp_type_card_${mediaType.name}"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                Text(
                  text = mediaSwitchLabel,
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = stringResource(descRes),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = enabled,
                onCheckedChange = { checked ->
                  viewModel.setWhatsAppMediaTypeEnabled(mediaType, checked)
                },
                modifier = Modifier
                  .semantics { contentDescription = mediaSwitchLabel }
                  .testTag("settings_whatsapp_type_switch_${mediaType.name}"),
              )
            }
          }
        }

        item {
          val autoPreselectLabel = stringResource(R.string.settings_whatsapp_auto_preselect_title)
          ElevatedCard(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .testTag("settings_whatsapp_auto_preselect_card"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                Text(
                  text = autoPreselectLabel,
                  style = MaterialTheme.typography.titleSmall,
                )
                Text(
                  text = stringResource(R.string.settings_whatsapp_auto_preselect_desc),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = uiState.whatsappAutoPreselect,
                onCheckedChange = viewModel::setWhatsAppAutoPreselect,
                modifier = Modifier
                  .semantics { contentDescription = autoPreselectLabel }
                  .testTag("settings_whatsapp_auto_preselect_switch"),
              )
            }
          }
        }

        item {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = stringResource(R.string.settings_exclusions_section),
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.semantics { heading() },
            )
            OutlinedButton(
              onClick = viewModel::showAddExclusionDialog,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("settings_add_exclusion_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Text(
                text = stringResource(R.string.settings_add_exclusion),
                modifier = Modifier.padding(start = 6.dp),
              )
            }
          }
        }

        if (uiState.userExcludedPaths.isEmpty()) {
          item {
            OutlinedCard(
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .testTag("settings_exclusions_empty"),
            ) {
              Text(
                text = stringResource(R.string.settings_exclusions_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
              )
            }
          }
        } else {
          items(
            items = uiState.userExcludedPaths,
            key = { it },
          ) { excludedPath ->
            OutlinedCard(
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp),
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  text = excludedPath,
                  style = MonoPathTextStyle,
                  modifier = Modifier.weight(1f),
                )
                IconButton(
                  onClick = { viewModel.removeUserExclusion(excludedPath) },
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(
                    imageVector = Icons.Filled.DeleteOutline,
                    contentDescription = stringResource(R.string.settings_remove_exclusion_cd),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AddPathDialog(
  title: String,
  description: String,
  label: String,
  value: String,
  inputTestTag: String,
  confirmTestTag: String,
  onValueChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
          value = value,
          onValueChange = onValueChange,
          label = { Text(label) },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag(inputTestTag),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag(confirmTestTag),
      ) {
        Text(stringResource(R.string.settings_exclusion_save))
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text(stringResource(R.string.trash_confirm_cancel))
      }
    },
  )
}

private fun whatsappMediaTypeLabelAndDesc(type: WhatsAppMediaType): Pair<Int, Int> =
  when (type) {
    WhatsAppMediaType.IMAGES ->
      R.string.settings_whatsapp_type_images_label to R.string.settings_whatsapp_type_images_desc
    WhatsAppMediaType.VIDEO ->
      R.string.settings_whatsapp_type_video_label to R.string.settings_whatsapp_type_video_desc
    WhatsAppMediaType.AUDIO ->
      R.string.settings_whatsapp_type_audio_label to R.string.settings_whatsapp_type_audio_desc
    WhatsAppMediaType.VOICE_NOTES ->
      R.string.settings_whatsapp_type_voice_notes_label to R.string.settings_whatsapp_type_voice_notes_desc
    WhatsAppMediaType.ANIMATED_GIFS ->
      R.string.settings_whatsapp_type_animated_gifs_label to R.string.settings_whatsapp_type_animated_gifs_desc
  }

private val DUPLICATE_MIN_SIZE_PRESETS = listOf(
  4L * 1024L,
  64L * 1024L,
  512L * 1024L,
  1024L * 1024L,
)

private val WHATSAPP_AGE_PRESETS = listOf(7, 30, 90, 180, 365, 730)
