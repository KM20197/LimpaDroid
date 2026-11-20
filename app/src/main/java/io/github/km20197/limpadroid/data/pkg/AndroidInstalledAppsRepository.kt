package io.github.km20197.limpadroid.data.pkg

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.km20197.limpadroid.data.db.KnownAppDao
import io.github.km20197.limpadroid.data.db.KnownAppEntity
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.pkg.InstalledAppInfo
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsRepository
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsSnapshot
import io.github.km20197.limpadroid.domain.pkg.KnownAppRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidInstalledAppsRepository(
  private val context: Context,
  private val knownAppDao: KnownAppDao,
  private val clock: AppClock = AppClock.System,
  private val packagesReader: () -> List<InstalledAppInfo> = { readPresentPackages(context) },
) : InstalledAppsRepository {

  private val syncMutex = Mutex()

  override suspend fun syncKnownApps(): InstalledAppsSnapshot = syncMutex.withLock {
    val now = clock.nowMillis()
    val presentApps = withContext(Dispatchers.IO) {
      runCatching { packagesReader() }.getOrDefault(emptyList())
    }
    val existing = knownAppDao.getAll().associateBy { it.packageName }

    // Salvaguarda: se PackageManager falhar ou retornar vazio (impossível pois o próprio app está presente),
    // não marca o histórico inteiro como removido.
    if (presentApps.isEmpty()) {
      return@withLock InstalledAppsSnapshot(
        presentApps = emptyList(),
        knownApps = existing.values.map { it.toDomain() },
      )
    }

    val presentByPackage = presentApps.associateBy { it.packageName }
    val updatedEntities = mutableListOf<KnownAppEntity>()

    for (app in presentApps) {
      val previous = existing[app.packageName]
      val effectiveLabel = app.label.ifBlank { previous?.label ?: app.packageName }
      updatedEntities.add(
        KnownAppEntity(
          packageName = app.packageName,
          label = effectiveLabel,
          firstSeen = previous?.firstSeen ?: now,
          lastSeen = now,
          removedAt = null,
        ),
      )
    }

    for ((pkg, previous) in existing) {
      if (pkg !in presentByPackage) {
        updatedEntities.add(
          previous.copy(
            removedAt = previous.removedAt ?: now,
          ),
        )
      }
    }

    knownAppDao.upsertAll(updatedEntities)

    return@withLock InstalledAppsSnapshot(
      presentApps = presentApps,
      knownApps = updatedEntities.map { it.toDomain() },
    )
  }

  override suspend fun loadSnapshot(): InstalledAppsSnapshot {
    val presentApps = withContext(Dispatchers.IO) {
      runCatching { packagesReader() }.getOrDefault(emptyList())
    }
    val knownApps = knownAppDao.getAll().map { it.toDomain() }
    return InstalledAppsSnapshot(
      presentApps = presentApps,
      knownApps = knownApps,
    )
  }

  private fun KnownAppEntity.toDomain(): KnownAppRecord = KnownAppRecord(
    packageName = packageName,
    label = label,
    firstSeen = firstSeen,
    lastSeen = lastSeen,
    removedAt = removedAt,
  )

  companion object {
    fun readPresentPackages(context: Context): List<InstalledAppInfo> {
      val pm = context.packageManager
      val packages: List<PackageInfo> = queryInstalledPackageInfos(pm)
      return packages.mapNotNull { info ->
        val packageName = info.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val appInfo = info.applicationInfo
        val isArchived = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
          appInfo?.isArchived == true
        } else {
          false
        }
        val label = appInfo?.let {
          runCatching { pm.getApplicationLabel(it).toString() }.getOrNull()
        }?.takeIf { it.isNotBlank() } ?: packageName

        InstalledAppInfo(
          packageName = packageName,
          label = label,
          isArchived = isArchived,
        )
      }.distinctBy { it.packageName }
    }

    @Suppress("DEPRECATION")
    private fun queryInstalledPackageInfos(pm: PackageManager): List<PackageInfo> = when {
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
        val flags = PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong() or
          PackageManager.MATCH_ARCHIVED_PACKAGES
        pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags))
      }

      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
        pm.getInstalledPackages(
          PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()),
        )
      }

      else -> {
        pm.getInstalledPackages(PackageManager.MATCH_UNINSTALLED_PACKAGES)
      }
    }
  }
}
