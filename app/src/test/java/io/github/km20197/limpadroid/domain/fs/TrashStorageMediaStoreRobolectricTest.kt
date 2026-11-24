package io.github.km20197.limpadroid.domain.fs

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class TrashStorageMediaStoreRobolectricTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `deletePermanentViaMediaStore queries MediaStore URI and deletes permanently via ContentResolver`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storageRoot = tempFolder.newFolder("trash_ms_direct").toPath().toRealPath()
    val storage = TrashStorage(
      usableSpaceProvider = { 512L * 1024L * 1024L },
      contentResolver = context.contentResolver,
    )
    val trashRoot = storage.resolveTrashRoot(storageRoot)
    storage.ensureTrashRootInitialized(trashRoot)

    val entryDir = storage.allocateEntryDir(trashRoot, "entry-direct-1", 1_700_000_000_000L)
    val payload = storage.resolvePayloadPath(entryDir)
    Files.createDirectories(entryDir)
    Files.writeString(payload, "video-payload-bytes")

    val unindexedEntryDir = storage.allocateEntryDir(trashRoot, "entry-direct-2", 1_700_000_000_000L)
    val unindexedPayload = storage.resolvePayloadPath(unindexedEntryDir)
    Files.createDirectories(unindexedEntryDir)
    Files.writeString(unindexedPayload, "plain-temp-bytes")

    val originalMissing = storageRoot.resolve("DCIM/Camera/VID_001.mp4")

    val provider = FakeMediaStoreProvider(
      mutableListOf(
        MediaRow(
          id = 501L,
          mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
          path = payload.toString(),
        ),
        MediaRow(
          id = 502L,
          mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
          path = originalMissing.toString(),
        ),
      ),
    )
    ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

    // 1) Chamada direta ao método dedicado com ContentResolver
    assertFalse(storage.deletePermanentViaMediaStore(context.contentResolver, unindexedPayload))
    assertTrue(Files.exists(unindexedPayload, LinkOption.NOFOLLOW_LINKS))

    // 2) deleteTrashedPayload aciona deletePermanentViaMediaStore para payload e originalPath ausente
    storage.deleteTrashedPayload(
      trashRoot = trashRoot,
      trashPath = payload,
      originalPath = originalMissing,
    )

    assertTrue(provider.deletedUris.contains("content://media/external/video/media/501"))
    assertTrue(provider.deletedUris.contains("content://media/external/video/media/502"))
    assertFalse(Files.exists(payload, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(entryDir, LinkOption.NOFOLLOW_LINKS))
  }

  private data class MediaRow(
    val id: Long,
    val mediaType: Int,
    val path: String,
  )

  private class FakeMediaStoreProvider(
    private val rows: MutableList<MediaRow>,
  ) : ContentProvider() {
    val deletedUris = mutableListOf<String>()

    override fun onCreate(): Boolean = true

    @Suppress("DEPRECATION")
    override fun query(
      uri: Uri,
      projection: Array<out String>?,
      queryArgs: Bundle?,
      cancellationSignal: CancellationSignal?,
    ): Cursor {
      val cols = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DATA,
      )
      val cursor = MatrixCursor(cols)
      for (row in rows) {
        cursor.addRow(arrayOf<Any>(row.id, row.mediaType, row.path))
      }
      return cursor
    }

    override fun query(
      uri: Uri,
      projection: Array<out String>?,
      selection: String?,
      selectionArgs: Array<out String>?,
      sortOrder: String?,
    ): Cursor = query(uri, projection, null, null)

    override fun delete(uri: Uri, extras: Bundle?): Int {
      val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return 0
      val match = rows.firstOrNull { it.id == id } ?: return 0
      deletedUris.add(uri.toString())
      Files.deleteIfExists(Path.of(match.path))
      rows.removeAll { it.id == id }
      return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
      delete(uri, null)

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
  }
}
