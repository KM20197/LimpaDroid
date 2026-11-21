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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

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
      val dirQueue = ArrayDeque<Path>()
      dirQueue.addLast(mediaFolder)

      while (dirQueue.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val currentDir = dirQueue.removeFirst()

        if (Files.isSymbolicLink(currentDir) || !Files.isDirectory(currentDir, LinkOption.NOFOLLOW_LINKS)) {
          continue
        }
        if (!config.pathGuard.isTraversalAllowed(currentDir)) {
          continue
        }
        if (currentDir != mediaFolder) {
          val dirName = currentDir.fileName?.toString().orEmpty()
          if (WhatsAppMediaRules.isForbiddenFolderName(dirName)) {
            continue
          }
        }
        if (!readPermissionChecker(currentDir)) {
          emitError(currentDir, IOException("Diretório sem permissão de leitura"))
          continue
        }

        scannedDirs += 1
        lastProcessedPath = currentDir.toString()

        val childDirs = mutableListOf<Path>()
        try {
          Files.newDirectoryStream(currentDir).use { stream ->
            val iterator = stream.iterator()
            while (true) {
              currentCoroutineContext().ensureActive()
              val entry = try {
                if (!iterator.hasNext()) break
                iterator.next()
              } catch (die: DirectoryIteratorException) {
                emitError(currentDir, die.cause ?: die)
                break
              } catch (ce: CancellationException) {
                throw ce
              } catch (t: Throwable) {
                emitError(currentDir, t)
                break
              }

              if (Files.isSymbolicLink(entry)) continue
              if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                childDirs.add(entry)
                continue
              }

              val attrs = try {
                Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
              } catch (ce: CancellationException) {
                throw ce
              } catch (t: Throwable) {
                emitError(entry, t)
                continue
              }
              if (attrs.isSymbolicLink || !attrs.isRegularFile) continue

              val fileName = entry.fileName?.toString().orEmpty()
              if (fileName.startsWith(".")) continue
              if (!config.pathGuard.isAllowed(entry)) continue

              val size = attrs.size()
              val mtime = attrs.lastModifiedTime().toMillis()
              scannedFiles += 1
              scannedBytes += size
              lastProcessedPath = entry.toString()

              if (WhatsAppMediaRules.matchesMinAge(mtime, nowMillis, minAgeDays)) {
                val candidate = Candidate(
                  path = entry.toString(),
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
                  filePath = entry,
                  lastModifiedMillis = mtime,
                  nowMillis = nowMillis,
                )
                candidatesCount += 1
                emit(ScanEvent.Found(candidate = candidate, reason = reason))
                emitProgress()
              }
            }
          }
        } catch (ce: CancellationException) {
          throw ce
        } catch (t: Throwable) {
          emitError(currentDir, t)
        }

        childDirs.sortBy { it.fileName?.toString().orEmpty().lowercase() }
        for (i in childDirs.lastIndex downTo 0) {
          dirQueue.addFirst(childDirs[i])
        }
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
