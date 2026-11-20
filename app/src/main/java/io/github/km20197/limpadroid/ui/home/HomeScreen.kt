package io.github.km20197.limpadroid.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.km20197.limpadroid.R
import io.github.km20197.limpadroid.domain.format.ByteFormatter
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.CategorySummary
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.ui.theme.MonoPathTextStyle
import java.util.Locale
import kotlinx.coroutines.launch

private data class CategoryOverviewItem(
  val category: Category,
  val titleRes: Int,
  val descRes: Int,
  val icon: ImageVector,
  val tag: String,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
  viewModel: HomeViewModel,
  onOpenDiagnostic: () -> Unit,
  onOpenScanDetails: () -> Unit,
  onOpenTrash: () -> Unit,
  onOpenHistory: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenCategoryResults: (Category) -> Unit = {},
  onOpenSettings: () -> Unit = {},
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val hintMessage = stringResource(R.string.home_version_hint)
  val storageErrorFormat = stringResource(R.string.home_scan_storage_error, uiState.scanActionMessage.orEmpty())

  LaunchedEffect(uiState.scanActionMessage) {
    if (uiState.scanActionMessage != null) {
      snackbarHostState.showSnackbar(storageErrorFormat)
      viewModel.clearScanActionMessage()
    }
  }

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    viewModel.refreshPermissions()
  }

  val allFilesLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
  ) {
    viewModel.refreshPermissions()
  }

  val notificationLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) {
    viewModel.refreshPermissions()
  }

  val categories = remember {
    listOf(
      CategoryOverviewItem(
        category = Category.SUPERFLUOUS,
        titleRes = R.string.cat_superfluous_title,
        descRes = R.string.cat_superfluous_desc,
        icon = Icons.Filled.CleaningServices,
        tag = "card_category_superfluous",
      ),
      CategoryOverviewItem(
        category = Category.ORPHAN,
        titleRes = R.string.cat_orphan_title,
        descRes = R.string.cat_orphan_desc,
        icon = Icons.Filled.FolderOff,
        tag = "card_category_orphan",
      ),
      CategoryOverviewItem(
        category = Category.DUPLICATE,
        titleRes = R.string.cat_duplicate_title,
        descRes = R.string.cat_duplicate_desc,
        icon = Icons.Filled.ContentCopy,
        tag = "card_category_duplicate",
      ),
      CategoryOverviewItem(
        category = Category.WHATSAPP,
        titleRes = R.string.cat_whatsapp_title,
        descRes = R.string.cat_whatsapp_desc,
        icon = Icons.Filled.Forum,
        tag = "card_category_whatsapp",
      ),
    )
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = stringResource(R.string.home_title),
              style = MaterialTheme.typography.titleLarge,
            )
            Text(
              text = stringResource(R.string.home_subtitle),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        actions = {
          IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
              .size(48.dp)
              .testTag("home_settings_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Settings,
              contentDescription = stringResource(R.string.settings_title),
            )
          }
        },
      )
    },
  ) { innerPadding ->
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
    ) {
      val useTwoColumns = maxWidth >= 600.dp
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Column(
          modifier = Modifier.widthIn(max = 900.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          PermissionStatusBanner(
            hasAllFilesAccess = uiState.hasAllFilesAccess,
            onGrantStorage = {
              runCatching { allFilesLauncher.launch(viewModel.createAllFilesAccessIntent()) }
            },
          )

          if (!uiState.hasNotificationPermission) {
            Surface(
              color = MaterialTheme.colorScheme.surfaceVariant,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("home_notifications_banner"),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Outlined.NotificationsOff,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = stringResource(R.string.home_notif_disabled_banner),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.weight(1f),
                )
                TextButton(
                  onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                      notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                      viewModel.refreshPermissions()
                    }
                  },
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("home_enable_notifications_button"),
                ) {
                  Text(stringResource(R.string.home_notif_enable))
                }
              }
            }
          }

          ScanInfrastructureCard(
            hasAllFilesAccess = uiState.hasAllFilesAccess,
            session = uiState.latestScanSession,
            onStart = viewModel::startInfrastructureScan,
            onPause = viewModel::pauseActiveScan,
            onResume = viewModel::resumePausedScan,
            onCancel = viewModel::cancelActiveOrPausedScan,
            onOpenDetails = onOpenScanDetails,
          )

          val isScanBusy = uiState.latestScanSession?.status == ScanStatus.RUNNING
          fun summaryFor(cat: Category): CategorySummary = when (cat) {
            Category.SUPERFLUOUS -> uiState.superfluousSummary
            Category.ORPHAN -> uiState.orphanSummary
            Category.DUPLICATE -> uiState.duplicateSummary
            Category.WHATSAPP -> uiState.whatsappSummary
          }
          fun isCategoryReady(cat: Category): Boolean = true

          if (useTwoColumns) {
            categories.chunked(2).forEach { pair ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                pair.forEach { item ->
                  val ready = isCategoryReady(item.category)
                  CategoryOverviewCard(
                    item = item,
                    summary = summaryFor(item.category),
                    hasAllFilesAccess = uiState.hasAllFilesAccess,
                    isScanBusy = isScanBusy,
                    onAnalyze = if (ready) {
                      { viewModel.startCategoryScan(item.category) }
                    } else {
                      null
                    },
                    onOpenResults = if (ready) {
                      { onOpenCategoryResults(item.category) }
                    } else {
                      null
                    },
                    modifier = Modifier.weight(1f),
                  )
                }
              }
            }
          } else {
            categories.forEach { item ->
              val ready = isCategoryReady(item.category)
              CategoryOverviewCard(
                item = item,
                summary = summaryFor(item.category),
                hasAllFilesAccess = uiState.hasAllFilesAccess,
                isScanBusy = isScanBusy,
                onAnalyze = if (ready) {
                  { viewModel.startCategoryScan(item.category) }
                } else {
                  null
                },
                onOpenResults = if (ready) {
                  { onOpenCategoryResults(item.category) }
                } else {
                  null
                },
                modifier = Modifier.fillMaxWidth(),
              )
            }
          }

          ElevatedCard(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("card_trash_summary"),
            colors = CardDefaults.elevatedCardColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
            ),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.AutoDelete,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                  text = stringResource(R.string.home_trash_title),
                  style = MaterialTheme.typography.titleMedium,
                )
              }
              Text(
                text = stringResource(
                  R.string.home_trash_metrics_format,
                  ByteFormatter.format(uiState.trashStats.inTrashBytes),
                  uiState.trashStats.inTrashCount,
                  uiState.trashStats.expiringSoonCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("home_trash_metrics"),
              )
              Text(
                text = stringResource(
                  R.string.home_trash_freed_format,
                  ByteFormatter.format(uiState.trashStats.freedBytes),
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.testTag("home_trash_freed"),
              )
              Text(
                text = stringResource(R.string.home_trash_warning),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                FilledTonalButton(
                  onClick = onOpenTrash,
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("home_open_trash_button"),
                ) {
                  Text(stringResource(R.string.home_open_trash))
                }
                OutlinedButton(
                  onClick = onOpenHistory,
                  modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("home_open_history_button"),
                ) {
                  Text(stringResource(R.string.home_open_history))
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(8.dp))

          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
              .fillMaxWidth()
              .clip(MaterialTheme.shapes.small)
              .combinedClickable(
                onClickLabel = hintMessage,
                onLongClickLabel = stringResource(R.string.home_version_long_click_label),
                onClick = {
                  scope.launch {
                    snackbarHostState.showSnackbar(hintMessage)
                  }
                },
                onLongClick = onOpenDiagnostic,
              )
              .heightIn(min = 48.dp)
              .semantics(mergeDescendants = true) {}
              .testTag("version_diagnostic_trigger"),
          ) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = stringResource(
                  R.string.home_version_format,
                  uiState.versionName,
                  uiState.versionCode,
                ),
                style = MonoPathTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun ScanInfrastructureCard(
  hasAllFilesAccess: Boolean,
  session: ScanSession?,
  onStart: () -> Unit,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onCancel: () -> Unit,
  onOpenDetails: () -> Unit,
) {
  ElevatedCard(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("card_scan_infrastructure"),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Radar,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
          )
        }
        Text(
          text = stringResource(R.string.home_scan_card_title),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        if (session != null) {
          ScanStatusBadge(status = session.status)
        }
      }

      if (session == null) {
        Text(
          text = stringResource(R.string.home_scan_idle_desc),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        if (session.status == ScanStatus.RUNNING) {
          LinearProgressIndicator(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("home_scan_progress"),
          )
        }
        Text(
          text = stringResource(
            R.string.home_scan_metrics_format,
            session.scannedFiles,
            session.scannedDirs,
            ByteFormatter.format(session.scannedBytes),
            session.errorCount,
          ),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier.testTag("home_scan_metrics"),
        )
        session.lastProcessedPath?.let { path ->
          Text(
            text = path,
            style = MonoPathTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
          )
        }
      }

      if (!hasAllFilesAccess) {
        Text(
          text = stringResource(R.string.home_scan_perm_required),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }

      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        when (session?.status) {
          ScanStatus.RUNNING -> {
            FilledTonalButton(
              onClick = onPause,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("home_pause_scan_button"),
            ) {
              Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text(stringResource(R.string.home_scan_pause))
            }
            OutlinedButton(
              onClick = onCancel,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("home_cancel_scan_button"),
            ) {
              Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text(stringResource(R.string.home_scan_cancel))
            }
          }

          ScanStatus.PAUSED -> {
            Button(
              onClick = onResume,
              enabled = hasAllFilesAccess,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("home_resume_scan_button"),
            ) {
              Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text(stringResource(R.string.home_scan_resume))
            }
            OutlinedButton(
              onClick = onCancel,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("home_cancel_scan_button"),
            ) {
              Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text(stringResource(R.string.home_scan_cancel))
            }
          }

          else -> {
            Button(
              onClick = onStart,
              enabled = hasAllFilesAccess,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("home_start_scan_button"),
            ) {
              Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text(
                stringResource(
                  if (session == null) R.string.home_scan_start else R.string.home_scan_new,
                ),
              )
            }
          }
        }

        if (session != null) {
          TextButton(
            onClick = onOpenDetails,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("home_scan_details_button"),
          ) {
            Text(stringResource(R.string.home_scan_details))
          }
        }
      }
    }
  }
}

