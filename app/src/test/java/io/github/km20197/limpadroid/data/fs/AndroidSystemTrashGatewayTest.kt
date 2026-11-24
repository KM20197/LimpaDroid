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
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
class AndroidSystemTrashGatewayTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `queryStats and emptySystemTrash purge only trashed allowed rows and handle direct disk fallback`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storageRoot = tempFolder.newFolder("shared").toPath().toRealPath()
    val outsideRoot = tempFolder.newFolder("outside").toPath().toRealPath()

    val dcimDir = Files.createDirectories(storageRoot.resolve("DCIM"))
    val trashedByResolver = Files.writeString(dcimDir.resolve(".trashed-1700-foto.jpg"), "foto-trash")
    val trashedDirectFallback = Files.writeString(dcimDir.resolve(".trashed-1701-video.mp4"), "video-trash-bytes")
    val activeFile = Files.writeString(dcimDir.resolve("ativa.jpg"), "nao-tocar")
    val outsideTrashed = Files.writeString(outsideRoot.resolve(".trashed-fora.jpg"), "fora")

    val provider = FakeMediaProvider(
      rows = mutableListOf(
        FakeRow(id = 1L, size = 10L, path = trashedByResolver.toString(), isTrashed = 1, allowResolverDelete = true),
        FakeRow(id = 2L, size = 17L, path = trashedDirectFallback.toString(), isTrashed = 1, allowResolverDelete = false),
        FakeRow(id = 3L, size = 9L, path = activeFile.toString(), isTrashed = 0, allowResolverDelete = true),
        FakeRow(id = 4L, size = 4L, path = outsideTrashed.toString(), isTrashed = 1, allowResolverDelete = true),
      ),
      onDelete = { row ->
        Files.deleteIfExists(Path.of(row.path))
      },
    )
    ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)

    val scannedPaths = mutableListOf<String>()
    val logs = mutableListOf<Pair<String, String>>()
    val gateway = AndroidSystemTrashGateway(
      context = context,
      permissionChecker = FakePermissionChecker(granted = true),
      storageVolumeResolver = object : StorageVolumeResolver {
        override fun resolvePrimarySharedStorage(): StorageRootResolution =
          StorageRootResolution.Available(storageRoot, "Teste")
      },
      mediaScannerHook = MediaScannerHook { paths -> scannedPaths.addAll(paths) },
      scanLogger = object : ScanLogger {
        override fun logEvent(tag: String, message: String) {
          logs.add(tag to message)
        }
      },
      applicationId = "io.github.km20197.limpadroid",
    )

    val stats = gateway.queryStats()
    assertEquals(3, stats.itemCount)
    assertEquals(31L, stats.totalBytes)

    val result = gateway.emptySystemTrash()

    assertEquals(2, result.purgedCount)
    assertEquals(27L, result.freedBytes)
    assertEquals(1, result.failedCount)

    // Itens em lixeira permitidos foram removidos; arquivo ativo (IS_TRASHED=0) e externo permaneceram intactos
    assertFalse(Files.exists(trashedByResolver))
    assertFalse(Files.exists(trashedDirectFallback))
    assertTrue(Files.exists(activeFile))
    assertTrue(Files.exists(outsideTrashed))
    assertTrue(scannedPaths.contains(trashedDirectFallback.toString()))
    assertTrue(logs.any { it.first == "SYSTEM_TRASH_SKIPPED_PATH" })
    assertTrue(logs.any { it.first == "SYSTEM_TRASH_EMPTIED" })
    assertEquals(MediaStore.MATCH_ONLY, provider.lastQueryTrashedFlag)
  }

  @Test
  fun `queryStats and emptySystemTrash return empty when all files access is missing`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val storageRoot = tempFolder.newFolder("no_perm").toPath().toRealPath()

    val gateway = AndroidSystemTrashGateway(
      context = context,
      permissionChecker = FakePermissionChecker(granted = false),
      storageVolumeResolver = object : StorageVolumeResolver {
        override fun resolvePrimarySharedStorage(): StorageRootResolution =
          StorageRootResolution.Available(storageRoot, "Teste")
      },
      mediaScannerHook = MediaScannerHook { },
      scanLogger = object : ScanLogger {
        override fun logEvent(tag: String, message: String) = Unit
      },
      applicationId = "io.github.km20197.limpadroid",
    )

    val stats = gateway.queryStats()
    val purge = gateway.emptySystemTrash()

    assertEquals(0, stats.itemCount)
    assertEquals(0L, stats.totalBytes)
    assertEquals(0, purge.purgedCount)
    assertEquals(0L, purge.freedBytes)
  }

  private class FakePermissionChecker(private val granted: Boolean) : PermissionChecker {
    override fun hasAllFilesAccess(): Boolean = granted
    override fun hasNotificationPermission(): Boolean = true
    override fun createAllFilesAccessIntent(): Intent = Intent("test.ACTION")
  }

  private data class FakeRow(
    val id: Long,
    val size: Long,
    val path: String,
    val isTrashed: Int,
    val allowResolverDelete: Boolean,
  )

  private class FakeMediaProvider(
    private val rows: MutableList<FakeRow>,
    private val onDelete: (FakeRow) -> Unit,
  ) : ContentProvider() {
    var lastQueryTrashedFlag: Int? = null

    override fun onCreate(): Boolean = true

    @Suppress("DEPRECATION")
    override fun query(
      uri: Uri,
      projection: Array<out String>?,
      queryArgs: Bundle?,
      cancellationSignal: android.os.CancellationSignal?,
    ): Cursor {
      lastQueryTrashedFlag = queryArgs?.getInt(MediaStore.QUERY_ARG_MATCH_TRASHED, -1)
      val cols = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.IS_TRASHED,
      )
      val cursor = MatrixCursor(cols)
      for (row in rows) {
        cursor.addRow(arrayOf<Any>(row.id, row.size, row.path, row.isTrashed))
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
      val row = rows.firstOrNull { it.id == id } ?: return 0
      if (!row.allowResolverDelete) return 0
      onDelete(row)
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
