package io.github.km20197.limpadroid.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
  primary = TealPrimaryDark,
  onPrimary = OnTealPrimaryDark,
  primaryContainer = TealPrimaryContainerDark,
  onPrimaryContainer = OnTealPrimaryContainerDark,
  secondary = JadeSecondaryDark,
  onSecondary = OnJadeSecondaryDark,
  secondaryContainer = JadeSecondaryContainerDark,
  onSecondaryContainer = OnJadeSecondaryContainerDark,
  tertiary = AmberTertiaryDark,
  onTertiary = OnAmberTertiaryDark,
  tertiaryContainer = AmberTertiaryContainerDark,
  onTertiaryContainer = OnAmberTertiaryContainerDark,
  error = ErrorDark,
  onError = OnErrorDark,
  errorContainer = ErrorContainerDark,
  onErrorContainer = OnErrorContainerDark,
  background = BackgroundDark,
  onBackground = OnBackgroundDark,
  surface = SurfaceDark,
  onSurface = OnSurfaceDark,
  surfaceVariant = SurfaceVariantDark,
  onSurfaceVariant = OnSurfaceVariantDark,
  outline = OutlineDark,
)

private val LightColorScheme = lightColorScheme(
  primary = TealPrimaryLight,
  onPrimary = OnTealPrimaryLight,
  primaryContainer = TealPrimaryContainerLight,
  onPrimaryContainer = OnTealPrimaryContainerLight,
  secondary = JadeSecondaryLight,
  onSecondary = OnJadeSecondaryLight,
  secondaryContainer = JadeSecondaryContainerLight,
  onSecondaryContainer = OnJadeSecondaryContainerLight,
  tertiary = AmberTertiaryLight,
  onTertiary = OnAmberTertiaryLight,
  tertiaryContainer = AmberTertiaryContainerLight,
  onTertiaryContainer = OnAmberTertiaryContainerLight,
  error = ErrorLight,
  onError = OnErrorLight,
  errorContainer = ErrorContainerLight,
  onErrorContainer = OnErrorContainerLight,
  background = BackgroundLight,
  onBackground = OnBackgroundLight,
  surface = SurfaceLight,
  onSurface = OnSurfaceLight,
  surfaceVariant = SurfaceVariantLight,
  onSurfaceVariant = OnSurfaceVariantLight,
  outline = OutlineLight,
)

@Composable
fun LimpaDroidTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme = when {
    dynamicColor -> {
      val context = LocalContext.current
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    darkTheme -> DarkColorScheme
    else -> LightColorScheme
  }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = LimpaDroidTypography,
    shapes = LimpaDroidShapes,
    content = content,
  )
}
