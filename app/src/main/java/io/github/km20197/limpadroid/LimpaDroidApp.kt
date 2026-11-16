package io.github.km20197.limpadroid

import android.app.Application
import android.content.Context
import io.github.km20197.limpadroid.data.fs.DefaultStorageDiagnosticRepository
import io.github.km20197.limpadroid.data.fs.StorageDiagnosticRepository
import io.github.km20197.limpadroid.data.permission.AndroidPermissionChecker
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.data.prefs.DataStoreAppPreferencesRepository
import io.github.km20197.limpadroid.domain.diagnostic.DirectoryInspector

interface AppContainer {
  val permissionChecker: PermissionChecker
  val preferencesRepository: AppPreferencesRepository
  val diagnosticRepository: StorageDiagnosticRepository
}

class DefaultAppContainer(
  private val context: Context,
) : AppContainer {
  override val permissionChecker: PermissionChecker by lazy {
    AndroidPermissionChecker(context)
  }

  override val preferencesRepository: AppPreferencesRepository by lazy {
    DataStoreAppPreferencesRepository(context)
  }

  override val diagnosticRepository: StorageDiagnosticRepository by lazy {
    DefaultStorageDiagnosticRepository(
      permissionChecker = permissionChecker,
      inspector = DirectoryInspector(),
    )
  }
}

open class LimpaDroidApp : Application() {
  open val container: AppContainer by lazy { DefaultAppContainer(applicationContext) }
}
