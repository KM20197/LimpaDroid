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
      trashRoot = trashRoot,
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

  @Test
  fun `deleteTrashedPayload and restoreFromTrash reject tampered paths outside trashRoot`() = runTest {
    val dcimCamera = Files.createDirectories(storageRoot.resolve("DCIM/Camera"))
    val victim = Files.writeString(dcimCamera.resolve("familia.jpg"), "preservado")
    val sibling = Files.writeString(dcimCamera.resolve("outra.jpg"), "tambem-preservado")
    val trashRoot = storage.resolveTrashRoot(storageRoot)
    storage.ensureTrashRootInitialized(trashRoot)

    var deleteRejected = false
    try {
      storage.deleteTrashedPayload(trashRoot, victim)
    } catch (_: SecurityException) {
      deleteRejected = true
    }
    assertTrue(deleteRejected)
    assertTrue(Files.exists(victim, LinkOption.NOFOLLOW_LINKS))
    assertTrue(Files.exists(sibling, LinkOption.NOFOLLOW_LINKS))

    val traversalPath = trashRoot.resolve("20261117/id-1/../../../DCIM/Camera/familia.jpg")
    var traversalRejected = false
    try {
      storage.deleteTrashedPayload(trashRoot, traversalPath)
    } catch (_: SecurityException) {
      traversalRejected = true
    }
    assertTrue(traversalRejected)
    assertTrue(Files.exists(dcimCamera, LinkOption.NOFOLLOW_LINKS))

    var restoreRejected = false
    try {
      storage.restoreFromTrash(
        trashRoot = trashRoot,
        payloadPath = victim,
        desiredOriginalPath = storageRoot.resolve("Download/x.jpg"),
        originalMtime = 100L,
        expectedSha256 = null,
        pathGuard = guard,
      )
    } catch (_: SecurityException) {
      restoreRejected = true
    }
    assertTrue(restoreRejected)
    assertTrue(Files.exists(victim, LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `scanRecoverableEntries ignores tampered metadata trashPath and validates id matching directory`() = runTest {
    val dcimCamera = Files.createDirectories(storageRoot.resolve("DCIM/Camera"))
    val externalVictim = Files.writeString(dcimCamera.resolve("x.jpg"), "nao-tocar")
    val trashRoot = storage.resolveTrashRoot(storageRoot)
    storage.ensureTrashRootInitialized(trashRoot)

    val validDir = storage.allocateEntryDir(trashRoot, "entry-safe", 1_700_000_000_000L)
    Files.createDirectories(validDir)
    val expectedPayload = Files.writeString(storage.resolvePayloadPath(validDir), "conteudo-real")
    storage.writeMetadata(
      storage.resolveMetadataPath(validDir),
      TrashMetadataPayload(
        id = "entry-safe",
        originalPath = storageRoot.resolve("Download/real.txt").toString(),
        trashPath = externalVictim.toString(),
        sizeBytes = 13L,
        sha256 = null,
        originalMtime = 100L,
        category = "SUPERFLUOUS",
        ruleId = "TEST",
        deletedAt = 1_700_000_000_000L,
        expiresAt = 1_702_592_000_000L,
      ),
    )

    val mismatchedDir = storage.allocateEntryDir(trashRoot, "dir-name", 1_700_000_000_000L)
    Files.createDirectories(mismatchedDir)
    Files.writeString(storage.resolvePayloadPath(mismatchedDir), "conteudo-falso")
    storage.writeMetadata(
      storage.resolveMetadataPath(mismatchedDir),
      TrashMetadataPayload(
        id = "other-id",
        originalPath = storageRoot.resolve("Download/bad.txt").toString(),
        trashPath = externalVictim.toString(),
        sizeBytes = 14L,
        sha256 = null,
        originalMtime = 100L,
        category = "SUPERFLUOUS",
        ruleId = "TEST",
        deletedAt = 1_700_000_000_000L,
        expiresAt = 1_702_592_000_000L,
      ),
    )

    val recovered = storage.scanRecoverableEntries(trashRoot)
    assertEquals(1, recovered.size)
    assertEquals("entry-safe", recovered.single().id)
    assertEquals(expectedPayload.toRealPath(LinkOption.NOFOLLOW_LINKS).toString(), recovered.single().trashPath)
    assertTrue(Files.exists(externalVictim, LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `restoreFromTrash handles concurrent FileAlreadyExistsException without overwriting new file`() = runTest {
    val pictures = Files.createDirectories(storageRoot.resolve("Pictures"))
    val originalPath = Files.writeString(pictures.resolve("raca.jpg"), "conteudo-da-lixeira")
    val trashRoot = storage.resolveTrashRoot(storageRoot)
    val entryDir = storage.allocateEntryDir(trashRoot, "entry-race", 1_700_000_000_000L)

    val moved = storage.moveToTrash(
      source = originalPath,
      trashRoot = trashRoot,
      entryDir = entryDir,
      metadataBuilder = { dest, size, sha, mtime ->
        TrashMetadataPayload(
          id = "entry-race",
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

    // Simula processo externo criando raca.jpg entre a checagem e a movimentação
    var injected = false
    val restored = storage.restoreFromTrash(
      trashRoot = trashRoot,
      payloadPath = moved.trashPath,
      desiredOriginalPath = originalPath,
      originalMtime = moved.originalMtime,
      expectedSha256 = moved.sha256,
      pathGuard = guard,
      forceCopyFallback = true,
      beforeMoveHookForTest = { candidate ->
        if (!injected && candidate == originalPath) {
          injected = true
          Files.writeString(candidate, "arquivo-concorrente-intacto")
        }
      },
    )

    assertEquals(pictures.resolve("raca (restaurado).jpg"), restored.restoredPath)
    assertEquals("arquivo-concorrente-intacto", Files.readString(originalPath))
    assertEquals("conteudo-da-lixeira", Files.readString(restored.restoredPath))
  }

  @Test
  fun `moveToTrash throws InsufficientStorageException before copy fallback when space is too low`() = runTest {
    val lowSpaceStorage = TrashStorage(usableSpaceProvider = { 4L })
    val dlDir = Files.createDirectories(storageRoot.resolve("Download"))
    val sourceFile = Files.writeString(dlDir.resolve("grande.bin"), "conteudo-maior-que-4-bytes")
    val trashRoot = lowSpaceStorage.resolveTrashRoot(storageRoot)
    val entryDir = lowSpaceStorage.allocateEntryDir(trashRoot, "entry-lowspace", 1_700_000_000_000L)

    var threwLowSpace = false
    try {
      lowSpaceStorage.moveToTrash(
        source = sourceFile,
        trashRoot = trashRoot,
        entryDir = entryDir,
        forceCopyFallback = true,
        metadataBuilder = { dest, size, sha, mtime ->
          TrashMetadataPayload(
            id = "entry-lowspace",
            originalPath = sourceFile.toString(),
            trashPath = dest.toString(),
            sizeBytes = size,
            sha256 = sha,
            originalMtime = mtime,
            category = "SUPERFLUOUS",
            ruleId = "TEST",
            deletedAt = 1_700_000_000_000L,
            expiresAt = 1_702_592_000_000L,
          )
        },
      )
    } catch (_: InsufficientStorageException) {
      threwLowSpace = true
    }

    assertTrue(threwLowSpace)
    assertTrue(Files.exists(sourceFile, LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(lowSpaceStorage.resolvePayloadPath(entryDir), LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `moveToTrash throws InsufficientStorageException when usableSpaceProvider fails or lacks safety margin`() = runTest {
    val dlDir = Files.createDirectories(storageRoot.resolve("Download"))
    val content = "conteudo-para-teste-margem"
    val fileSize = content.toByteArray(Charsets.UTF_8).size.toLong()

    // 1) Falha ao consultar espaço livre deve falhar de modo seguro (0 B), sem desativar a checagem
    val failingSpaceStorage = TrashStorage(usableSpaceProvider = { error("statvfs indisponível") })
    val source1 = Files.writeString(dlDir.resolve("falha_espaco.bin"), content)
    val trashRoot1 = failingSpaceStorage.resolveTrashRoot(storageRoot)
    val entryDir1 = failingSpaceStorage.allocateEntryDir(trashRoot1, "entry-space-err", 1_700_000_000_000L)

    var threwOnError = false
    try {
      failingSpaceStorage.moveToTrash(
        source = source1,
        trashRoot = trashRoot1,
        entryDir = entryDir1,
        forceCopyFallback = true,
        metadataBuilder = { dest, size, sha, mtime ->
          TrashMetadataPayload(
            id = "entry-space-err",
            originalPath = source1.toString(),
            trashPath = dest.toString(),
            sizeBytes = size,
            sha256 = sha,
            originalMtime = mtime,
            category = "SUPERFLUOUS",
            ruleId = "TEST",
            deletedAt = 1_700_000_000_000L,
            expiresAt = 1_702_592_000_000L,
          )
        },
      )
    } catch (_: InsufficientStorageException) {
      threwOnError = true
    }
    assertTrue(threwOnError)
    assertTrue(Files.exists(source1, LinkOption.NOFOLLOW_LINKS))

    // 2) Espaço exato igual ao tamanho do arquivo sem margem de segurança também deve ser recusado
    val exactSpaceStorage = TrashStorage(usableSpaceProvider = { fileSize })
    val source2 = Files.writeString(dlDir.resolve("sem_margem.bin"), content)
    val trashRoot2 = exactSpaceStorage.resolveTrashRoot(storageRoot)
    val entryDir2 = exactSpaceStorage.allocateEntryDir(trashRoot2, "entry-no-margin", 1_700_000_000_000L)

    var threwWithoutMargin = false
    try {
      exactSpaceStorage.moveToTrash(
        source = source2,
        trashRoot = trashRoot2,
        entryDir = entryDir2,
        forceCopyFallback = true,
        metadataBuilder = { dest, size, sha, mtime ->
          TrashMetadataPayload(
            id = "entry-no-margin",
            originalPath = source2.toString(),
            trashPath = dest.toString(),
            sizeBytes = size,
            sha256 = sha,
            originalMtime = mtime,
            category = "SUPERFLUOUS",
            ruleId = "TEST",
            deletedAt = 1_700_000_000_000L,
            expiresAt = 1_702_592_000_000L,
          )
        },
      )
    } catch (_: InsufficientStorageException) {
      threwWithoutMargin = true
    }
    assertTrue(threwWithoutMargin)
    assertTrue(Files.exists(source2, LinkOption.NOFOLLOW_LINKS))
  }
}
