package io.github.km20197.limpadroid.data.fs

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
class AndroidMediaStoreDeleteGatewayTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `findAssociatedUri resolves typed media URI and deleteByAssociatedUri invokes ContentResolver delete`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = tempFolder.newFolder("shared_ms").toPath().toRealPath()
    val payloadImage = Files.writeString(root.resolve("payload_img"), "jpg-data")
    val payloadVideo = Files.writeString(root.resolve("payload_vid"), "mp4-data")
    val unindexedFile = Files.writeString(root.resolve("unindexed.bin"), "raw")

    val provider = FakeMediaStoreProvider(
      mutableListOf(
        Row(
          id = 42L,
          mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
          path = payloadImage.toString(),
        ),
        Row(
          id = 77L,
          mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
          path = payloadVideo.toString(),
        ),
      ),
    )
    ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

    val logs = mutableListOf<Pair<String, String>>()
    val gateway = AndroidMediaStoreDeleteGateway(
      context = context,
      permissionChecker = FakePermissionChecker(granted = true),
      scanLogger = object : ScanLogger {
        override fun logEvent(tag: String, message: String) {
          logs.add(tag to message)
        }
      },
    )

    assertEquals("content://media/external/images/media/42", gateway.findAssociatedUri(payloadImage))
    assertEquals("content://media/external/video/media/77", gateway.findAssociatedUri(payloadVideo))
    assertNull(gateway.findAssociatedUri(unindexedFile))

    assertTrue(
      "Esperado delete=true, logs=$logs, deletedUris=${provider.deletedUris}",
      gateway.deleteByAssociatedUri(payloadImage),
    )
    assertTrue(gateway.deleteByAssociatedUri(payloadVideo))
    assertFalse(gateway.deleteByAssociatedUri(unindexedFile))

    assertFalse(Files.exists(payloadImage))
    assertFalse(Files.exists(payloadVideo))
    assertTrue(Files.exists(unindexedFile))
    assertTrue(provider.deletedUris.contains("content://media/external/images/media/42"))
    assertTrue(provider.deletedUris.contains("content://media/external/video/media/77"))
    assertEquals(2, logs.count { it.first == "TRASH_MEDIASTORE_URI_DELETED" })
  }

  @Test
  fun `deleteByAssociatedUri does not delete when all files access permission is missing`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val root = tempFolder.newFolder("shared_ms_noperm").toPath().toRealPath()
    val payloadImage = Files.writeString(root.resolve("payload_img"), "jpg-data")

    val provider = FakeMediaStoreProvider(
      mutableListOf(
        Row(
          id = 99L,
          mediaType = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE,
          path = payloadImage.toString(),
        ),
      ),
    )
    ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

    val gateway = AndroidMediaStoreDeleteGateway(
      context = context,
      permissionChecker = FakePermissionChecker(granted = false),
    )

    assertEquals("content://media/external/images/media/99", gateway.findAssociatedUri(payloadImage))
    assertFalse(gateway.deleteByAssociatedUri(payloadImage))
    assertTrue(Files.exists(payloadImage))
    assertTrue(provider.deletedUris.isEmpty())
  }

  private class FakePermissionChecker(private val granted: Boolean) : PermissionChecker {
    override fun hasAllFilesAccess(): Boolean = granted
    override fun hasNotificationPermission(): Boolean = true
    override fun createAllFilesAccessIntent(): Intent = Intent("test.ACTION")
  }

  private data class Row(
    val id: Long,
    val mediaType: Int,
    val path: String,
  )

  private class FakeMediaStoreProvider(
    private val rows: MutableList<Row>,
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
