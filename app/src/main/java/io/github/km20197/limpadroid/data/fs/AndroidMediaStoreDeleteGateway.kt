package io.github.km20197.limpadroid.data.fs

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.domain.fs.MediaStoreDeleteGateway
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AndroidMediaStoreDeleteGateway(
  private val context: Context,
  private val permissionChecker: PermissionChecker,
  private val scanLogger: ScanLogger? = null,
) : MediaStoreDeleteGateway {

  private data class MatchedMediaEntry(
    val id: Long,
    val primaryUri: Uri,
    val filesUri: Uri,
  )

  override suspend fun findAssociatedUri(filePath: Path): String? = withContext(Dispatchers.IO) {
    resolveMatchedEntries(filePath).firstOrNull()?.primaryUri?.toString()
  }

  override suspend fun deleteByAssociatedUri(filePath: Path): Boolean = withContext(Dispatchers.IO) {
    if (!permissionChecker.hasAllFilesAccess()) {
      return@withContext false
    }
    val matches = resolveMatchedEntries(filePath)
    if (matches.isEmpty()) {
      return@withContext false
    }

    val deleteExtras = Bundle().apply {
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }

    var anyDeleted = false
    for (match in matches) {
      currentCoroutineContext().ensureActive()
      val deletedPrimary = tryDeleteUri(match.primaryUri, deleteExtras)
      val deleted = if (deletedPrimary > 0) {
        true
      } else if (match.primaryUri != match.filesUri) {
        tryDeleteUri(match.filesUri, deleteExtras) > 0
      } else {
        false
      }
      if (deleted) {
        anyDeleted = true
        scanLogger?.logEvent(
          "TRASH_MEDIASTORE_URI_DELETED",
          "uri=${match.primaryUri} path=${filePath.toAbsolutePath().normalize()}",
        )
      }
    }
    anyDeleted
  }

  @Suppress("DEPRECATION")
  private fun tryDeleteUri(uri: Uri, extras: Bundle): Int {
    val withExtras = try {
      context.contentResolver.delete(uri, extras)
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      scanLogger?.logEvent("TRASH_MEDIASTORE_DELETE_ERR", "${t.javaClass.simpleName}: ${t.message}")
      0
    }
    if (withExtras > 0) return withExtras

    return try {
      context.contentResolver.delete(uri, null, null)
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      scanLogger?.logEvent("TRASH_MEDIASTORE_DELETE_ERR", "${t.javaClass.simpleName}: ${t.message}")
      0
    }
  }

  @Suppress("DEPRECATION")
  private suspend fun resolveMatchedEntries(filePath: Path): List<MatchedMediaEntry> {
    val candidates = buildCandidatePathStrings(filePath)
    if (candidates.isEmpty()) return emptyList()

    val filesCollection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
      MediaStore.MediaColumns._ID,
      MediaStore.Files.FileColumns.MEDIA_TYPE,
      MediaStore.MediaColumns.DATA,
    )
    val placeholders = candidates.joinToString(",") { "?" }
    val queryArgs = Bundle().apply {
      putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
      putString(
        ContentResolver.QUERY_ARG_SQL_SELECTION,
        "${MediaStore.MediaColumns.DATA} IN ($placeholders)",
      )
      putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, candidates.toTypedArray())
    }

    val cursor = runCatching {
      context.contentResolver.query(filesCollection, projection, queryArgs, null)
    }.getOrNull() ?: return emptyList()

    val matches = mutableListOf<MatchedMediaEntry>()
    val seenIds = HashSet<Long>()
    cursor.use { c ->
      val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
      val mediaTypeCol = c.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
      val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
      if (idCol < 0 || dataCol < 0) return@use

      while (c.moveToNext()) {
        currentCoroutineContext().ensureActive()
        val rowPath = c.getString(dataCol)?.takeIf { it.isNotBlank() } ?: continue
        if (rowPath !in candidates) continue

        val id = c.getLong(idCol)
        if (!seenIds.add(id)) continue

        val mediaType = if (mediaTypeCol >= 0) {
          c.getInt(mediaTypeCol)
        } else {
          MediaStore.Files.FileColumns.MEDIA_TYPE_NONE
        }
        val typedCollection = when (mediaType) {
          MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
          MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
          MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
          else -> filesCollection
        }
        matches.add(
          MatchedMediaEntry(
            id = id,
            primaryUri = ContentUris.withAppendedId(typedCollection, id),
            filesUri = ContentUris.withAppendedId(filesCollection, id),
          ),
        )
      }
    }
    return matches
  }

  private fun buildCandidatePathStrings(filePath: Path): Set<String> {
    val result = linkedSetOf<String>()
    val normalized = filePath.toAbsolutePath().normalize().toString()
    if (normalized.isNotBlank()) {
      result.add(normalized)
    }
    runCatching { filePath.toRealPath(LinkOption.NOFOLLOW_LINKS).toString() }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
      ?.let { result.add(it) }
    return result
  }
}
