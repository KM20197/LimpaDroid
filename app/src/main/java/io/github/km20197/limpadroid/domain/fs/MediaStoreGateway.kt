package io.github.km20197.limpadroid.domain.fs

import java.nio.file.Path

data class StaleMediaEntry(
  val mediaId: Long,
  val path: String,
  val lastModifiedMillis: Long,
)

interface MediaStoreGateway {
  suspend fun findStaleEntries(
    storageRoot: Path,
    pathGuard: PathGuard,
  ): List<StaleMediaEntry>
}
