package io.github.km20197.limpadroid

import android.app.Application
import android.content.Context
import io.github.km20197.limpadroid.data.db.LimpaDroidDatabase
import io.github.km20197.limpadroid.data.fs.AndroidMediaScannerHook
import io.github.km20197.limpadroid.data.fs.AndroidMediaStoreGateway
import io.github.km20197.limpadroid.data.fs.AndroidStorageVolumeResolver
import io.github.km20197.limpadroid.data.fs.DefaultStorageDiagnosticRepository
import io.github.km20197.limpadroid.data.fs.StorageDiagnosticRepository
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.LocalScanLogger
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.data.permission.AndroidPermissionChecker
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.data.pkg.AndroidInstalledAppsRepository
import io.github.km20197.limpadroid.data.prefs.AppPreferencesRepository
import io.github.km20197.limpadroid.data.prefs.DataStoreAppPreferencesRepository
import io.github.km20197.limpadroid.data.scan.RoomHashCacheRepository
import io.github.km20197.limpadroid.data.scan.ScanRepository
import io.github.km20197.limpadroid.domain.diagnostic.DirectoryInspector
import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook
import io.github.km20197.limpadroid.domain.fs.MediaStoreGateway
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsRepository
import io.github.km20197.limpadroid.domain.scan.DuplicateScanEngine
import io.github.km20197.limpadroid.domain.scan.HashCacheRepository
import io.github.km20197.limpadroid.domain.scan.InfrastructureScanEngine
import io.github.km20197.limpadroid.domain.scan.OrphanScanEngine
import io.github.km20197.limpadroid.domain.scan.ScanEngine
import io.github.km20197.limpadroid.domain.scan.SuperfluousScanEngine
import io.github.km20197.limpadroid.domain.scan.WhatsAppScanEngine
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaType
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppScanSettings
import io.github.km20197.limpadroid.scan.ScanController
import io.github.km20197.limpadroid.scan.ScanWorkerDependencies
import io.github.km20197.limpadroid.scan.WorkManagerScanController
import io.github.km20197.limpadroid.trash.TrashController
import io.github.km20197.limpadroid.trash.TrashRepository
import io.github.km20197.limpadroid.trash.WorkManagerTrashController
import java.io.File
import kotlinx.coroutines.flow.first

interface AppContainer {
  val permissionChecker: PermissionChecker
  val preferencesRepository: AppPreferencesRepository
  val diagnosticRepository: StorageDiagnosticRepository
  val scanController: ScanController
  val scanWorkerDependencies: ScanWorkerDependencies
  val trashRepository: TrashRepository
  val trashController: TrashController
  val installedAppsRepository: InstalledAppsRepository
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

  private val mediaScannerHook: MediaScannerHook by lazy {
    AndroidMediaScannerHook(context)
  }

  private val mediaStoreGateway: MediaStoreGateway by lazy {
    AndroidMediaStoreGateway(context)
  }

  private val scanRepository: ScanRepository by lazy {
    ScanRepository(
      sessionDao = database.scanSessionDao(),
      candidateDao = database.scanCandidateDao(),
      errorDao = database.scanErrorDao(),
    )
  }

  private val fileWalker: FileWalker by lazy { FileWalker() }

  private val scanEngine: ScanEngine by lazy {
    InfrastructureScanEngine(fileWalker = fileWalker)
  }

  private val superfluousScanEngine: ScanEngine by lazy {
    SuperfluousScanEngine(
      fileWalker = fileWalker,
      mediaStoreGateway = mediaStoreGateway,
      enabledRulesProvider = { preferencesRepository.enabledSuperfluousRuleIds.first() },
    )
  }

  private val orphanScanEngine: ScanEngine by lazy {
    OrphanScanEngine(
      installedAppsRepository = installedAppsRepository,
      applicationId = BuildConfig.APPLICATION_ID,
    )
  }

  private val hashCacheRepository: HashCacheRepository by lazy {
    RoomHashCacheRepository(database.hashCacheDao())
  }

  private val duplicateScanEngine: ScanEngine by lazy {
    DuplicateScanEngine(
      hashCacheRepository = hashCacheRepository,
      minSizeBytesProvider = { preferencesRepository.duplicateMinSizeBytes.first() },
      priorityFoldersProvider = { preferencesRepository.duplicatePriorityFolders.first() },
    )
  }

  private val whatsappScanEngine: ScanEngine by lazy {
    WhatsAppScanEngine(
      settingsProvider = {
        WhatsAppScanSettings(
          minAgeDays = preferencesRepository.whatsappMinAgeDays.first(),
          enabledMediaTypes = WhatsAppMediaType.parseEnabledNames(
            preferencesRepository.whatsappEnabledMediaTypes.first(),
          ),
          autoPreselect = preferencesRepository.whatsappAutoPreselect.first(),
        )
      },
    )
  }

  override val installedAppsRepository: InstalledAppsRepository by lazy {
    AndroidInstalledAppsRepository(
      context = context,
      knownAppDao = database.knownAppDao(),
    )
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
      superfluousScanEngine = superfluousScanEngine,
      orphanScanEngine = orphanScanEngine,
      duplicateScanEngine = duplicateScanEngine,
      whatsappScanEngine = whatsappScanEngine,
      userExcludedPathsProvider = { preferencesRepository.userExcludedPaths.first() },
    )
  }

  override val trashRepository: TrashRepository by lazy {
    TrashRepository(
      trashEntryDao = database.trashEntryDao(),
      operationHistoryDao = database.operationHistoryDao(),
      storageVolumeResolver = volumeResolver,
      mediaScannerHook = mediaScannerHook,
      scanLogger = scanLogger,
      applicationId = BuildConfig.APPLICATION_ID,
      scanCandidateDao = database.scanCandidateDao(),
    )
  }

  override val trashController: TrashController by lazy {
    WorkManagerTrashController(
      context = context,
      trashRepository = trashRepository,
    )
  }
}

open class LimpaDroidApp : Application() {
  open val container: AppContainer by lazy { DefaultAppContainer(applicationContext) }
}
