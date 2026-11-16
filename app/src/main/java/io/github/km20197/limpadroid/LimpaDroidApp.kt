package io.github.km20197.limpadroid

import android.app.Application
import android.content.Context
import io.github.km20197.limpadroid.data.db.LimpaDroidDatabase
import io.github.km20197.limpadroid.data.fs.AndroidStorageVolumeResolver
import io.github.km20197.limpadroid.data.fs.DefaultStorageDiagnosticRepository
import io.github.km20197.limpadroid.data.fs.StorageDiagnosticRepository
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.LocalScanLogger
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.data.permission.AndroidPermissionChecker
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.data.prefs.DataStoreAppPreferencesRepository
import io.github.km20197.limpadroid.data.scan.ScanRepository
import io.github.km20197.limpadroid.domain.diagnostic.DirectoryInspector
import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.scan.InfrastructureScanEngine
import io.github.km20197.limpadroid.domain.scan.ScanEngine
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanWorkerDependencies
import io.github.km20197.limpadroid.scan.WorkManagerScanController
import java.io.File

interface AppContainer {
  val permissionChecker: PermissionChecker
  val preferencesRepository: AppPreferencesRepository
  val diagnosticRepository: StorageDiagnosticRepository
  val scanController: ScanController
  val scanWorkerDependencies: ScanWorkerDependencies
}

class DefaultAppContainer(
  private val context: Context,
) : AppContainer {

  private val volumeResolver: StorageVolumeResolver by lazy {
    AndroidStorageVolumeResolver(context)
  }

  private val database: LimpaDroidDatabase by lazy {
    LimpaDroidDatabase.getInstance(context)
  }

  private val scanLogger: ScanLogger by lazy {
    LocalScanLogger(File(context.filesDir, "logs"))
  }

  private val scanRepository: ScanRepository by lazy {
    ScanRepository(
      sessionDao = database.scanSessionDao(),
      candidateDao = database.scanCandidateDao(),
      errorDao = database.scanErrorDao(),
    )
  }

  private val scanEngine: ScanEngine by lazy {
    InfrastructureScanEngine(fileWalker = FileWalker())
  }

  override val permissionChecker: PermissionChecker by lazy {
    AndroidPermissionChecker(context)
  }

  override val preferencesRepository: AppPreferencesRepository by lazy {
    DataStoreAppPreferencesRepository(context)
  }

  override val diagnosticRepository: StorageDiagnosticRepository by lazy {
    DefaultStorageDiagnosticRepository(
      permissionChecker = permissionChecker,
      volumeResolver = volumeResolver,
      inspector = DirectoryInspector(),
    )
  }

  override val scanController: ScanController by lazy {
    WorkManagerScanController(
      context = context,
      scanRepository = scanRepository,
      storageVolumeResolver = volumeResolver,
      scanLogger = scanLogger,
    )
  }

  override val scanWorkerDependencies: ScanWorkerDependencies by lazy {
    ScanWorkerDependencies(
      scanRepository = scanRepository,
      storageVolumeResolver = volumeResolver,
      scanEngine = scanEngine,
      scanLogger = scanLogger,
      applicationId = BuildConfig.APPLICATION_ID,
    )
  }
}

open class LimpaDroidApp : Application() {
  open val container: AppContainer by lazy { DefaultAppContainer(applicationContext) }
}
