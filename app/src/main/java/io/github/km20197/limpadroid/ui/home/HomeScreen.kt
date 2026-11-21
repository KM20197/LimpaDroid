package io.github.km20197.limpadroid.ui.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.km20197.limpadroid.R
import io.github.km20197.limpadroid.domain.format.ByteFormatter
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.CategorySummary
import io.github.km20197.limpadroid.domain.model.OverallCleanupOverview
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.ui.results.CategoryResultsFeedback
import io.github.km20197.limpadroid.ui.theme.MonoPathTextStyle
import java.util.Locale
import kotlinx.coroutines.launch

private data class CategoryOverviewItem(
  val category: Category,
  val titleRes: Int,
  val icon: ImageVector,
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
  val doneTrashAndMediaFormat = stringResource(R.string.results_feedback_cleanup_done)
  val doneMediaOnlyFormat = stringResource(R.string.results_feedback_mediastore_only)
  val skippedSuffixFormat = stringResource(R.string.results_feedback_skipped_suffix)

  LaunchedEffect(uiState.scanActionMessage) {
    if (uiState.scanActionMessage != null) {
      snackbarHostState.showSnackbar(storageErrorFormat)
      viewModel.clearScanActionMessage()
    }
  }

  LaunchedEffect(uiState.totalCleanupFeedback) {
    val msg = when (val fb = uiState.totalCleanupFeedback) {
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
      viewModel.clearTotalCleanupFeedback()
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

  if (uiState.isTotalCleanupConfirmationVisible) {
    TotalCleanupConfirmationDialog(
      overview = uiState.cleanupOverview,
      onConfirm = viewModel::confirmTotalCleanup,
      onDismiss = viewModel::dismissTotalCleanupConfirmation,
    )
  }

  val categories = remember {
    listOf(
      CategoryOverviewItem(
        category = Category.SUPERFLUOUS,
        titleRes = R.string.cat_superfluous_title,
        icon = Icons.Filled.CleaningServices,
      ),
      CategoryOverviewItem(
        category = Category.ORPHAN,
        titleRes = R.string.cat_orphan_title,
        icon = Icons.Filled.FolderOff,
      ),
      CategoryOverviewItem(
        category = Category.DUPLICATE,
        titleRes = R.string.cat_duplicate_title,
        icon = Icons.Filled.ContentCopy,
      ),
      CategoryOverviewItem(
        category = Category.WHATSAPP,
        titleRes = R.string.cat_whatsapp_title,
        icon = Icons.Filled.Forum,
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
      val useCompactStack = maxWidth < 360.dp
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

          PrimaryActionsHeroCard(
            hasAllFilesAccess = uiState.hasAllFilesAccess,
            session = uiState.latestScanSession,
            overview = uiState.cleanupOverview,
            isCleanupRunning = uiState.isCleanupRunning,
            useCompactStack = useCompactStack,
            onStartScan = viewModel::startFullScan,
            onPauseScan = viewModel::pauseActiveScan,
            onResumeScan = viewModel::resumePausedScan,
            onCancelScan = viewModel::cancelActiveOrPausedScan,
            onRequestTotalCleanup = viewModel::requestTotalCleanup,
            onOpenScanDetails = onOpenScanDetails,
          )

          fun summaryFor(cat: Category): CategorySummary = when (cat) {
            Category.SUPERFLUOUS -> uiState.superfluousSummary
            Category.ORPHAN -> uiState.orphanSummary
            Category.DUPLICATE -> uiState.duplicateSummary
            Category.WHATSAPP -> uiState.whatsappSummary
          }

          OutlinedCard(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("card_categories_summary"),
          ) {
            Column(
              modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                Text(
                  text = stringResource(R.string.home_categories_section_title),
                  style = MaterialTheme.typography.titleMedium,
                  modifier = Modifier.semantics { heading() },
                )
                Text(
                  text = stringResource(R.string.home_categories_section_hint),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Spacer(modifier = Modifier.height(6.dp))
              categories.forEachIndexed { index, item ->
                if (index > 0) {
                  HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
                CategorySummaryRow(
                  item = item,
                  summary = summaryFor(item.category),
                  onOpenResults = { onOpenCategoryResults(item.category) },
                )
              }
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
                  modifier = Modifier.semantics { heading() },
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

          Spacer(modifier = Modifier.height(4.dp))

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
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(2.dp),
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
              Text(
                text = stringResource(R.string.home_license_footer),
                style = MaterialTheme.typography.labelSmall,
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
private fun PrimaryActionsHeroCard(
  hasAllFilesAccess: Boolean,
  session: ScanSession?,
  overview: OverallCleanupOverview,
  isCleanupRunning: Boolean,
  useCompactStack: Boolean,
  onStartScan: () -> Unit,
  onPauseScan: () -> Unit,
  onResumeScan: () -> Unit,
  onCancelScan: () -> Unit,
  onRequestTotalCleanup: () -> Unit,
  onOpenScanDetails: () -> Unit,
) {
  val isScanBusy = session?.status == ScanStatus.RUNNING
  val isScanPaused = session?.status == ScanStatus.PAUSED

  ElevatedCard(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("card_scan_infrastructure"),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
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
          modifier = Modifier
            .weight(1f)
            .semantics { heading() },
        )
        if (session != null) {
          ScanStatusBadge(status = session.status)
        }
      }

      if (isScanBusy || isCleanupRunning) {
        LinearProgressIndicator(
          modifier = Modifier
            .fillMaxWidth()
            .testTag("home_scan_progress"),
        )
      }

      if (session == null) {
        Text(
          text = stringResource(R.string.home_scan_idle_desc),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
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
        if (isScanBusy || isScanPaused) {
          session.lastProcessedPath?.let { path ->
            Text(
              text = path,
              style = MonoPathTextStyle,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }

      if (isCleanupRunning) {
        Text(
          text = stringResource(R.string.home_cleanup_in_progress),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      } else if (overview.hasPreselected) {
        val baseReady = stringResource(
          R.string.home_cleanup_ready_summary_format,
          overview.preselectedCount,
          ByteFormatter.format(overview.preselectedTrashBytes),
        )
        val manualSuffix = if (overview.manualReviewCount > 0) {
          stringResource(R.string.home_cleanup_manual_suffix_format, overview.manualReviewCount)
        } else {
          ""
        }
        Text(
          text = baseReady + manualSuffix,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag("home_cleanup_ready_summary"),
        )
      } else if (overview.hasAnyCandidates) {
        Text(
          text = stringResource(
            R.string.home_cleanup_manual_only_hint_format,
            overview.totalCandidatesCount,
          ),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.testTag("home_cleanup_manual_only_hint"),
        )
      }

      if (!hasAllFilesAccess) {
        Text(
          text = stringResource(R.string.home_scan_perm_required),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }

      if (isScanBusy || isScanPaused) {
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (isScanBusy) {
            FilledTonalButton(
              onClick = onPauseScan,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("home_pause_scan_button"),
            ) {
              Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text(stringResource(R.string.home_scan_pause))
            }
          } else {
            Button(
              onClick = onResumeScan,
              enabled = hasAllFilesAccess,
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("home_resume_scan_button"),
            ) {
              Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.size(6.dp))
              Text(stringResource(R.string.home_scan_resume))
            }
          }
          OutlinedButton(
            onClick = onCancelScan,
            modifier = Modifier
              .heightIn(min = 48.dp)
              .testTag("home_cancel_scan_button"),
          ) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.home_scan_cancel))
          }
        }
      } else if (useCompactStack) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(
            onClick = onStartScan,
            enabled = hasAllFilesAccess && !isCleanupRunning,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("home_start_scan_button"),
          ) {
            Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.home_btn_scan))
          }
          FilledTonalButton(
            onClick = onRequestTotalCleanup,
            enabled = hasAllFilesAccess && !isCleanupRunning && overview.hasPreselected,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("home_total_cleanup_button"),
          ) {
            Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.home_btn_total_cleanup))
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Button(
            onClick = onStartScan,
            enabled = hasAllFilesAccess && !isCleanupRunning,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("home_start_scan_button"),
          ) {
            Icon(Icons.Filled.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.home_btn_scan), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
          FilledTonalButton(
            onClick = onRequestTotalCleanup,
            enabled = hasAllFilesAccess && !isCleanupRunning && overview.hasPreselected,
            modifier = Modifier
              .weight(1f)
              .heightIn(min = 48.dp)
              .testTag("home_total_cleanup_button"),
          ) {
            Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.size(6.dp))
            Text(stringResource(R.string.home_btn_total_cleanup), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }

      if (session != null) {
        TextButton(
          onClick = onOpenScanDetails,
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

@Composable
private fun CategorySummaryRow(
  item: CategoryOverviewItem,
  summary: CategorySummary,
  onOpenResults: () -> Unit,
) {
  val catKey = item.category.name.lowercase(Locale.ROOT)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = 56.dp)
      .clickable(onClick = onOpenResults)
      .padding(horizontal = 16.dp, vertical = 10.dp)
      .testTag("home_open_${catKey}_results_button"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Box(
      modifier = Modifier
        .size(36.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = item.icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(18.dp),
      )
    }
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = stringResource(item.titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (summary.latestSessionId != null) {
        Text(
          text = stringResource(
            R.string.home_category_metrics_format,
            summary.candidateCount,
            ByteFormatter.format(summary.totalBytes),
          ),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag("home_${catKey}_metrics"),
        )
      } else {
        Text(
          text = stringResource(R.string.home_category_unscanned),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Icon(
      imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
      contentDescription = stringResource(R.string.home_view_results_button),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun TotalCleanupConfirmationDialog(
  overview: OverallCleanupOverview,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  val bodyText = when {
    overview.preselectedTrashCount > 0 && overview.preselectedMediaStoreCount > 0 ->
      stringResource(
        R.string.results_confirm_mixed_body,
        overview.preselectedTrashCount,
        ByteFormatter.format(overview.preselectedTrashBytes),
        overview.preselectedMediaStoreCount,
      )
    overview.preselectedTrashCount > 0 ->
      stringResource(
        R.string.results_confirm_trash_body,
        overview.preselectedTrashCount,
        ByteFormatter.format(overview.preselectedTrashBytes),
      )
    else ->
      stringResource(
        R.string.results_confirm_mediastore_body,
        overview.preselectedMediaStoreCount,
      )
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("home_total_cleanup_confirm_dialog"),
    title = { Text(stringResource(R.string.home_total_cleanup_confirm_title)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = bodyText, style = MaterialTheme.typography.bodyMedium)
        if (overview.manualReviewCount > 0) {
          Text(
            text = stringResource(
              R.string.home_cleanup_manual_only_hint_format,
              overview.manualReviewCount,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      TextButton(
        onClick = onConfirm,
        modifier = Modifier
          .heightIn(min = 48.dp)
          .testTag("home_total_cleanup_confirm_button"),
      ) {
        Text(stringResource(R.string.home_total_cleanup_confirm_action))
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
