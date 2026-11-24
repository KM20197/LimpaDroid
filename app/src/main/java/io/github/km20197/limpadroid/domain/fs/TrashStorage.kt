package io.github.km20197.limpadroid.domain.fs

import io.github.km20197.limpadroid.domain.model.TrashMetadataPayload
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

data class ValidatedTrashPayload(
  val trashRoot: Path,
  val dayDir: Path,
  val entryDir: Path,
  val payloadPath: Path,
  val entryId: String,
)

class InsufficientStorageException(message: String) : IOException(message)

class TrashStorage(
  private val usableSpaceProvider: (Path) -> Long = { path -> defaultUsableSpaceBytes(path) },
  private val mediaStoreDeleteGateway: MediaStoreDeleteGateway = MediaStoreDeleteGateway.NoOp,
) {
  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }

  fun resolveTrashRoot(storageRoot: Path): Path =
    storageRoot.resolve("Documents").resolve("LimpaDroid").resolve(TRASH_DIR_NAME)

  fun allocateEntryDir(trashRoot: Path, entryId: String, deletedAtMillis: Long): Path {
    val sanitizedId = entryId.trim()
    require(ENTRY_ID_REGEX.matches(sanitizedId) && sanitizedId != "." && sanitizedId != "..") {
      "Identificador inválido para entrada da lixeira: $entryId"
    }
    val dayFolder = DAY_FORMATTER.format(Instant.ofEpochMilli(deletedAtMillis))
    return trashRoot.resolve(dayFolder).resolve(sanitizedId)
  }

  fun resolvePayloadPath(entryDir: Path): Path = entryDir.resolve(PAYLOAD_NAME)

  fun resolveMetadataPath(entryDir: Path): Path = entryDir.resolve(METADATA_FILE_NAME)

  fun ensureTrashRootInitialized(trashRoot: Path) {
    if (Files.isSymbolicLink(trashRoot)) {
      throw SecurityException("Raiz da lixeira não pode ser um link simbólico: $trashRoot")
    }
    Files.createDirectories(trashRoot)
    val nomedia = trashRoot.resolve(".nomedia")
    if (!Files.exists(nomedia, LinkOption.NOFOLLOW_LINKS)) {
      Files.createFile(nomedia)
    }
  }

  /**
   * Garante que [candidatePayloadPath] aponta estritamente para `<trashRoot>/<yyyyMMdd>/<id>/payload`
   * sem segmentos `..` nem links simbólicos ao longo do caminho.
   */
  fun validatePayloadPath(trashRoot: Path, candidatePayloadPath: Path): ValidatedTrashPayload {
    if (Files.isSymbolicLink(trashRoot)) {
      throw SecurityException("Raiz da lixeira não pode ser link simbólico: $trashRoot")
    }
    if (!Files.exists(trashRoot, LinkOption.NOFOLLOW_LINKS) ||
      !Files.isDirectory(trashRoot, LinkOption.NOFOLLOW_LINKS)
    ) {
      throw SecurityException("Raiz da lixeira inexistente ou inválida: $trashRoot")
    }
    if (candidatePayloadPath.toString().isBlank() || containsParentTraversal(candidatePayloadPath)) {
      throw SecurityException("Caminho da lixeira contém travessia inválida: $candidatePayloadPath")
    }

    val canonicalRoot = trashRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
    val resolvedPayload = resolveCandidateAgainstRoot(canonicalRoot, candidatePayloadPath)
      ?: throw SecurityException("Não foi possível resolver o caminho da lixeira: $candidatePayloadPath")

    if (!resolvedPayload.startsWith(canonicalRoot)) {
      throw SecurityException("Caminho fora da raiz da lixeira: $candidatePayloadPath")
    }

    val relative = canonicalRoot.relativize(resolvedPayload)
    if (relative.nameCount != 3) {
      throw SecurityException(
        "Estrutura inválida na lixeira (esperado <yyyyMMdd>/<id>/payload): $candidatePayloadPath",
      )
    }

    val dayName = relative.getName(0).toString()
    val entryId = relative.getName(1).toString()
    val payloadName = relative.getName(2).toString()

    if (!DAY_DIR_REGEX.matches(dayName)) {
      throw SecurityException("Pasta diária inválida na lixeira: $dayName")
    }
    if (!ENTRY_ID_REGEX.matches(entryId) || entryId == "." || entryId == "..") {
      throw SecurityException("Identificador inválido na estrutura da lixeira: $entryId")
    }
    if (payloadName != PAYLOAD_NAME) {
      throw SecurityException("Arquivo alvo inválido na estrutura da lixeira: $payloadName")
    }

    val dayDir = canonicalRoot.resolve(dayName)
    val entryDir = dayDir.resolve(entryId)
    val expectedPayload = entryDir.resolve(PAYLOAD_NAME)

    if (Files.isSymbolicLink(dayDir) || Files.isSymbolicLink(entryDir) || Files.isSymbolicLink(expectedPayload)) {
      throw SecurityException("Link simbólico proibido dentro da lixeira: $expectedPayload")
    }
    if (resolvedPayload != expectedPayload) {
      throw SecurityException("Caminho canônico diverge da estrutura da lixeira: $resolvedPayload")
    }

    return ValidatedTrashPayload(
      trashRoot = canonicalRoot,
      dayDir = dayDir,
      entryDir = entryDir,
      payloadPath = expectedPayload,
      entryId = entryId,
    )
  }

  suspend fun moveToTrash(
    source: Path,
    trashRoot: Path,
    entryDir: Path,
    metadataBuilder: (trashPath: Path, sizeBytes: Long, sha256: String?, mtime: Long) -> TrashMetadataPayload,
    precomputedSha256: String? = null,
    forceCopyFallback: Boolean = false,
  ): MovedPayloadInfo = withContext(Dispatchers.IO) {
    ensureTrashRootInitialized(trashRoot)
    Files.createDirectories(entryDir)
    val payloadDest = resolvePayloadPath(entryDir)
    val validatedDest = validatePayloadPath(trashRoot, payloadDest)
    val metadataDest = resolveMetadataPath(validatedDest.entryDir)

    val attrs = Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (attrs.isSymbolicLink) {
      throw IOException("Links simbólicos não podem ser movidos para a lixeira: $source")
    }
    val originalMtime = attrs.lastModifiedTime().toMillis()
    val isRegularFile = attrs.isRegularFile
    val sizeBytes = if (isRegularFile) attrs.size() else computeDirectorySize(source)

    val verifiedSha = movePayloadWithVerification(
      source = source,
      destination = validatedDest.payloadPath,
      isRegularFile = isRegularFile,
      expectedSizeBytes = sizeBytes,
      precomputedSha256 = precomputedSha256,
      originalMtime = originalMtime,
      allowOverwrite = false,
      forceCopyFallback = forceCopyFallback,
    )

    val payload = metadataBuilder(validatedDest.payloadPath, sizeBytes, verifiedSha, originalMtime)
    writeMetadata(metadataDest, payload)

    MovedPayloadInfo(
      trashPath = validatedDest.payloadPath,
      metadataPath = metadataDest,
      sizeBytes = sizeBytes,
      sha256 = verifiedSha,
      originalMtime = originalMtime,
    )
  }

  suspend fun restoreFromTrash(
    trashRoot: Path,
    payloadPath: Path,
    desiredOriginalPath: Path,
    originalMtime: Long,
    expectedSha256: String?,
    pathGuard: PathGuard,
    forceCopyFallback: Boolean = false,
    beforeMoveHookForTest: ((Path) -> Unit)? = null,
  ): RestoredPayloadInfo = withContext(Dispatchers.IO) {
    val validated = validatePayloadPath(trashRoot, payloadPath)
    if (!Files.exists(validated.payloadPath, LinkOption.NOFOLLOW_LINKS)) {
      throw IOException("Conteúdo ausente na lixeira: ${validated.payloadPath}")
    }

    val attrs = Files.readAttributes(validated.payloadPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    if (attrs.isSymbolicLink) {
      throw SecurityException("Link simbólico não pode ser restaurado: ${validated.payloadPath}")
    }
    val isRegularFile = attrs.isRegularFile
    val sizeBytes = if (isRegularFile) attrs.size() else computeDirectorySize(validated.payloadPath)

    var attempt = 0
    var restoredTarget: Path? = null
    while (attempt < MAX_RESTORE_ATTEMPTS) {
      val candidateTarget = resolveRestoreCandidate(desiredOriginalPath, attempt)
      if (Files.exists(candidateTarget, LinkOption.NOFOLLOW_LINKS)) {
        attempt += 1
        continue
      }
      when (val guardResult = pathGuard.validate(candidateTarget)) {
        is PathGuardResult.Allowed -> Unit
        is PathGuardResult.Rejected -> {
          throw SecurityException("Destino de restauração bloqueado (${guardResult.reason}): ${guardResult.detail}")
        }
      }
      candidateTarget.parent?.let { Files.createDirectories(it) }
      beforeMoveHookForTest?.invoke(candidateTarget)

      try {
        movePayloadWithVerification(
          source = validated.payloadPath,
          destination = candidateTarget,
          isRegularFile = isRegularFile,
          expectedSizeBytes = sizeBytes,
          precomputedSha256 = expectedSha256,
          originalMtime = originalMtime,
          allowOverwrite = false,
          forceCopyFallback = forceCopyFallback,
        )
        restoredTarget = candidateTarget
        break
      } catch (_: FileAlreadyExistsException) {
        attempt += 1
      }
    }

    val targetPath = restoredTarget
      ?: throw IOException("Não foi possível alocar nome disponível para restauração após $MAX_RESTORE_ATTEMPTS tentativas.")

    runCatching {
      Files.setLastModifiedTime(targetPath, FileTime.fromMillis(originalMtime))
    }

    deleteRecursively(validated.entryDir)
    pruneEmptyDateDir(validated.dayDir)

    RestoredPayloadInfo(
      restoredPath = targetPath,
      sizeBytes = sizeBytes,
    )
  }

  /**
   * Verifica se [filePath] (ou [originalPath], caso não exista mais em disco) possui um MediaStore URI
   * associado e invoca a exclusão definitiva via ContentResolver antes da limpeza local em disco.
   */
  suspend fun deleteMediaStoreUriIfPresent(
    filePath: Path,
    originalPath: Path? = null,
  ): Boolean = withContext(Dispatchers.IO) {
    var deletedViaMediaStore = false
    if (Files.exists(filePath, LinkOption.NOFOLLOW_LINKS) &&
      !Files.isSymbolicLink(filePath) &&
      Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)
    ) {
      if (mediaStoreDeleteGateway.findAssociatedUri(filePath) != null &&
        mediaStoreDeleteGateway.deleteByAssociatedUri(filePath)
      ) {
        deletedViaMediaStore = true
      }
    }
    if (originalPath != null &&
      !Files.exists(originalPath, LinkOption.NOFOLLOW_LINKS) &&
      mediaStoreDeleteGateway.findAssociatedUri(originalPath) != null &&
      mediaStoreDeleteGateway.deleteByAssociatedUri(originalPath)
    ) {
      deletedViaMediaStore = true
    }
    deletedViaMediaStore
  }

  suspend fun deleteTrashedPayload(
    trashRoot: Path,
    trashPath: Path,
    originalPath: Path? = null,
  ) = withContext(Dispatchers.IO) {
    val validated = validatePayloadPath(trashRoot, trashPath)
    val payload = validated.payloadPath
    if (Files.isRegularFile(payload, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(payload)) {
      deleteMediaStoreUriIfPresent(filePath = payload, originalPath = originalPath)
    } else if (Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(payload)) {
      val files = collectRegularFiles(payload)
      for (file in files) {
        currentCoroutineContext().ensureActive()
        deleteMediaStoreUriIfPresent(filePath = file)
      }
      if (originalPath != null &&
        !Files.exists(originalPath, LinkOption.NOFOLLOW_LINKS) &&
        mediaStoreDeleteGateway.findAssociatedUri(originalPath) != null
      ) {
        mediaStoreDeleteGateway.deleteByAssociatedUri(originalPath)
      }
    } else if (originalPath != null &&
      !Files.exists(originalPath, LinkOption.NOFOLLOW_LINKS) &&
      mediaStoreDeleteGateway.findAssociatedUri(originalPath) != null
    ) {
      mediaStoreDeleteGateway.deleteByAssociatedUri(originalPath)
    }
    deleteRecursively(validated.entryDir)
    pruneEmptyDateDir(validated.dayDir)
  }

  private fun collectRegularFiles(dir: Path): List<Path> {
    val files = mutableListOf<Path>()
    runCatching {
      Files.walkFileTree(
        dir,
        object : SimpleFileVisitor<Path>() {
          override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (Files.isSymbolicLink(d)) return FileVisitResult.SKIP_SUBTREE
            return FileVisitResult.CONTINUE
          }

          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile && !attrs.isSymbolicLink && !Files.isSymbolicLink(file)) {
              files.add(file)
            }
            return FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
        },
      )
    }
    return files
  }

  fun writeMetadata(metadataPath: Path, payload: TrashMetadataPayload) {
    metadataPath.parent?.let { Files.createDirectories(it) }
    Files.write(metadataPath, json.encodeToString(payload).toByteArray(Charsets.UTF_8))
  }

  fun readMetadata(metadataPath: Path): TrashMetadataPayload? = runCatching {
    if (Files.isSymbolicLink(metadataPath) || !Files.isRegularFile(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
      return@runCatching null
    }
    val content = Files.readAllBytes(metadataPath).toString(Charsets.UTF_8)
    json.decodeFromString<TrashMetadataPayload>(content)
  }.getOrNull()

  suspend fun scanRecoverableEntries(trashRoot: Path): List<TrashMetadataPayload> = withContext(Dispatchers.IO) {
    if (!Files.exists(trashRoot, LinkOption.NOFOLLOW_LINKS) ||
      Files.isSymbolicLink(trashRoot) ||
      !Files.isDirectory(trashRoot, LinkOption.NOFOLLOW_LINKS)
    ) {
      return@withContext emptyList()
    }
    val canonicalRoot = runCatching { trashRoot.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
      ?: return@withContext emptyList()
    val recovered = mutableListOf<TrashMetadataPayload>()
    try {
      Files.newDirectoryStream(canonicalRoot).use { dayStream ->
        val dayIterator = dayStream.iterator()
        while (true) {
          val dayDir = try {
            if (!dayIterator.hasNext()) break
            dayIterator.next()
          } catch (_: java.nio.file.DirectoryIteratorException) {
            break
          }
          val dayName = dayDir.fileName?.toString().orEmpty()
          if (!DAY_DIR_REGEX.matches(dayName) ||
            Files.isSymbolicLink(dayDir) ||
            !Files.isDirectory(dayDir, LinkOption.NOFOLLOW_LINKS)
          ) {
            continue
          }
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
                val entryDirName = entryDir.fileName?.toString().orEmpty()
                if (!ENTRY_ID_REGEX.matches(entryDirName) ||
                  entryDirName == "." ||
                  entryDirName == ".." ||
                  Files.isSymbolicLink(entryDir) ||
                  !Files.isDirectory(entryDir, LinkOption.NOFOLLOW_LINKS)
                ) {
                  continue
                }
                val metadataFile = resolveMetadataPath(entryDir)
                val parsed = readMetadata(metadataFile) ?: continue
                if (parsed.id != entryDirName) continue

                val derivedPayload = resolvePayloadPath(entryDir)
                if (Files.isSymbolicLink(derivedPayload) ||
                  !Files.exists(derivedPayload, LinkOption.NOFOLLOW_LINKS)
                ) {
                  continue
                }
                val validated = runCatching {
                  validatePayloadPath(canonicalRoot, derivedPayload)
                }.getOrNull() ?: continue

                recovered.add(parsed.copy(trashPath = validated.payloadPath.toString()))
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
    var attempt = 0
    while (true) {
      val candidate = resolveRestoreCandidate(desiredPath, attempt)
      if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
        return candidate
      }
      attempt += 1
    }
  }

  private fun resolveRestoreCandidate(desiredPath: Path, attempt: Int): Path {
    if (attempt <= 0) return desiredPath
    val parent = desiredPath.parent
    val fileName = desiredPath.fileName?.toString() ?: "arquivo"
    val dotIndex = fileName.lastIndexOf('.')
    val hasExtension = dotIndex > 0 && dotIndex < fileName.length - 1
    val baseName = if (hasExtension) fileName.substring(0, dotIndex) else fileName
    val extension = if (hasExtension) fileName.substring(dotIndex) else ""
    val suffix = if (attempt == 1) " (restaurado)" else " (restaurado $attempt)"
    val candidateName = "$baseName$suffix$extension"
    return if (parent != null) parent.resolve(candidateName) else Paths.get(candidateName)
  }

  private suspend fun movePayloadWithVerification(
    source: Path,
    destination: Path,
    isRegularFile: Boolean,
    expectedSizeBytes: Long,
    precomputedSha256: String?,
    originalMtime: Long,
    allowOverwrite: Boolean,
    forceCopyFallback: Boolean,
  ): String? {
    if (!forceCopyFallback) {
      if (allowOverwrite) {
        try {
          Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
          return precomputedSha256
        } catch (_: AtomicMoveNotSupportedException) {
          // Fallback para cópia + verificação
        } catch (_: IOException) {
          // Fallback para cópia + verificação caso partições difiram
        }
      } else {
        // Sem ATOMIC_MOVE nem REPLACE_EXISTING evita sobrescrever arquivo criado concorrentemente no destino
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
          throw FileAlreadyExistsException(destination.toString())
        }
        try {
          Files.move(source, destination)
          return precomputedSha256
        } catch (faee: FileAlreadyExistsException) {
          throw faee
        } catch (_: IOException) {
          if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw FileAlreadyExistsException(destination.toString())
          }
          // Fallback para cópia + verificação caso partições difiram
        }
      }
    }

    val destParent = destination.parent ?: destination
    Files.createDirectories(destParent)
    val usableBytes = runCatching { usableSpaceProvider(destParent) }.getOrDefault(0L)
    val requiredBytes = if (expectedSizeBytes > Long.MAX_VALUE - COPY_SAFETY_MARGIN_MIN_BYTES) {
      Long.MAX_VALUE
    } else {
      expectedSizeBytes + COPY_SAFETY_MARGIN_MIN_BYTES
    }
    if (usableBytes < requiredBytes) {
      throw InsufficientStorageException(
        "Espaço livre insuficiente para mover com segurança (${usableBytes} B disponíveis, ${requiredBytes} B necessários com margem de 100 MiB).",
      )
    }

    val sourceSha256 = if (isRegularFile) {
      precomputedSha256 ?: Hasher.sha256(source, Dispatchers.IO)
    } else {
      null
    }

    var destinationCreated = false
    try {
      if (isRegularFile) {
        if (allowOverwrite) {
          destinationCreated = true
          Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        } else {
          // Sem REPLACE_EXISTING abre o arquivo com CREATE_NEW no nível do sistema operacional
          Files.copy(source, destination)
          destinationCreated = true
        }
        val copiedSize = Files.size(destination)
        if (copiedSize != expectedSizeBytes) {
          throw IOException("Falha de verificação de tamanho na cópia ($copiedSize != $expectedSizeBytes)")
        }
        if (sourceSha256 != null) {
          val copiedSha = Hasher.sha256(destination, Dispatchers.IO)
          if (!copiedSha.equals(sourceSha256, ignoreCase = true)) {
            throw IOException("Falha de verificação SHA-256 na cópia")
          }
        }
        runCatching {
          Files.setLastModifiedTime(destination, FileTime.fromMillis(originalMtime))
        }
        Files.delete(source)
        return sourceSha256
      } else {
        if (!allowOverwrite && Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
          throw FileAlreadyExistsException(destination.toString())
        }
        if (!allowOverwrite) {
          Files.createDirectory(destination)
          destinationCreated = true
        } else {
          destinationCreated = true
        }
        copyDirectoryRecursively(source, destination)
        val copiedSize = computeDirectorySize(destination)
        if (copiedSize != expectedSizeBytes) {
          throw IOException("Falha de verificação de tamanho do diretório ($copiedSize != $expectedSizeBytes)")
        }
        deleteRecursively(source)
        return null
      }
    } catch (ce: CancellationException) {
      if (destinationCreated && Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
        runCatching { deleteRecursively(destination) }
      }
      throw ce
    } catch (t: Throwable) {
      if (destinationCreated && Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
        runCatching { deleteRecursively(destination) }
      }
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

  private fun containsParentTraversal(path: Path): Boolean {
    for (segment in path) {
      if (segment.toString() == "..") return true
    }
    return false
  }

  private fun resolveCandidateAgainstRoot(canonicalRoot: Path, candidatePath: Path): Path? = runCatching {
    val abs = if (candidatePath.isAbsolute) candidatePath else canonicalRoot.resolve(candidatePath)
    if (Files.exists(abs, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(abs)) return@runCatching null
      val parent = abs.parent ?: return@runCatching null
      val parentReal = if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(parent)) return@runCatching null
        parent.toRealPath(LinkOption.NOFOLLOW_LINKS)
      } else {
        parent.normalize()
      }
      parentReal.resolve(abs.fileName)
    } else {
      val missingParts = ArrayDeque<String>()
      var current: Path? = abs.normalize()
      while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        current.fileName?.toString()?.let { missingParts.addFirst(it) }
        current = current.parent
      }
      val existingBase = current ?: return@runCatching null
      if (Files.isSymbolicLink(existingBase)) return@runCatching null
      val realBase = existingBase.toRealPath(LinkOption.NOFOLLOW_LINKS)
      missingParts.fold(realBase) { acc, part -> acc.resolve(part) }.normalize()
    }
  }.getOrNull()

  companion object {
    const val TRASH_DIR_NAME = ".lixeira"
    const val PAYLOAD_NAME = "payload"
    const val METADATA_FILE_NAME = "metadata.json"

    private const val MAX_RESTORE_ATTEMPTS = 10_000
    const val COPY_SAFETY_MARGIN_MIN_BYTES = 100L * 1024L * 1024L
    private val DAY_DIR_REGEX = Regex("^\\d{8}$")
    private val ENTRY_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,128}$")

    private val DAY_FORMATTER: DateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    private fun defaultUsableSpaceBytes(path: Path): Long =
      runCatching { path.toFile().usableSpace }.getOrDefault(0L)
  }
}
