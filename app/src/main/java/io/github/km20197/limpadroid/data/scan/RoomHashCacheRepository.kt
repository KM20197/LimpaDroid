package io.github.km20197.limpadroid.data.scan

import io.github.km20197.limpadroid.data.db.HashCacheDao
import io.github.km20197.limpadroid.data.db.HashCacheEntity
import io.github.km20197.limpadroid.domain.scan.CachedHashEntry
import io.github.km20197.limpadroid.domain.scan.HashCacheRepository

class RoomHashCacheRepository(
  private val hashCacheDao: HashCacheDao,
) : HashCacheRepository {

  override suspend fun getByPath(path: String): CachedHashEntry? =
    hashCacheDao.getByPath(path)?.toDomain()

  override suspend fun getByPaths(paths: List<String>): Map<String, CachedHashEntry> {
    if (paths.isEmpty()) return emptyMap()
    val result = HashMap<String, CachedHashEntry>(paths.size)
    paths.distinct().chunked(BATCH_SIZE).forEach { chunk ->
      hashCacheDao.getByPaths(chunk).forEach { entity ->
        result[entity.path] = entity.toDomain()
      }
    }
    return result
  }

  override suspend fun upsertAll(entries: List<CachedHashEntry>) {
    if (entries.isEmpty()) return
    entries.chunked(BATCH_SIZE).forEach { chunk ->
      hashCacheDao.upsertAll(chunk.map { it.toEntity() })
    }
  }

  private fun HashCacheEntity.toDomain(): CachedHashEntry = CachedHashEntry(
    path = path,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    sha256 = sha256,
    updatedAt = updatedAt,
  )

  private fun CachedHashEntry.toEntity(): HashCacheEntity = HashCacheEntity(
    path = path,
    sizeBytes = sizeBytes,
    lastModified = lastModified,
    sha256 = sha256,
    updatedAt = updatedAt,
  )

  private companion object {
    const val BATCH_SIZE = 400
  }
}