@Composable
internal fun ScanStatusBadge(status: ScanStatus) {
  val (labelRes, bgColor, textColor) = when (status) {
    ScanStatus.RUNNING -> Triple(
      R.string.scan_status_running,
      MaterialTheme.colorScheme.primaryContainer,
      MaterialTheme.colorScheme.onPrimaryContainer,
    )
    ScanStatus.PAUSED -> Triple(
      R.string.scan_status_paused,
      MaterialTheme.colorScheme.tertiaryContainer,
      MaterialTheme.colorScheme.onTertiaryContainer,
    )
    ScanStatus.CANCELLED -> Triple(
      R.string.scan_status_cancelled,
      MaterialTheme.colorScheme.surfaceVariant,
      MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ScanStatus.COMPLETED -> Triple(
      R.string.scan_status_completed,
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer,
    )
    ScanStatus.FAILED -> Triple(
      R.string.scan_status_failed,
      MaterialTheme.colorScheme.errorContainer,
      MaterialTheme.colorScheme.onErrorContainer,
    )
  }

  Surface(
    color = bgColor,
    shape = MaterialTheme.shapes.small,
    modifier = Modifier.testTag("scan_status_badge"),
  ) {
    Text(
      text = stringResource(labelRes),
      style = MaterialTheme.typography.labelMedium,
      color = textColor,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}

@Composable
private fun PermissionStatusBanner(
  hasAllFilesAccess: Boolean,
  onGrantStorage: () -> Unit,
) {
  Surface(
    color = if (hasAllFilesAccess) {
      MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    } else {
      MaterialTheme.colorScheme.errorContainer
    },
    shape = MaterialTheme.shapes.medium,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("home_storage_permission_banner"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = if (hasAllFilesAccess) {
          Icons.Outlined.CheckCircle
        } else {
          Icons.Outlined.WarningAmber
        },
        contentDescription = null,
        tint = if (hasAllFilesAccess) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onErrorContainer
        },
      )
      Text(
        text = stringResource(
          if (hasAllFilesAccess) R.string.home_perm_ok else R.string.home_perm_missing,
        ),
        style = MaterialTheme.typography.labelLarge,
        color = if (hasAllFilesAccess) {
          MaterialTheme.colorScheme.onPrimaryContainer
        } else {
          MaterialTheme.colorScheme.onErrorContainer
        },
        modifier = Modifier.weight(1f),
      )
      if (!hasAllFilesAccess) {
        TextButton(
          onClick = onGrantStorage,
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("home_grant_storage_button"),
        ) {
          Text(stringResource(R.string.home_perm_grant))
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryOverviewCard(
  item: CategoryOverviewItem,
  modifier: Modifier = Modifier,
  summary: CategorySummary? = null,
  hasAllFilesAccess: Boolean = false,
  isScanBusy: Boolean = false,
  onAnalyze: (() -> Unit)? = null,
  onOpenResults: (() -> Unit)? = null,
) {
  OutlinedCard(
    modifier = modifier.testTag(item.tag),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = item.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
          )
        }
        Text(
          text = stringResource(item.titleRes),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
      }
      Text(
        text = stringResource(item.descRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      val catKey = item.category.name.lowercase(Locale.ROOT)
      if (summary != null && summary.latestSessionId != null) {
        Text(
          text = stringResource(
            R.string.home_category_metrics_format,
            summary.candidateCount,
            ByteFormatter.format(summary.totalBytes),
          ),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag("home_${catKey}_metrics"),
        )
      }
      if (onAnalyze != null || onOpenResults != null) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (onAnalyze != null) {
            FilledTonalButton(
              onClick = onAnalyze,
              enabled = hasAllFilesAccess && !isScanBusy,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("home_analyze_${catKey}_button"),
            ) {
              Text(stringResource(R.string.results_analyze_action))
            }
          }
          if (onOpenResults != null) {
            OutlinedButton(
              onClick = onOpenResults,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("home_open_${catKey}_results_button"),
            ) {
              Text(stringResource(R.string.home_view_results_button))
            }
          }
        }
      }
    }
  }
}
