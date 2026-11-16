package io.github.km20197.limpadroid.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.km20197.limpadroid.AppContainer
import io.github.km20197.limpadroid.ui.diagnostic.DiagnosticScreen
import io.github.km20197.limpadroid.ui.diagnostic.DiagnosticViewModel
import io.github.km20197.limpadroid.ui.home.HomeScreen
import io.github.km20197.limpadroid.ui.home.HomeViewModel
import io.github.km20197.limpadroid.ui.onboarding.OnboardingScreen
import io.github.km20197.limpadroid.ui.onboarding.OnboardingViewModel

@Composable
fun LimpaDroidNavHost(
  container: AppContainer,
  navController: NavHostController = rememberNavController(),
) {
  val startDestination: Any = remember(container) {
    if (container.permissionChecker.hasAllFilesAccess()) {
      HomeRoute
    } else {
      OnboardingRoute
    }
  }

  NavHost(
    navController = navController,
    startDestination = startDestination,
  ) {
    composable<OnboardingRoute> {
      val onboardingViewModel: OnboardingViewModel = viewModel(
        factory = OnboardingViewModel.Factory(
          permissionChecker = container.permissionChecker,
          preferencesRepository = container.preferencesRepository,
        ),
      )
      OnboardingScreen(
        viewModel = onboardingViewModel,
        onContinueToHome = {
          navController.navigate(HomeRoute) {
            popUpTo(OnboardingRoute) { inclusive = true }
            launchSingleTop = true
          }
        },
      )
    }

    composable<HomeRoute> {
      val homeViewModel: HomeViewModel = viewModel(
        factory = HomeViewModel.Factory(
          permissionChecker = container.permissionChecker,
        ),
      )
      HomeScreen(
        viewModel = homeViewModel,
        onOpenDiagnostic = {
          navController.navigate(DiagnosticRoute) {
            launchSingleTop = true
          }
        },
      )
    }

    composable<DiagnosticRoute> {
      val diagnosticViewModel: DiagnosticViewModel = viewModel(
        factory = DiagnosticViewModel.Factory(
          repository = container.diagnosticRepository,
        ),
      )
      DiagnosticScreen(
        viewModel = diagnosticViewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }
  }
}
