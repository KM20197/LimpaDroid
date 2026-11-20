package io.github.km20197.limpadroid.data.fs

import android.content.Context
import android.provider.MediaStore
import io.github.km20197.limpadroid.domain.fs.MediaStoreGateway
import io.github.km20197.limpadroid.domain.fs.PathGuard
import io.github.km20197.limpadroid.domain.fs.StaleMediaEntry
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidMediaStoreGateway(
  private val context: Context,
) : MediaStoreGateway {

  @Suppress("DEPRECATION")
  override suspend fun findStaleEntries(
    storageRoot: Path,
    pathGuard: PathGuard,
  ): List<StaleMediaEntry> = withContext(Dispatchers.IO) {
    val results = mutableListOf<StaleMediaEntry>()
    val seenPaths = HashSet<String>()
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
      MediaStore.MediaColumns._ID,
      MediaStore.MediaColumns.DATA,
      MediaStore.MediaColumns.DATE_MODIFIED,
      MediaStore.MediaColumns.IS_PENDING,
      MediaStore.MediaColumns.IS_TRASHED,
    )

    val cursor = runCatching {
      context.contentResolver.query(collection, projection, null, null, null)
    }.getOrNull() ?: return@withContext emptyList()

    cursor.use { c ->
      val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
      val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
      val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
      val pendingCol = c.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
      val trashedCol = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
      if (idCol < 0 || dataCol < 0) return@use

      while (c.moveToNext()) {
        currentCoroutineContext().ensureActive()
        if (pendingCol >= 0 && c.getInt(pendingCol) != 0) continue
        if (trashedCol >= 0 && c.getInt(trashedCol) != 0) continue

        val rawPath = c.getString(dataCol) ?: continue
        if (rawPath.isBlank()) continue
        val parsedPath = runCatching { Paths.get(rawPath) }.getOrNull() ?: continue
        if (!pathGuard.isAllowed(parsedPath)) continue
        if (Files.exists(parsedPath, LinkOption.NOFOLLOW_LINKS)) continue

        val normalized = parsedPath.toAbsolutePath().normalize().toString()
        if (!seenPaths.add(normalized)) continue

        val dateModifiedSec = if (dateCol >= 0) c.getLong(dateCol) else 0L
        results.add(
          StaleMediaEntry(
            mediaId = c.getLong(idCol),
            path = normalized,
            lastModifiedMillis = if (dateModifiedSec > 0L) dateModifiedSec * 1000L else 0L,
          ),
        )
      }
    }
    results
  }
}
