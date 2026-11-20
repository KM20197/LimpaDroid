package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaMatch
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaRules
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppRootLocation
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppScanSettings
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job

class WhatsAppScanEngine(
  private val settingsProvider: suspend () -> WhatsAppScanSettings = { WhatsAppScanSettings() },
  private val clock: AppClock = AppClock.System,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val readPermissionChecker: (Path) -> Boolean = Files::isReadable,
) : ScanEngine {
  override val scope: ScanScope = ScanScope.WHATSAPP
  override val category: Category = Category.WHATSAPP

  override fun scan(
    config: ScanConfig,
    latestCheckpointRef: AtomicReference<WalkCheckpoint>?,
  ): Flow<ScanEvent> = flow {
    val startNanos = System.nanoTime()
    emit(ScanEvent.Started(sessionId = config.sessionId, resumed = config.resumed))

    val settings = settingsProvider()
    val minAgeDays = settings.sanitizedMinAgeDays
    val enabledTypes = settings.enabledMediaTypes
    val autoPreselect = settings.autoPreselect
    val nowMillis = clock.nowMillis()
    val storageRoot = config.pathGuard.storageRoot
    val flowJob = currentCoroutineContext().job

    var scannedFiles = 0L
    var scannedDirs = 0L
    var scannedBytes = 0L
    var candidatesCount = 0
    var errorCount = config.initialErrorCount
    var lastProcessedPath: String? = null
    var lastProgressMillis = System.currentTimeMillis()
    val completedSubtrees = mutableSetOf<String>()

    suspend fun emitError(path: Path, throwable: Throwable) {
      errorCount += 1
      emit(
        ScanEvent.ErrorLogged(
          WalkError(
            path = path.toString(),
            errorType = throwable.javaClass.simpleName.ifBlank { "IOException" },
            message = throwable.message ?: "Erro ao inspecionar mídia do WhatsApp",
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
        completedSubtrees = completedSubtrees.toSet(),
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

    suspend fun walkMatchedMediaFolder(
      rootLocation: WhatsAppRootLocation,
      mediaFolder: Path,
      match: WhatsAppMediaMatch,
    ) {
      val collectedCandidates = mutableListOf<ScanEvent.Found>()
      val collectedErrors = mutableListOf<Pair<Path, Throwable>>()

      try {
        Files.walkFileTree(
          mediaFolder,
          EnumSet.noneOf(FileVisitOption::class.java),
          Int.MAX_VALUE,
          object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
              if (!flowJob.isActive) return FileVisitResult.TERMINATE
              if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE
              if (!config.pathGuard.isTraversalAllowed(dir)) return FileVisitResult.SKIP_SUBTREE

              if (dir != mediaFolder) {
                val dirName = dir.fileName?.toString().orEmpty()
                if (WhatsAppMediaRules.isForbiddenFolderName(dirName)) {
                  return FileVisitResult.SKIP_SUBTREE
                }
              }

              if (!readPermissionChecker(dir)) {
                collectedErrors.add(dir to IOException("Diretório sem permissão de leitura"))
                return FileVisitResult.SKIP_SUBTREE
              }

              scannedDirs += 1
              lastProcessedPath = dir.toString()
              return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
              if (!flowJob.isActive) return FileVisitResult.TERMINATE
              if (attrs.isSymbolicLink || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
              if (!attrs.isRegularFile) return FileVisitResult.CONTINUE

              val fileName = file.fileName?.toString().orEmpty()
              if (fileName.startsWith(".")) return FileVisitResult.CONTINUE
              if (!config.pathGuard.isAllowed(file)) return FileVisitResult.CONTINUE

              val size = attrs.size()
              val mtime = attrs.lastModifiedTime().toMillis()
              scannedFiles += 1
              scannedBytes += size
              lastProcessedPath = file.toString()

              if (WhatsAppMediaRules.matchesMinAge(mtime, nowMillis, minAgeDays)) {
                val candidate = Candidate(
                  path = file.toString(),
                  sizeBytes = size,
                  lastModified = mtime,
                  category = Category.WHATSAPP,
                  ruleId = match.ruleId.ruleId,
                  confidence = Confidence.HIGH,
                  groupId = "${rootLocation.displayOrigin}:${match.mediaType.name}",
                  preselected = autoPreselect,
                )
                val reason = WhatsAppMediaRules.formatReason(
                  root = rootLocation,
                  mediaFolder = mediaFolder,
                  filePath = file,
                  lastModifiedMillis = mtime,
                  nowMillis = nowMillis,
                )
                collectedCandidates.add(ScanEvent.Found(candidate = candidate, reason = reason))
              }
              return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
              if (!flowJob.isActive) return FileVisitResult.TERMINATE
              collectedErrors.add(file to exc)
              return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
              if (exc != null) {
                collectedErrors.add(dir to exc)
              }
              return if (flowJob.isActive) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
            }
          },
        )
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        collectedErrors.add(mediaFolder to t)
      }

      currentCoroutineContext().ensureActive()

      for ((errPath, err) in collectedErrors) {
        emitError(errPath, err)
      }
      for (found in collectedCandidates) {
        currentCoroutineContext().ensureActive()
        candidatesCount += 1
        emit(found)
      }
      completedSubtrees.add(mediaFolder.toString())
      emitProgress()
    }

    if (enabledTypes.isNotEmpty()) {
      val visitedMediaRoots = HashSet<Path>()
      for (rootLocation in WhatsAppMediaRules.KNOWN_MEDIA_ROOTS) {
        currentCoroutineContext().ensureActive()
        val mediaRoot = rootLocation.resolve(storageRoot)
        if (!Files.exists(mediaRoot, LinkOption.NOFOLLOW_LINKS) ||
          Files.isSymbolicLink(mediaRoot) ||
          !Files.isDirectory(mediaRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
          continue
        }
        val canonicalRoot = runCatching { mediaRoot.toRealPath(LinkOption.NOFOLLOW_LINKS) }
          .getOrDefault(mediaRoot.toAbsolutePath().normalize())
        if (!visitedMediaRoots.add(canonicalRoot)) {
          continue
        }
        if (!config.pathGuard.isTraversalAllowed(mediaRoot)) {
          continue
        }
        if (!readPermissionChecker(mediaRoot)) {
          emitError(mediaRoot, IOException("Pasta de mídia do WhatsApp sem permissão de leitura"))
          continue
        }

        scannedDirs += 1
        lastProcessedPath = mediaRoot.toString()

        val matchedFolders = mutableListOf<Pair<Path, WhatsAppMediaMatch>>()
        try {
          Files.newDirectoryStream(mediaRoot).use { stream ->
            val iterator = stream.iterator()
            while (true) {
              currentCoroutineContext().ensureActive()
              val child = try {
                if (!iterator.hasNext()) break
                iterator.next()
              } catch (die: DirectoryIteratorException) {
                emitError(mediaRoot, die.cause ?: die)
                break
              }
              if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                continue
              }
              val childName = child.fileName?.toString().orEmpty()
              val match = WhatsAppMediaRules.classifyMediaSubfolder(childName, enabledTypes)
              if (match != null && config.pathGuard.isTraversalAllowed(child)) {
                matchedFolders.add(child to match)
              }
            }
          }
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          emitError(mediaRoot, t)
        }

        matchedFolders.sortBy { it.first.fileName?.toString().orEmpty().lowercase() }
        for ((folder, match) in matchedFolders) {
          currentCoroutineContext().ensureActive()
          walkMatchedMediaFolder(rootLocation, folder, match)
        }
      }
    }

    val finalCheckpoint = WalkCheckpoint(
      lastProcessedPath = lastProcessedPath ?: storageRoot.toString(),
      completedSubtrees = completedSubtrees + SCOPE_WHATSAPP_MARKER,
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
        candidatesCount = candidatesCount,
        errorCount = errorCount,
        durationMs = durationMs,
      ),
    )
  }.flowOn(ioDispatcher)

  private companion object {
    const val PROGRESS_INTERVAL_MS = 250L
    const val SCOPE_WHATSAPP_MARKER = "__WHATSAPP_SCAN__"
  }
}
