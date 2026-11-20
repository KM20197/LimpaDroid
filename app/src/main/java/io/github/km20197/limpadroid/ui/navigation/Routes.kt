package io.github.km20197.limpadroid.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
data object OnboardingRoute

@Serializable
data object HomeRoute

@Serializable
data object DiagnosticRoute

@Serializable
data object ScanStatusRoute

@Serializable
data object TrashRoute

@Serializable
data object HistoryRoute

@Serializable
data class CategoryResultsRoute(val categoryName: String)

@Serializable
data object SettingsRoute
