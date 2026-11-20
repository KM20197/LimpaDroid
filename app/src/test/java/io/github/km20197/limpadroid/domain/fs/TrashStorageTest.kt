package io.github.km20197.limpadroid.domain.fs

import io.github.km20197.limpadroid.domain.model.TrashMetadataPayload
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrashStorageTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var storageRoot: Path
  private lateinit var guard: DefaultPathGuard
  private val storage = TrashStorage()

  @Before
  fun setUp() {
    storageRoot = tempFolder.newFolder("shared_storage").toPath().toRealPath()
    guard = DefaultPathGuard(storageRoot, "io.github.km20197.limpadroid")
  }

  @Test
  fun `moveToTrash creates nomedia metadata json and supports atomic and fallback copy`() = runTest {
    val downloadDir = Files.createDirectories(storageRoot.resolve("Download"))
    val sourceFile = Files.writeString(downloadDir.resolve("relatorio.pdf"), "conteudo-limpadroid-123")
    val mtime = 1_700_000_000_000L
    Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(mtime))

    val trashRoot = storage.resolveTrashRoot(storageRoot)
    val entryDir = storage.allocateEntryDir(trashRoot, "entry-1", 1_700_000_100_000L)

    val moved = storage.moveToTrash(
      source = sourceFile,
      trashRoot = trashRoot,
      entryDir = entryDir,
      forceCopyFallback = true,
      metadataBuilder = { dest, size, sha, origMtime ->
        TrashMetadataPayload(
          id = "entry-1",
          originalPath = sourceFile.toString(),
          trashPath = dest.toString(),
          sizeBytes = size,
          sha256 = sha,
          originalMtime = origMtime,
          category = "SUPERFLUOUS",
          ruleId = "TEST",
          deletedAt = 1_700_000_100_000L,
          expiresAt = 1_702_592_100_000L,
        )
      },
    )

    assertFalse(Files.exists(sourceFile, LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.exists(trashRoot.resolve(".nomedia"), LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.exists(moved.trashPath, LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.exists(moved.metadataPath, LinkOption.NOFOLLOW_LINKS))
    assertEquals(23L, moved.sizeBytes)
    assertEquals(mtime, moved.originalMtime)

    val recovered = storage.scanRecoverableEntries(trashRoot)
    assertEquals(1, recovered.size)
    assertEquals("entry-1", recovered.single().id)
  }

  @Test
  fun `restoreFromTrash resolves naming conflict and preserves original mtime`() = runTest {
    val pictures = Files.createDirectories(storageRoot.resolve("Pictures"))
    val originalPath = Files.writeString(pictures.resolve("foto.jpg"), "dados-da-foto")
    val originalMtime = 1_650_000_000_000L
    Files.setLastModifiedTime(originalPath, FileTime.fromMillis(originalMtime))

    val trashRoot = storage.resolveTrashRoot(storageRoot)
    val entryDir = storage.allocateEntryDir(trashRoot, "entry-2", 1_700_000_000_000L)

    val moved = storage.moveToTrash(
      source = originalPath,
      trashRoot = trashRoot,
      entryDir = entryDir,
      metadataBuilder = { dest, size, sha, mtime ->
        TrashMetadataPayload(
          id = "entry-2",
          originalPath = originalPath.toString(),
          trashPath = dest.toString(),
          sizeBytes = size,
          sha256 = sha,
          originalMtime = mtime,
          category = "WHATSAPP",
          ruleId = "WA",
          deletedAt = 1_700_000_000_000L,
          expiresAt = 1_702_592_000_000L,
        )
      },
    )

    // Ocupa o caminho original antes da restauração
    Files.writeString(originalPath, "arquivo-novo-no-lugar")

    val restored = storage.restoreFromTrash(
      payloadPath = moved.trashPath,
      desiredOriginalPath = originalPath,
      originalMtime = originalMtime,
      expectedSha256 = moved.sha256,
      pathGuard = guard,
    )

    assertEquals(pictures.resolve("foto (restaurado).jpg"), restored.restoredPath)
    assertEquals("dados-da-foto", Files.readString(restored.restoredPath))
    assertEquals(
      originalMtime,
      Files.getLastModifiedTime(restored.restoredPath, LinkOption.NOFOLLOW_LINKS).toMillis(),
    )
    assertFalse(Files.exists(entryDir, LinkOption.NOFOLLOW_LINKS))
  }
}
