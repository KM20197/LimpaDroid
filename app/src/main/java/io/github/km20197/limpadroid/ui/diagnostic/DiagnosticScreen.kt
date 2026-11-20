package io.github.km20197.limpadroid.ui.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.km20197.limpadroid.R
import io.github.km20197.limpadroid.domain.format.ByteFormatter
import io.github.km20197.limpadroid.domain.model.DiagnosticEntry
import io.github.km20197.limpadroid.domain.model.DiagnosticReport
import io.github.km20197.limpadroid.domain.model.WhatsAppRootStatus
import io.github.km20197.limpadroid.ui.theme.MonoPathTextStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(
  viewModel: DiagnosticViewModel,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val copiedTextMessage = stringResource(R.string.diagnostic_copied)

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.diagnostic_title)) },
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier
              .size(48.dp)
              .testTag("diagnostic_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = stringResource(R.string.cd_back),
            )
          }
        },
        actions = {
          val successState = uiState as? DiagnosticUiState.Success
          if (successState != null) {
            FilledTonalButton(
              onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                  ClipData.newPlainText("LimpaDroid Diagnóstico", successState.formattedText),
                )
                scope.launch {
                  snackbarHostState.showSnackbar(copiedTextMessage)
                }
              },
              modifier = Modifier
                .padding(end = 4.dp)
                .heightIn(min = 48.dp)
                .testTag("diagnostic_copy_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.padding(end = 6.dp),
              )
              Text(stringResource(R.string.diagnostic_copy_button))
            }
          }
          IconButton(
            onClick = { viewModel.refresh() },
            modifier = Modifier
              .size(48.dp)
              .testTag("diagnostic_refresh_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Refresh,
              contentDescription = stringResource(R.string.cd_refresh),
            )
          }
        },
      )
    },
  ) { innerPadding ->
    when (val state = uiState) {
      DiagnosticUiState.Loading -> {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator()
            Text(
              text = stringResource(R.string.diagnostic_loading),
              style = MaterialTheme.typography.bodyMedium,
            )
          }
        }
      }

      is DiagnosticUiState.Error -> {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = stringResource(R.string.diagnostic_error_generic, state.message),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.error,
            )
            Button(
              onClick = { viewModel.refresh() },
              modifier = Modifier.heightIn(min = 48.dp),
            ) {
              Text(stringResource(R.string.action_retry))
            }
          }
        }
      }

      is DiagnosticUiState.Success -> {
        DiagnosticContentList(
          report = state.report,
          modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        )
      }
    }
  }
}

@Composable
private fun DiagnosticContentList(
  report: DiagnosticReport,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier,
    contentAlignment = Alignment.TopCenter,
  ) {
    LazyColumn(
      modifier = Modifier
        .widthIn(max = 900.dp)
        .fillMaxSize()
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item {
        Spacer(modifier = Modifier.height(4.dp))
        DiagnosticSummaryCard(report = report)
      }

      item {
        SectionHeader(title = stringResource(R.string.diagnostic_whatsapp_title))
      }
      items(report.whatsappKnownRoots, key = { it.path }) { waRoot ->
        WhatsAppRootCard(rootStatus = waRoot)
      }

      item {
        SectionHeader(title = stringResource(R.string.diagnostic_android_media_title))
      }
      if (report.androidMediaEntries.isEmpty()) {
        item { EmptySectionNote() }
      } else {
        items(report.androidMediaEntries, key = { "media:${it.path}" }) { entry ->
          DiagnosticEntryCard(entry = entry)
        }
      }

      item {
        SectionHeader(title = stringResource(R.string.diagnostic_root_title))
      }
      if (report.rootEntries.isEmpty()) {
        item { EmptySectionNote() }
      } else {
        items(report.rootEntries, key = { "root:${it.path}" }) { entry ->
          DiagnosticEntryCard(entry = entry)
        }
      }

      item { Spacer(modifier = Modifier.height(24.dp)) }
    }
  }
}

@Composable
private fun DiagnosticSummaryCard(report: DiagnosticReport) {
  ElevatedCard(
    modifier = Modifier
      .fillMaxWidth()
      .testTag("diagnostic_summary_card"),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = stringResource(R.string.diagnostic_summary_title),
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        text = stringResource(
          if (report.hasAllFilesAccess) {
            R.string.diagnostic_perm_granted
          } else {
            R.string.diagnostic_perm_denied
          },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (report.hasAllFilesAccess) {
          MaterialTheme.colorScheme.secondary
        } else {
          MaterialTheme.colorScheme.error
        },
      )
      Text(
        text = stringResource(
          if (report.storageAvailable) {
            R.string.diagnostic_storage_available
          } else {
            R.string.diagnostic_storage_unavailable
          },
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = if (report.storageAvailable) {
          MaterialTheme.colorScheme.secondary
        } else {
          MaterialTheme.colorScheme.error
        },
      )
      Text(
        text = stringResource(
          if (report.androidMediaAccessible) {
            R.string.diagnostic_media_accessible
          } else {
            R.string.diagnostic_media_inaccessible
          },
        ),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = stringResource(R.string.diagnostic_scope_note),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = stringResource(R.string.diagnostic_sdk_app_format, report.sdkInt, report.appVersion),
        style = MonoPathTextStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = report.storageRoot,
        style = MonoPathTextStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun WhatsAppRootCard(rootStatus: WhatsAppRootStatus) {
  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = rootStatus.label,
          style = MaterialTheme.typography.labelLarge,
        )
        Text(
          text = stringResource(
            if (rootStatus.exists) R.string.diagnostic_exists_yes else R.string.diagnostic_exists_no,
          ),
          style = MaterialTheme.typography.labelMedium,
          color = if (rootStatus.exists) {
            MaterialTheme.colorScheme.secondary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
      }
      Text(
        text = rootStatus.path,
        style = MonoPathTextStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (rootStatus.subFolders.isNotEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        rootStatus.subFolders.forEach { folder ->
          Text(
            text = "• $folder",
            style = MonoPathTextStyle,
          )
        }
        if (rootStatus.truncated) {
          Text(
            text = stringResource(R.string.diagnostic_truncated_notice),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun DiagnosticEntryCard(entry: DiagnosticEntry) {
  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = if (entry.isDirectory) {
            Icons.Filled.Folder
          } else {
            Icons.AutoMirrored.Filled.InsertDriveFile
          },
          contentDescription = null,
          tint = if (entry.isDirectory) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
        )
        Text(
          text = entry.name,
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.weight(1f),
        )
        if (!entry.isDirectory && !entry.isTruncationNotice) {
          Text(
            text = ByteFormatter.format(entry.sizeBytes),
            style = MonoPathTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (entry.error != null) {
        Text(
          text = stringResource(R.string.diagnostic_entry_error_format, entry.error),
          style = MonoPathTextStyle,
          color = MaterialTheme.colorScheme.error,
        )
      }
      if (entry.children.isNotEmpty()) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        entry.children.forEach { child ->
          if (child.isTruncationNotice) {
            Text(
              text = "  ${child.name}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            val suffix = if (child.isDirectory) "/" else " (${ByteFormatter.format(child.sizeBytes)})"
            val err = child.error?.let { " [erro: $it]" }.orEmpty()
            Text(
              text = "  ${child.name}$suffix$err",
              style = MonoPathTextStyle,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SectionHeader(title: String) {
  Text(
    text = title,
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier
      .padding(top = 6.dp)
      .semantics { heading() },
  )
}

@Composable
private fun EmptySectionNote() {
  Text(
    text = stringResource(R.string.diagnostic_empty_dir),
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}
