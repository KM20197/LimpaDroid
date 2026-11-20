package io.github.km20197.limpadroid.domain.pkg

import java.util.Locale

data class InstalledAppInfo(
  val packageName: String,
  val label: String,
  val isArchived: Boolean = false,
)

data class KnownAppRecord(
  val packageName: String,
  val label: String,
  val firstSeen: Long,
  val lastSeen: Long,
  val removedAt: Long? = null,
)

data class InstalledAppsSnapshot(
  val presentApps: List<InstalledAppInfo>,
  val knownApps: List<KnownAppRecord>,
)

object AppIdentifierNormalizer {
  val PACKAGE_NAME_REGEX: Regex = Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")

  fun normalize(value: String): String =
    value.trim()
      .lowercase(Locale.ROOT)
      .replace(" ", "")
      .replace("-", "")
      .replace("_", "")

  fun isPackageFormat(value: String): Boolean = PACKAGE_NAME_REGEX.matches(value)
}

interface InstalledAppsRepository {
  suspend fun syncKnownApps(): InstalledAppsSnapshot
  suspend fun loadSnapshot(): InstalledAppsSnapshot
}
