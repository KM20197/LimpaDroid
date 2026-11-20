package io.github.km20197.limpadroid.domain.fs

import io.github.km20197.limpadroid.domain.model.TrashMetadataPayload
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class MovedPayloadInfo(
  val trashPath: Path,
  val metadataPath: Path,
  val sizeBytes: Long,
  val sha256: String?,
  val originalMtime: Long,
)

data class RestoredPayloadInfo(
  val restoredPath: Path,
  val sizeBytes: Long,
)

class TrashStorage {
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }

  fun resolveTrashRoot(storageRoot: Path): Path =
    storageRoot.resolve("Documents").resolve("LimpaDroid").resolve(TRASH_DIR_NAME)

  fun allocateEntryDir(trashRoot: Path, entryId: String, deletedAtMillis: Long): Path {
    val dayFolder = DAY_FORMATTER.format(Instant.ofEpochMilli(deletedAtMillis))
    return trashRoot.resolve(dayFolder).resolve(entryId)
  }

  fun resolvePayloadPath(entryDir: Path): Path = entryDir.resolve(PAYLOAD_NAME)

  fun resolveMetadataPath(entryDir: Path): Path = entryDir.resolve(METADATA_FILE_NAME)

  fun ensureTrashRootInitialized(trashRoot: Path) {
    Files.createDirectories(trashRoot)
    val nomedia = trashRoot.resolve(".nomedia")
    if (!Files.exists(nomedia, LinkOption.NOFOLLOW_LINKS)) {
      Files.createFile(nomedia)
    }
  }

  suspend fun moveToTrash(
    source: Path,
    trashRoot: Path,
    entryDir: Path,
    metadataBuilder: (trashPath: Path, sizeBytes: Long, sha256: String?, mtime: Long) -> TrashMetadataPayload,
    forceCopyFallback: Boolean = false,
  ): MovedPayloadInfo = withContext(Dispatchers.IO) {
    ensureTrashRootInitialized(trashRoot)
    Files.createDirectories(entryDir)
    val payloadDest = resolvePayloadPath(entryDir)
    val metadataDest = resolveMetadataPath(entryDir)

    val attrs = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (attrs.isSymbolicLink) {
      throw IOException("Links simbólicos não podem ser movidos para a lixeira: $source")
    }
    val originalMtime = attrs.lastModifiedTime().toMillis()
    val isRegularFile = attrs.isRegularFile
    val sizeBytes = if (isRegularFile) attrs.size() else computeDirectorySize(source)
    val sha256 = if (isRegularFile) Hasher.sha256(source, Dispatchers.IO) else null

    movePayloadWithVerification(
      source = source,
      destination = payloadDest,
      isRegularFile = isRegularFile,
      expectedSizeBytes = sizeBytes,
      expectedSha256 = sha256,
      originalMtime = originalMtime,
      forceCopyFallback = forceCopyFallback,
    )

    val payload = metadataBuilder(payloadDest, sizeBytes, sha256, originalMtime)
    writeMetadata(metadataDest, payload)

    MovedPayloadInfo(
      trashPath = payloadDest,
      metadataPath = metadataDest,
      sizeBytes = sizeBytes,
      sha256 = sha256,
      originalMtime = originalMtime,
    )
  }

  suspend fun restoreFromTrash(
    payloadPath: Path,
    desiredOriginalPath: Path,
    originalMtime: Long,
    expectedSha256: String?,
    pathGuard: PathGuard,
    forceCopyFallback: Boolean = false,
  ): RestoredPayloadInfo = withContext(Dispatchers.IO) {
    if (!Files.exists(payloadPath, LinkOption.NOFOLLOW_LINKS)) {
      throw IOException("Conteúdo ausente na lixeira: $payloadPath")
    }

    val targetPath = resolveNonConflictingRestorePath(desiredOriginalPath)
    when (val guardResult = pathGuard.validate(targetPath)) {
      is PathGuardResult.Allowed -> Unit
      is PathGuardResult.Rejected -> {
        throw SecurityException("Destino de restauração bloqueado (${guardResult.reason}): ${guardResult.detail}")
      }
    }

    targetPath.parent?.let { Files.createDirectories(it) }

    val attrs = Files.readAttributes(payloadPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    val isRegularFile = attrs.isRegularFile
    val sizeBytes = if (isRegularFile) attrs.size() else computeDirectorySize(payloadPath)
    val sha256 = if (isRegularFile) (expectedSha256 ?: Hasher.sha256(payloadPath, Dispatchers.IO)) else null

    movePayloadWithVerification(
      source = payloadPath,
      destination = targetPath,
      isRegularFile = isRegularFile,
      expectedSizeBytes = sizeBytes,
      expectedSha256 = sha256,
      originalMtime = originalMtime,
      forceCopyFallback = forceCopyFallback,
    )

    runCatching {
      Files.setLastModifiedTime(targetPath, FileTime.fromMillis(originalMtime))
    }

    val entryDir = payloadPath.parent
    if (entryDir != null) {
      deleteRecursively(entryDir)
      pruneEmptyDateDir(entryDir.parent)
    }

    RestoredPayloadInfo(
      restoredPath = targetPath,
      sizeBytes = sizeBytes,
    )
  }

  suspend fun deleteTrashedPayload(trashPath: Path) = withContext(Dispatchers.IO) {
    val entryDir = trashPath.parent
    if (entryDir != null && entryDir.fileName?.toString() != TRASH_DIR_NAME) {
      deleteRecursively(entryDir)
      pruneEmptyDateDir(entryDir.parent)
    } else if (Files.exists(trashPath, LinkOption.NOFOLLOW_LINKS)) {
      deleteRecursively(trashPath)
    }
  }

  fun writeMetadata(metadataPath: Path, payload: TrashMetadataPayload) {
    metadataPath.parent?.let { Files.createDirectories(it) }
    Files.write(metadataPath, json.encodeToString(payload).toByteArray(Charsets.UTF_8))
  }

  fun readMetadata(metadataPath: Path): TrashMetadataPayload? = runCatching {
    val content = Files.readAllBytes(metadataPath).toString(Charsets.UTF_8)
    json.decodeFromString<TrashMetadataPayload>(content)
  }.getOrNull()

  suspend fun scanRecoverableEntries(trashRoot: Path): List<TrashMetadataPayload> = withContext(Dispatchers.IO) {
    if (!Files.exists(trashRoot, LinkOption.NOFOLLOW_LINKS) ||
      !Files.isDirectory(trashRoot, LinkOption.NOFOLLOW_LINKS)
    ) {
      return@withContext emptyList()
    }
    val recovered = mutableListOf<TrashMetadataPayload>()
    try {
      Files.newDirectoryStream(trashRoot).use { dayStream ->
        val dayIterator = dayStream.iterator()
        while (true) {
          val dayDir = try {
            if (!dayIterator.hasNext()) break
            dayIterator.next()
          } catch (_: java.nio.file.DirectoryIteratorException) {
            break
          }
          if (!Files.isDirectory(dayDir, LinkOption.NOFOLLOW_LINKS)) continue
          try {
            Files.newDirectoryStream(dayDir).use { entryStream ->
              val entryIterator = entryStream.iterator()
              while (true) {
                val entryDir = try {
                  if (!entryIterator.hasNext()) break
                  entryIterator.next()
                } catch (_: java.nio.file.DirectoryIteratorException) {
                  break
                }
                if (!Files.isDirectory(entryDir, LinkOption.NOFOLLOW_LINKS)) continue
                val metadataFile = resolveMetadataPath(entryDir)
                if (!Files.isRegularFile(metadataFile, LinkOption.NOFOLLOW_LINKS)) continue
                val parsed = readMetadata(metadataFile) ?: continue
                val payloadFile = resolvePayloadPath(entryDir)
                val fallbackTrashFile = Paths.get(parsed.trashPath)
                val resolvedTrash = when {
                  Files.exists(payloadFile, LinkOption.NOFOLLOW_LINKS) -> payloadFile
                  Files.exists(fallbackTrashFile, LinkOption.NOFOLLOW_LINKS) -> fallbackTrashFile
                  else -> null
                } ?: continue
                recovered.add(parsed.copy(trashPath = resolvedTrash.toString()))
              }
            }
          } catch (_: java.nio.file.DirectoryIteratorException) {
            // Ignora diretório diário ilegível sem interromper os demais
          } catch (_: IOException) {
            // Ignora erro pontual de E/S ao ler dia da lixeira
          }
        }
      }
    } catch (_: java.nio.file.DirectoryIteratorException) {
      // Ignora erro na iteração da raiz da lixeira
    } catch (_: IOException) {
      // Ignora erro de E/S na raiz da lixeira
    }
    recovered
  }

  fun resolveNonConflictingRestorePath(desiredPath: Path): Path {
    if (!Files.exists(desiredPath, LinkOption.NOFOLLOW_LINKS)) {
      return desiredPath
    }
    val parent = desiredPath.parent
    val fileName = desiredPath.fileName?.toString() ?: "arquivo"
    val dotIndex = fileName.lastIndexOf('.')
    val hasExtension = dotIndex > 0 && dotIndex < fileName.length - 1
    val baseName = if (hasExtension) fileName.substring(0, dotIndex) else fileName
    val extension = if (hasExtension) fileName.substring(dotIndex) else ""

    var attempt = 1
    while (true) {
      val suffix = if (attempt == 1) " (restaurado)" else " (restaurado $attempt)"
      val candidateName = "$baseName$suffix$extension"
      val candidatePath = if (parent != null) parent.resolve(candidateName) else Paths.get(candidateName)
      if (!Files.exists(candidatePath, LinkOption.NOFOLLOW_LINKS)) {
        return candidatePath
      }
      attempt += 1
    }
  }

  private suspend fun movePayloadWithVerification(
    source: Path,
    destination: Path,
    isRegularFile: Boolean,
    expectedSizeBytes: Long,
    expectedSha256: String?,
    originalMtime: Long,
    forceCopyFallback: Boolean,
  ) {
    if (!forceCopyFallback) {
      try {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        return
      } catch (_: AtomicMoveNotSupportedException) {
        // Fallback para cópia + verificação
      } catch (_: IOException) {
        // Fallback para cópia + verificação caso partições difiram
      }
    }

    try {
      if (isRegularFile) {
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        val copiedSize = Files.size(destination)
        if (copiedSize != expectedSizeBytes) {
          throw IOException("Falha de verificação de tamanho na cópia ($copiedSize != $expectedSizeBytes)")
        }
        if (expectedSha256 != null) {
          val copiedSha = Hasher.sha256(destination, Dispatchers.IO)
          if (!copiedSha.equals(expectedSha256, ignoreCase = true)) {
            throw IOException("Falha de verificação SHA-256 na cópia")
          }
        }
        runCatching {
          Files.setLastModifiedTime(destination, FileTime.fromMillis(originalMtime))
        }
        Files.delete(source)
      } else {
        copyDirectoryRecursively(source, destination)
        val copiedSize = computeDirectorySize(destination)
        if (copiedSize != expectedSizeBytes) {
          throw IOException("Falha de verificação de tamanho do diretório ($copiedSize != $expectedSizeBytes)")
        }
        deleteRecursively(source)
      }
    } catch (t: Throwable) {
      runCatching { deleteRecursively(destination) }
      throw t
    }
  }

  private fun copyDirectoryRecursively(sourceDir: Path, targetDir: Path) {
    Files.walkFileTree(
      sourceDir,
      object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
          val rel = sourceDir.relativize(dir)
          Files.createDirectories(targetDir.resolve(rel))
          return FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (attrs.isSymbolicLink || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
          val rel = sourceDir.relativize(file)
          val destFile = targetDir.resolve(rel)
          Files.copy(file, destFile, StandardCopyOption.REPLACE_EXISTING)
          runCatching {
            Files.setLastModifiedTime(destFile, attrs.lastModifiedTime())
          }
          return FileVisitResult.CONTINUE
        }
      },
    )
  }

  private fun computeDirectorySize(dir: Path): Long {
    var total = 0L
    Files.walkFileTree(
      dir,
      object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          if (attrs.isRegularFile && !attrs.isSymbolicLink) {
            total += attrs.size()
          }
          return FileVisitResult.CONTINUE
        }
      },
    )
    return total
  }

  fun deleteRecursively(target: Path) {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return
    if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
      Files.deleteIfExists(target)
      return
    }
    Files.walkFileTree(
      target,
      object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          Files.deleteIfExists(file)
          return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
          Files.deleteIfExists(dir)
          return FileVisitResult.CONTINUE
        }
      },
    )
  }

  private fun pruneEmptyDateDir(dateDir: Path?) {
    if (dateDir == null || !Files.isDirectory(dateDir, LinkOption.NOFOLLOW_LINKS)) return
    if (dateDir.fileName?.toString() == TRASH_DIR_NAME) return
    val isEmpty = runCatching {
      Files.newDirectoryStream(dateDir).use { stream -> !stream.iterator().hasNext() }
    }.getOrDefault(false)
    if (isEmpty) {
      runCatching { Files.deleteIfExists(dateDir) }
    }
  }

  companion object {
    const val TRASH_DIR_NAME = ".lixeira"
    const val PAYLOAD_NAME = "payload"
    const val METADATA_FILE_NAME = "metadata.json"

    private val DAY_FORMATTER: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)
  }
}
