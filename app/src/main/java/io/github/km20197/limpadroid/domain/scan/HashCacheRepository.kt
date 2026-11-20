package io.github.km20197.limpadroid.domain.scan

data class CachedHashEntry(
  val path: String,
  val sizeBytes: Long,
  val lastModified: Long,
  val sha256: String,
  val updatedAt: Long,
)

interface HashCacheRepository {
  suspend fun getByPath(path: String): CachedHashEntry?
  suspend fun getByPaths(paths: List<String>): Map<String, CachedHashEntry>
  suspend fun upsertAll(entries: List<CachedHashEntry>)
}
