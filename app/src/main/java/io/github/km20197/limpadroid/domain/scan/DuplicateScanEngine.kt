package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.Hasher
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateFileEntry
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateKeepPolicy
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateRuleId
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

class DuplicateScanEngine(
  private val hashCacheRepository: HashCacheRepository? = null,
  private val minSizeBytesProvider: suspend () -> Long = { DuplicateKeepPolicy.DEFAULT_MIN_SIZE_BYTES },
  private val priorityFoldersProvider: suspend () -> List<String> = { DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS },
  private val clock: AppClock = AppClock.System,
  private val hashDispatcher: CoroutineDispatcher = Hasher.createLimitedIoDispatcher(Hasher.MAX_HASH_PARALLELISM),
  private val walkDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val readPermissionChecker: (Path) -> Boolean = Files::isReadable,
) : ScanEngine {
  override val scope: ScanScope = ScanScope.DUPLICATE
  override val category: Category = Category.DUPLICATE

  override fun scan(
    config: ScanConfig,
    latestCheckpointRef: AtomicReference<WalkCheckpoint>?,
  ): Flow<ScanEvent> = flow {
    val startNanos = System.nanoTime()
    emit(ScanEvent.Started(sessionId = config.sessionId, resumed = config.resumed))

    val minSizeBytes = minSizeBytesProvider().coerceAtLeast(1L)
    val priorityFolders = priorityFoldersProvider()
    val storageRoot = config.pathGuard.storageRoot
    val flowJob = currentCoroutineContext().job

    var scannedFiles = 0L
    var scannedDirs = 0L
    var scannedBytes = 0L
    var errorCount = config.initialErrorCount
    var lastProcessedPath: String? = null
    var lastProgressMillis = System.currentTimeMillis()

    val walkErrors = mutableListOf<WalkError>()
    // M4 Estágio 1A: primeira passagem guarda somente contadores leves por tamanho (sem alocar entradas individuais)
    val sizeCounts = HashMap<Long, Byte>()

    suspend fun emitError(path: String, throwable: Throwable) {
      errorCount += 1
      emit(
        ScanEvent.ErrorLogged(
          WalkError(
            path = path,
            errorType = throwable.javaClass.simpleName.ifBlank { "IOException" },
            message = throwable.message ?: "Erro ao analisar duplicados",
          ),
        ),
      )
    }

    suspend fun emitProgress(force: Boolean = false) {
      val now = System.currentTimeMillis()
      if (!force && (now - lastProgressMillis) < PROGRESS_INTERVAL_MS) return
      lastProgressMillis = now
      val checkpoint = WalkCheckpoint(
        lastProcessedPath = lastProcessedPath,
        completedSubtrees = emptySet(),
        settledFiles = scannedFiles,
        settledDirs = scannedDirs,
        settledBytes = scannedBytes,
      )
      latestCheckpointRef?.set(checkpoint)
      emit(
        ScanEvent.Progress(
          scannedFiles = scannedFiles,
          scannedDirs = scannedDirs,
          scannedBytes = scannedBytes,
          currentPath = lastProcessedPath,
          errorCount = errorCount,
          checkpoint = checkpoint,
        ),
      )
    }

    fun walkStorageTree(
      recordStatsAndErrors: Boolean,
      onRegularFile: (file: Path, size: Long, mtime: Long) -> Unit,
    ) {
      val dirDepthAllowedFiles = ArrayDeque<Boolean>()
      Files.walkFileTree(
        storageRoot,
        EnumSet.noneOf(FileVisitOption::class.java),
        Int.MAX_VALUE,
        object : SimpleFileVisitor<Path>() {
          override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!flowJob.isActive) return FileVisitResult.TERMINATE
            if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE

            val isRoot = dir == storageRoot
            if (!isRoot) {
              val dirName = dir.fileName?.toString().orEmpty()
              if (dirName.startsWith(".")) {
                return FileVisitResult.SKIP_SUBTREE
              }
              if (!config.pathGuard.isTraversalAllowed(dir)) {
                return FileVisitResult.SKIP_SUBTREE
              }
              val relSegments = relativeSegments(storageRoot, dir)
              val decision = evaluateDirectoryPolicy(relSegments)
              if (!decision.traverse) {
                return FileVisitResult.SKIP_SUBTREE
              }
              if (!readPermissionChecker(dir)) {
                if (recordStatsAndErrors) {
                  walkErrors.add(
                    WalkError(
                      path = dir.toString(),
                      errorType = "AccessDeniedException",
                      message = "Diretório sem permissão de leitura",
                    ),
                  )
                }
                return FileVisitResult.SKIP_SUBTREE
              }
              if (recordStatsAndErrors) {
                scannedDirs += 1
                lastProcessedPath = dir.toString()
              }
              dirDepthAllowedFiles.addLast(decision.collectFiles)
            } else {
              dirDepthAllowedFiles.addLast(true)
            }
            return FileVisitResult.CONTINUE
          }

          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!flowJob.isActive) return FileVisitResult.TERMINATE
            if (attrs.isSymbolicLink || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
            if (!attrs.isRegularFile) return FileVisitResult.CONTINUE

            val fileName = file.fileName?.toString().orEmpty()
            if (fileName.startsWith(".")) return FileVisitResult.CONTINUE

            val allowFilesHere = dirDepthAllowedFiles.lastOrNull() ?: true
            if (!allowFilesHere) return FileVisitResult.CONTINUE

            val size = attrs.size()
            if (recordStatsAndErrors) {
              scannedFiles += 1
              scannedBytes += size
              lastProcessedPath = file.toString()
            }

            if (size >= minSizeBytes) {
              onRegularFile(file, size, attrs.lastModifiedTime().toMillis())
            }
            return FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            if (!flowJob.isActive) return FileVisitResult.TERMINATE
            if (recordStatsAndErrors) {
              walkErrors.add(
                WalkError(
                  path = file.toString(),
                  errorType = exc.javaClass.simpleName.ifBlank { "IOException" },
                  message = exc.message ?: "Erro de leitura de arquivo",
                ),
              )
            }
            return FileVisitResult.CONTINUE
          }

          override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (dirDepthAllowedFiles.isNotEmpty()) {
              dirDepthAllowedFiles.removeLast()
            }
            if (exc != null && recordStatsAndErrors) {
              walkErrors.add(
                WalkError(
                  path = dir.toString(),
                  errorType = exc.javaClass.simpleName.ifBlank { "IOException" },
                  message = exc.message ?: "Erro ao finalizar diretório",
                ),
              )
            }
            return if (flowJob.isActive) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
          }
        },
      )
    }

    val collidingSizes = HashSet<Long>()
    if (!Files.exists(storageRoot) || !Files.isDirectory(storageRoot)) {
      emitError(storageRoot.toString(), IOException("Raiz de armazenamento inacessível: $storageRoot"))
    } else {
      walkStorageTree(recordStatsAndErrors = true) { _, size, _ ->
        val current = sizeCounts[size]
        if (current == null) {
          sizeCounts[size] = 1
        } else if (current.toInt() == 1) {
          sizeCounts[size] = 2
          collidingSizes.add(size)
        }
      }
      sizeCounts.clear()
    }

    currentCoroutineContext().ensureActive()

    for (err in walkErrors) {
      errorCount += 1
      emit(ScanEvent.ErrorLogged(err))
    }
    walkErrors.clear()

    // M4 Estágio 1B: segunda passagem coleta somente arquivos cujo tamanho tem colisão (>= 2)
    val sizeBuckets = HashMap<Long, MutableList<DuplicateFileEntry>>()
    if (collidingSizes.isNotEmpty() && Files.exists(storageRoot) && Files.isDirectory(storageRoot)) {
      walkStorageTree(recordStatsAndErrors = false) { file, size, mtime ->
        if (size in collidingSizes && config.pathGuard.isAllowed(file)) {
          sizeBuckets.getOrPut(size) { ArrayList(2) }.add(
            DuplicateFileEntry(
              path = file.toString(),
              sizeBytes = size,
              lastModified = mtime,
            ),
          )
        }
      }
    }
    collidingSizes.clear()

    val stage1Groups = ArrayList<List<DuplicateFileEntry>>()
    val bucketIterator = sizeBuckets.values.iterator()
    while (bucketIterator.hasNext()) {
      val group = bucketIterator.next()
      bucketIterator.remove()
      if (group.size >= 2) {
        stage1Groups.add(group)
      }
    }
    sizeBuckets.clear()

    emitProgress(force = true)

    // Estágio 2: hash parcial (64 KiB iniciais + 64 KiB finais + tamanho) com paralelismo <= 4
    val stage1Survivors = stage1Groups.flatten()
    stage1Groups.clear()

    val partialResults = computeConcurrently(stage1Survivors) { entry ->
      val path = Paths.get(entry.path)
      val partial = Hasher.partialSha256(path, hashDispatcher)
      entry to partial
    }

    val stage2GroupsMap = LinkedHashMap<Pair<Long, String>, MutableList<DuplicateFileEntry>>()
    for (outcome in partialResults) {
      currentCoroutineContext().ensureActive()
      when (outcome) {
        is HashTaskOutcome.Success -> {
          val (entry, partialHash) = outcome.value
          stage2GroupsMap
            .getOrPut(entry.sizeBytes to partialHash) { ArrayList(2) }
            .add(entry)
        }
        is HashTaskOutcome.Failure -> {
          emitError(outcome.path, outcome.error)
        }
      }
    }

    val stage2Survivors = ArrayList<DuplicateFileEntry>()
    val stage2Iter = stage2GroupsMap.values.iterator()
    while (stage2Iter.hasNext()) {
      val group = stage2Iter.next()
      stage2Iter.remove()
      if (group.size >= 2) {
        stage2Survivors.addAll(group)
      }
    }
    stage2GroupsMap.clear()

    emitProgress(force = true)

    // Estágio 3: hash completo SHA-256 com reaproveitamento e gravação no cache hash_cache
    val cachedMap = if (hashCacheRepository != null && stage2Survivors.isNotEmpty()) {
      hashCacheRepository.getByPaths(stage2Survivors.map { it.path })
    } else {
      emptyMap()
    }

    val fullHashResults = HashMap<String, MutableList<DuplicateFileEntry>>()
    val toCompute = ArrayList<DuplicateFileEntry>()

    for (entry in stage2Survivors) {
      currentCoroutineContext().ensureActive()
      val cached = cachedMap[entry.path]
      if (cached != null &&
        cached.sizeBytes == entry.sizeBytes &&
        cached.lastModified == entry.lastModified
      ) {
        fullHashResults.getOrPut(cached.sha256) { ArrayList(2) }.add(entry)
      } else {
        toCompute.add(entry)
      }
    }

    val computedHashes = computeConcurrently(toCompute) { entry ->
      val path = Paths.get(entry.path)
      val fullSha = Hasher.sha256(path, hashDispatcher)
      entry to fullSha
    }

    val cacheUpdates = ArrayList<CachedHashEntry>(computedHashes.size)
    val cacheTimestamp = clock.nowMillis()
    for (outcome in computedHashes) {
      currentCoroutineContext().ensureActive()
      when (outcome) {
        is HashTaskOutcome.Success -> {
          val (entry, fullSha) = outcome.value
          fullHashResults.getOrPut(fullSha) { ArrayList(2) }.add(entry)
          cacheUpdates.add(
            CachedHashEntry(
              path = entry.path,
              sizeBytes = entry.sizeBytes,
              lastModified = entry.lastModified,
              sha256 = fullSha,
              updatedAt = cacheTimestamp,
            ),
          )
        }
        is HashTaskOutcome.Failure -> {
          emitError(outcome.path, outcome.error)
        }
      }
    }

    if (hashCacheRepository != null && cacheUpdates.isNotEmpty()) {
      hashCacheRepository.upsertAll(cacheUpdates)
    }

    var emittedCandidatesCount = 0
    val sortedGroups = fullHashResults.entries
      .filter { it.value.size >= 2 }
      .sortedWith(
        compareByDescending<Map.Entry<String, MutableList<DuplicateFileEntry>>> {
          it.value.first().sizeBytes * (it.value.size - 1)
        }.thenBy { it.key },
      )

    for ((sha256, groupEntries) in sortedGroups) {
      currentCoroutineContext().ensureActive()
      val resolution = DuplicateKeepPolicy.resolve(
        sha256 = sha256,
        entries = groupEntries,
        priorityFolders = priorityFolders,
        storageRoot = storageRoot,
      )
      val ruleId = if (resolution.hasPriorityMatch) {
        DuplicateRuleId.DUPLICATE_PRIORITY.ruleId
      } else {
        DuplicateRuleId.DUPLICATE_NO_PRIORITY.ruleId
      }
      // M7: reduz confiança para MEDIUM e desmarca quando há empate no mesmo nível de prioridade
      // ou envolvimento de diretórios de projetos / dados de apps.
      val confidence = if (resolution.hasPriorityMatch && !resolution.hasSamePriorityTie) {
        Confidence.HIGH
      } else {
        Confidence.MEDIUM
      }
      val keptReason = if (resolution.hasPriorityMatch) {
        REASON_KEPT_PRIORITY
      } else {
        REASON_KEPT_NO_PRIORITY
      }
      val removableReason = if (resolution.hasPriorityMatch) {
        REASON_DUPLICATE_PRIORITY
      } else {
        REASON_DUPLICATE_NO_PRIORITY
      }

      val kept = resolution.keptEntry
      emittedCandidatesCount += 1
      emit(
        ScanEvent.Found(
          candidate = Candidate(
            path = kept.path,
            sizeBytes = kept.sizeBytes,
            lastModified = kept.lastModified,
            category = Category.DUPLICATE,
            ruleId = ruleId,
            confidence = confidence,
            groupId = sha256,
            preselected = false,
          ),
          reason = keptReason,
          isKept = true,
        ),
      )

      for (removable in resolution.removableEntries) {
        emittedCandidatesCount += 1
        emit(
          ScanEvent.Found(
            candidate = Candidate(
              path = removable.path,
              sizeBytes = removable.sizeBytes,
              lastModified = removable.lastModified,
              category = Category.DUPLICATE,
              ruleId = ruleId,
              confidence = confidence,
              groupId = sha256,
              preselected = confidence == Confidence.HIGH,
            ),
            reason = removableReason,
            isKept = false,
          ),
        )
      }
    }

    val finalCheckpoint = WalkCheckpoint(
      lastProcessedPath = lastProcessedPath ?: storageRoot.toString(),
      completedSubtrees = setOf(SCOPE_DUPLICATE_MARKER),
      settledFiles = scannedFiles,
      settledDirs = scannedDirs,
      settledBytes = scannedBytes,
    )
    latestCheckpointRef?.set(finalCheckpoint)
    emitProgress(force = true)

    val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
    emit(
      ScanEvent.Done(
        scannedFiles = scannedFiles,
        scannedDirs = scannedDirs,
        scannedBytes = scannedBytes,
        candidatesCount = emittedCandidatesCount,
        errorCount = errorCount,
        durationMs = durationMs,
      ),
    )
  }.flowOn(walkDispatcher)

  private suspend fun <T> computeConcurrently(
    items: List<DuplicateFileEntry>,
    block: suspend (DuplicateFileEntry) -> T,
  ): List<HashTaskOutcome<T>> {
    if (items.isEmpty()) return emptyList()
    val results = arrayOfNulls<HashTaskOutcome<T>>(items.size)
    val nextIndex = AtomicInteger(0)
    val workerCount = minOf(Hasher.MAX_HASH_PARALLELISM, items.size)

    coroutineScope {
      repeat(workerCount) {
        launch(hashDispatcher) {
          while (true) {
            currentCoroutineContext().ensureActive()
            val idx = nextIndex.getAndIncrement()
            if (idx >= items.size) break
            val item = items[idx]
            results[idx] = try {
              HashTaskOutcome.Success(block(item))
            } catch (ce: CancellationException) {
              throw ce
            } catch (t: Throwable) {
              HashTaskOutcome.Failure(item.path, t)
            }
          }
        }
      }
    }

    return results.mapNotNull { it }
  }

  private fun relativeSegments(storageRoot: Path, path: Path): List<String> {
    val rel = storageRoot.relativize(path)
    val result = ArrayList<String>(rel.nameCount)
    for (i in 0 until rel.nameCount) {
      result.add(rel.getName(i).toString())
    }
    return result
  }

  private fun evaluateDirectoryPolicy(segments: List<String>): DirectoryPolicyDecision {
    if (segments.isEmpty()) return DirectoryPolicyDecision(traverse = true, collectFiles = true)
    if (!segments[0].equals("Android", ignoreCase = true)) {
      return DirectoryPolicyDecision(traverse = true, collectFiles = true)
    }

    // Dentro de Android/, somente as pastas de mídia do WhatsApp (seção 7.5) são consideradas
    return when (segments.size) {
      1 -> DirectoryPolicyDecision(traverse = true, collectFiles = false)
      2 -> DirectoryPolicyDecision(
        traverse = segments[1].equals("media", ignoreCase = true),
        collectFiles = false,
      )
      3 -> DirectoryPolicyDecision(
        traverse = segments[2].equals(WA_PKG, ignoreCase = true) ||
          segments[2].equals(WA_BUSINESS_PKG, ignoreCase = true),
        collectFiles = false,
      )
      4 -> {
        val expectedAppDir = if (segments[2].equals(WA_PKG, ignoreCase = true)) {
          "WhatsApp"
        } else {
          "WhatsApp Business"
        }
        DirectoryPolicyDecision(
          traverse = segments[3].equals(expectedAppDir, ignoreCase = true),
          collectFiles = false,
        )
      }
      5 -> DirectoryPolicyDecision(
        traverse = segments[4].equals("Media", ignoreCase = true),
        collectFiles = false,
      )
      6 -> {
        val allowedSubfolder = WHATSAPP_MEDIA_SUBFOLDER_REGEX.matches(segments[5])
        DirectoryPolicyDecision(
          traverse = allowedSubfolder,
          collectFiles = allowedSubfolder,
        )
      }
      else -> {
        val allowedSubfolder = WHATSAPP_MEDIA_SUBFOLDER_REGEX.matches(segments[5])
        DirectoryPolicyDecision(
          traverse = allowedSubfolder,
          collectFiles = allowedSubfolder,
        )
      }
    }
  }

  private data class DirectoryPolicyDecision(
    val traverse: Boolean,
    val collectFiles: Boolean,
  )

  private sealed interface HashTaskOutcome<out T> {
    data class Success<T>(val value: T) : HashTaskOutcome<T>
    data class Failure(val path: String, val error: Throwable) : HashTaskOutcome<Nothing>
  }

  companion object {
    const val REASON_KEPT_PRIORITY = "Cópia preservada por pasta de prioridade"
    const val REASON_KEPT_NO_PRIORITY = "Sem pasta de prioridade · cópia preservada"
    const val REASON_DUPLICATE_PRIORITY = "Duplicado idêntico por SHA-256"
    const val REASON_DUPLICATE_NO_PRIORITY = "Sem pasta de prioridade"

    private const val PROGRESS_INTERVAL_MS = 250L
    private const val SCOPE_DUPLICATE_MARKER = "__DUPLICATE_SCAN__"
    private const val WA_PKG = "com.whatsapp"
    private const val WA_BUSINESS_PKG = "com.whatsapp.w4b"
    private val WHATSAPP_MEDIA_SUBFOLDER_REGEX =
      Regex("^WhatsApp( Business)? (Images|Video|Audio|Voice Notes)$")
  }
}
