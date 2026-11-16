package io.github.km20197.limpadroid.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.km20197.limpadroid.R

@Composable
fun OnboardingScreen(
  viewModel: OnboardingViewModel,
  onContinueToHome: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

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
  ) { granted ->
    viewModel.onNotificationPermissionResult(granted)
  }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
              MaterialTheme.colorScheme.background,
            ),
          ),
        )
        .padding(innerPadding),
    ) {
      val scrollState = rememberScrollState()
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(scrollState)
          .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Column(
          modifier = Modifier.widthIn(max = 640.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Box(
              modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Outlined.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
              )
            }
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
              )
              Text(
                text = stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          // Card 1: MANAGE_EXTERNAL_STORAGE
          ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
              containerColor = MaterialTheme.colorScheme.surface,
            ),
          ) {
            Column(
              modifier = Modifier.padding(20.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Icon(
                  imageVector = if (uiState.hasAllFilesAccess) {
                    Icons.Filled.CheckCircle
                  } else {
                    Icons.Filled.FolderOpen
                  },
                  contentDescription = null,
                  tint = if (uiState.hasAllFilesAccess) {
                    MaterialTheme.colorScheme.secondary
                  } else {
                    MaterialTheme.colorScheme.primary
                  },
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = stringResource(R.string.onboarding_storage_title),
                    style = MaterialTheme.typography.titleMedium,
                  )
                  Text(
                    text = stringResource(
                      if (uiState.hasAllFilesAccess) {
                        R.string.onboarding_storage_status_granted
                      } else {
                        R.string.onboarding_storage_status_pending
                      },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (uiState.hasAllFilesAccess) {
                      MaterialTheme.colorScheme.secondary
                    } else {
                      MaterialTheme.colorScheme.tertiary
                    },
                    modifier = Modifier.testTag("storage_permission_status"),
                  )
                }
              }

              Text(
                text = stringResource(R.string.onboarding_storage_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              if (uiState.hasAllFilesAccess) {
                FilledTonalButton(
                  onClick = {
                    runCatching { allFilesLauncher.launch(viewModel.createAllFilesAccessIntent()) }
                  },
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("grant_storage_button"),
                ) {
                  Text(stringResource(R.string.onboarding_storage_granted_button))
                }
              } else {
                Button(
                  onClick = {
                    runCatching { allFilesLauncher.launch(viewModel.createAllFilesAccessIntent()) }
                  },
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("grant_storage_button"),
                ) {
                  Text(stringResource(R.string.onboarding_storage_button))
                }
              }
            }
          }

          // Card 2: POST_NOTIFICATIONS (opcional)
          ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
              containerColor = MaterialTheme.colorScheme.surface,
            ),
          ) {
            Column(
              modifier = Modifier.padding(20.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Icon(
                  imageVector = if (uiState.hasNotificationPermission) {
                    Icons.Filled.CheckCircle
                  } else {
                    Icons.Filled.Notifications
                  },
                  contentDescription = null,
                  tint = if (uiState.hasNotificationPermission) {
                    MaterialTheme.colorScheme.secondary
                  } else {
                    MaterialTheme.colorScheme.primary
                  },
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = stringResource(R.string.onboarding_notifications_title),
                    style = MaterialTheme.typography.titleMedium,
                  )
                  if (uiState.hasNotificationPermission) {
                    Text(
                      text = stringResource(R.string.onboarding_notifications_granted),
                      style = MaterialTheme.typography.labelMedium,
                      color = MaterialTheme.colorScheme.secondary,
                      modifier = Modifier.testTag("notifications_permission_status"),
                    )
                  }
                }
              }

              Text(
                text = stringResource(R.string.onboarding_notifications_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              AnimatedVisibility(visible = uiState.showNotificationWarning) {
                Surface(
                  color = MaterialTheme.colorScheme.tertiaryContainer,
                  shape = MaterialTheme.shapes.small,
                  modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notifications_optional_warning"),
                ) {
                  Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                  ) {
                    Icon(
                      imageVector = Icons.Outlined.Info,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.onTertiaryContainer,
                      modifier = Modifier.size(20.dp),
                    )
                    Text(
                      text = stringResource(R.string.onboarding_notifications_warning),
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                  }
                }
              }

              if (!uiState.hasNotificationPermission) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  OutlinedButton(
                    onClick = {
                      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                      } else {
                        viewModel.onNotificationPermissionResult(true)
                      }
                    },
                    modifier = Modifier
                      .weight(1f)
                      .heightIn(min = 48.dp)
                      .testTag("request_notifications_button"),
                  ) {
                    Text(stringResource(R.string.onboarding_notifications_allow))
                  }
                  if (!uiState.notificationPromptHandled) {
                    TextButton(
                      onClick = { viewModel.skipNotifications() },
                      modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("skip_notifications_button"),
                    ) {
                      Text(stringResource(R.string.onboarding_notifications_skip))
                    }
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          Button(
            onClick = onContinueToHome,
            enabled = uiState.canContinue,
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 52.dp)
              .testTag("onboarding_continue_button"),
          ) {
            Text(stringResource(R.string.onboarding_continue))
          }

          if (!uiState.canContinue) {
            Text(
              text = stringResource(R.string.onboarding_continue_hint),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.align(Alignment.CenterHorizontally),
            )
          }
        }
      }
    }
  }
}
