package io.github.km20197.limpadroid.ui.scan

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.ui.home.ScanStatusBadge
import io.github.km20197.limpadroid.ui.theme.MonoPathTextStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScanStatusScreen(
  viewModel: ScanStatusViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    viewModel.refreshPermissions()
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.scan_screen_title)) },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier
              .size(48.dp)
              .testTag("scan_back_button"),
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
          .testTag("scan_status_list"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp),
          ) {
            Text(
              text = stringResource(R.string.scan_screen_scope_note),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(14.dp),
            )
          }
        }

        uiState.errorMessage?.let { errorMsg ->
          item {
            Surface(
              color = MaterialTheme.colorScheme.errorContainer,
              shape = MaterialTheme.shapes.medium,
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp),
            ) {
              Text(
                text = stringResource(R.string.home_scan_storage_error, errorMsg),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(14.dp),
              )
            }
          }
        }

        item {
          val session = uiState.session
          ElevatedCard(
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .testTag("scan_session_summary_card"),
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              if (session == null) {
                Text(
                  text = stringResource(R.string.scan_screen_empty),
                  style = MaterialTheme.typography.bodyLarge,
                )
              } else {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = stringResource(R.string.home_scan_card_title),
                    style = MaterialTheme.typography.titleMedium,
                  )
                  ScanStatusBadge(status = session.status)
                }

                if (session.status == ScanStatus.RUNNING) {
                  LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                Text(
                  text = stringResource(
                    R.string.home_scan_metrics_format,
                    session.scannedFiles,
                    session.scannedDirs,
                    ByteFormatter.format(session.scannedBytes),
                    session.errorCount,
                  ),
                  style = MaterialTheme.typography.bodyLarge,
                )

                Text(
                  text = stringResource(R.string.scan_screen_root_label, session.rootPath),
                  style = MonoPathTextStyle,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                session.lastProcessedPath?.let { lastPath ->
                  Text(
                    text = stringResource(R.string.scan_screen_last_path_label, lastPath),
                    style = MonoPathTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }

                Text(
                  text = stringResource(
                    R.string.scan_screen_checkpoint_label,
                    session.completedSubtrees.size,
                  ),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                when (session?.status) {
                  ScanStatus.RUNNING -> {
                    FilledTonalButton(
                      onClick = viewModel::pauseScan,
                      modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("scan_pause_button"),
                    ) {
                      Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                      Spacer(modifier = Modifier.size(6.dp))
                      Text(stringResource(R.string.home_scan_pause))
                    }
                    OutlinedButton(
                      onClick = viewModel::cancelScan,
                      modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("scan_cancel_button"),
                    ) {
                      Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                      Spacer(modifier = Modifier.size(6.dp))
                      Text(stringResource(R.string.home_scan_cancel))
                    }
                  }

                  ScanStatus.PAUSED -> {
                    Button(
                      onClick = viewModel::resumeScan,
                      enabled = uiState.hasAllFilesAccess,
                      modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("scan_resume_button"),
                    ) {
                      Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                      Spacer(modifier = Modifier.size(6.dp))
                      Text(stringResource(R.string.home_scan_resume))
                    }
                    OutlinedButton(
                      onClick = viewModel::cancelScan,
                      modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("scan_cancel_button"),
                    ) {
                      Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                      Spacer(modifier = Modifier.size(6.dp))
                      Text(stringResource(R.string.home_scan_cancel))
                    }
                  }

                  else -> {
                    Button(
                      onClick = viewModel::startNewScan,
                      enabled = uiState.hasAllFilesAccess,
                      modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("scan_start_button"),
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
              }
            }
          }
        }

        item {
          val reportedErrorCount = maxOf(uiState.errors.size, uiState.session?.errorCount ?: 0)
          Text(
            text = stringResource(R.string.scan_screen_errors_title, reportedErrorCount),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
              .fillMaxWidth()
              .widthIn(max = 900.dp)
              .padding(top = 4.dp),
          )
        }

        if (uiState.errors.isEmpty()) {
          item {
            OutlinedCard(
              modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .testTag("scan_errors_empty_card"),
            ) {
              Text(
                text = stringResource(R.string.scan_screen_errors_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
              )
            }
          }
        } else {
          items(
            items = uiState.errors,
            key = { "${it.timestamp}_${it.path}_${it.errorType}" },
          ) { error ->
            ScanErrorCard(
              error = error,
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
private fun ScanErrorCard(
  error: WalkError,
  modifier: Modifier = Modifier,
) {
  OutlinedCard(modifier = modifier.testTag("scan_error_item")) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = error.errorType,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
      )
      Text(
        text = error.path,
        style = MonoPathTextStyle,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = error.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
