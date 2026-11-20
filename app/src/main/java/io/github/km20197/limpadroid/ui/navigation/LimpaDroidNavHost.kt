package io.github.km20197.limpadroid.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.km20197.limpadroid.AppContainer
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.ui.diagnostic.DiagnosticScreen
import io.github.km20197.limpadroid.ui.diagnostic.DiagnosticViewModel
import io.github.km20197.limpadroid.ui.history.HistoryScreen
import io.github.km20197.limpadroid.ui.history.HistoryViewModel
import io.github.km20197.limpadroid.ui.home.HomeScreen
import io.github.km20197.limpadroid.ui.home.HomeViewModel
import io.github.km20197.limpadroid.ui.onboarding.OnboardingScreen
import io.github.km20197.limpadroid.ui.onboarding.OnboardingViewModel
import io.github.km20197.limpadroid.ui.results.CategoryResultsScreen
import io.github.km20197.limpadroid.ui.results.CategoryResultsViewModel
import io.github.km20197.limpadroid.ui.scan.ScanStatusScreen
import io.github.km20197.limpadroid.ui.scan.ScanStatusViewModel
import io.github.km20197.limpadroid.ui.settings.SettingsScreen
import io.github.km20197.limpadroid.ui.settings.SettingsViewModel
import io.github.km20197.limpadroid.ui.trash.TrashScreen
import io.github.km20197.limpadroid.ui.trash.TrashViewModel

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
          scanController = container.scanController,
          trashController = container.trashController,
          installedAppsRepository = container.installedAppsRepository,
        ),
      )
      HomeScreen(
        viewModel = homeViewModel,
        onOpenDiagnostic = {
          navController.navigate(DiagnosticRoute) {
            launchSingleTop = true
          }
        },
        onOpenScanDetails = {
          navController.navigate(ScanStatusRoute) {
            launchSingleTop = true
          }
        },
        onOpenTrash = {
          navController.navigate(TrashRoute) {
            launchSingleTop = true
          }
        },
        onOpenHistory = {
          navController.navigate(HistoryRoute) {
            launchSingleTop = true
          }
        },
        onOpenCategoryResults = { category ->
          navController.navigate(CategoryResultsRoute(category.name)) {
            launchSingleTop = true
          }
        },
        onOpenSettings = {
          navController.navigate(SettingsRoute) {
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

    composable<ScanStatusRoute> {
      val scanStatusViewModel: ScanStatusViewModel = viewModel(
        factory = ScanStatusViewModel.Factory(
          permissionChecker = container.permissionChecker,
          scanController = container.scanController,
        ),
      )
      ScanStatusScreen(
        viewModel = scanStatusViewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }

    composable<TrashRoute> {
      val trashViewModel: TrashViewModel = viewModel(
        factory = TrashViewModel.Factory(
          trashController = container.trashController,
        ),
      )
      TrashScreen(
        viewModel = trashViewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }

    composable<HistoryRoute> {
      val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.Factory(
          trashController = container.trashController,
        ),
      )
      HistoryScreen(
        viewModel = historyViewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }

    composable<CategoryResultsRoute> { backStackEntry ->
      val route = backStackEntry.toRoute<CategoryResultsRoute>()
      val category = runCatching { Category.valueOf(route.categoryName) }
        .getOrDefault(Category.SUPERFLUOUS)
      val resultsViewModel: CategoryResultsViewModel = viewModel(
        factory = CategoryResultsViewModel.Factory(
          category = category,
          scanController = container.scanController,
          trashController = container.trashController,
          preferencesRepository = container.preferencesRepository,
        ),
      )
      CategoryResultsScreen(
        viewModel = resultsViewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }

    composable<SettingsRoute> {
      val settingsViewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.Factory(
          preferencesRepository = container.preferencesRepository,
        ),
      )
      SettingsScreen(
        viewModel = settingsViewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }
  }
}
