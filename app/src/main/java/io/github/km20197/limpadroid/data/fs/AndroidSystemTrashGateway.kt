package io.github.km20197.limpadroid.data.fs

import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook
import io.github.km20197.limpadroid.domain.fs.SystemTrashGateway
import io.github.km20197.limpadroid.domain.fs.SystemTrashPurgeResult
import io.github.km20197.limpadroid.domain.fs.SystemTrashStats
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidSystemTrashGateway(
  private val context: Context,
  private val permissionChecker: PermissionChecker,
  private val storageVolumeResolver: StorageVolumeResolver,
  private val mediaScannerHook: MediaScannerHook,
  private val scanLogger: ScanLogger,
  private val applicationId: String,
) : SystemTrashGateway {

  private data class SystemTrashedRow(
    val id: Long,
    val sizeBytes: Long,
    val path: String?,
  )

  override suspend fun queryStats(): SystemTrashStats = withContext(Dispatchers.IO) {
    if (!permissionChecker.hasAllFilesAccess()) {
      return@withContext SystemTrashStats()
    }
    val rows = queryTrashedRows()
    SystemTrashStats(
      itemCount = rows.size,
      totalBytes = rows.sumOf { it.sizeBytes },
    )
  }

  override suspend fun emptySystemTrash(): SystemTrashPurgeResult = withContext(Dispatchers.IO) {
    if (!permissionChecker.hasAllFilesAccess()) {
      return@withContext SystemTrashPurgeResult()
    }
    val rows = queryTrashedRows()
    if (rows.isEmpty()) {
      return@withContext SystemTrashPurgeResult()
    }

    val storageRoot = (storageVolumeResolver.resolvePrimarySharedStorage() as? StorageRootResolution.Available)
      ?.rootPath
    val pathGuard = storageRoot?.let {
      DefaultPathGuard(rawStorageRoot = it, applicationId = applicationId)
    }

    val collection = MediaStore.Files.getContentUri("external")
    val deleteExtras = Bundle().apply {
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
    }

    var purgedCount = 0
    var freedBytes = 0L
    var failedCount = 0
    val pathsToScan = mutableListOf<String>()

    for (row in rows) {
      currentCoroutineContext().ensureActive()
      val rawPath = row.path?.takeIf { it.isNotBlank() }
      val parsedPath = rawPath?.let { runCatching { Paths.get(it) }.getOrNull() }

      if (rawPath != null && parsedPath == null) {
        failedCount += 1
        continue
      }
      if (parsedPath != null && pathGuard != null && !pathGuard.isAllowed(parsedPath)) {
        failedCount += 1
        scanLogger.logEvent("SYSTEM_TRASH_SKIPPED_PATH", "id=${row.id} path=$rawPath")
        continue
      }

      val itemUri = ContentUris.withAppendedId(collection, row.id)
      var deletedViaResolver = false
      try {
        val deletedRows = context.contentResolver.delete(itemUri, deleteExtras)
        deletedViaResolver = deletedRows > 0
      } catch (ce: CancellationException) {
        throw ce
      } catch (_: Throwable) {
        deletedViaResolver = false
      }

      val stillExistsOnDisk = parsedPath != null && Files.exists(parsedPath, LinkOption.NOFOLLOW_LINKS)
      var removed = deletedViaResolver && !stillExistsOnDisk

      if (stillExistsOnDisk) {
        val deletedDirectly = runCatching {
          Files.deleteIfExists(parsedPath)
        }.getOrDefault(false)
        if (deletedDirectly || !Files.exists(parsedPath, LinkOption.NOFOLLOW_LINKS)) {
          removed = true
          pathsToScan.add(parsedPath.toString())
          runCatching { context.contentResolver.delete(itemUri, deleteExtras) }
        }
      }

      if (removed) {
        purgedCount += 1
        freedBytes += row.sizeBytes
      } else {
        failedCount += 1
        scanLogger.logEvent("SYSTEM_TRASH_PURGE_FAILED", "id=${row.id} path=${rawPath.orEmpty()}")
      }
    }

    if (pathsToScan.isNotEmpty()) {
      runCatching { mediaScannerHook.scanPaths(pathsToScan) }
    }
    if (purgedCount > 0 || failedCount > 0) {
      scanLogger.logEvent(
        "SYSTEM_TRASH_EMPTIED",
        "purged=$purgedCount bytes=$freedBytes failed=$failedCount",
      )
    }

    SystemTrashPurgeResult(
      purgedCount = purgedCount,
      freedBytes = freedBytes,
      failedCount = failedCount,
    )
  }

  @Suppress("DEPRECATION")
  private suspend fun queryTrashedRows(): List<SystemTrashedRow> {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
      MediaStore.MediaColumns._ID,
      MediaStore.MediaColumns.SIZE,
      MediaStore.MediaColumns.DATA,
      MediaStore.MediaColumns.IS_TRASHED,
    )
    val queryArgs = Bundle().apply {
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
    }

    val cursor = runCatching {
      context.contentResolver.query(collection, projection, queryArgs, null)
    }.getOrNull() ?: return emptyList()

    val rows = mutableListOf<SystemTrashedRow>()
    cursor.use { c ->
      val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
      val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
      val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
      val trashedCol = c.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
      // Confirma explicitamente IS_TRASHED para nunca atingir arquivos ativos caso um provedor ignore queryArgs.
      if (idCol < 0 || trashedCol < 0) return@use

      while (c.moveToNext()) {
        currentCoroutineContext().ensureActive()
        if (c.getInt(trashedCol) == 0) continue

        val id = c.getLong(idCol)
        val rawPath = if (dataCol >= 0) c.getString(dataCol) else null
        val cursorSize = if (sizeCol >= 0) c.getLong(sizeCol).coerceAtLeast(0L) else 0L
        val resolvedSize = if (cursorSize > 0L) {
          cursorSize
        } else if (!rawPath.isNullOrBlank()) {
          runCatching {
            val p = Paths.get(rawPath)
            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) Files.size(p) else 0L
          }.getOrDefault(0L)
        } else {
          0L
        }
        rows.add(SystemTrashedRow(id = id, sizeBytes = resolvedSize, path = rawPath))
      }
    }
    return rows
  }
}
