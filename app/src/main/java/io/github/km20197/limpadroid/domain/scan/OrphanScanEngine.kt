package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsRepository
import io.github.km20197.limpadroid.domain.scan.rules.OrphanDirectoryClassifier
import io.github.km20197.limpadroid.domain.scan.rules.OrphanMatch
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class OrphanScanEngine(
  private val installedAppsRepository: InstalledAppsRepository,
  private val applicationId: String,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScanEngine {
  override val scope: ScanScope = ScanScope.ORPHAN
  override val category: Category = Category.ORPHAN

  override fun scan(
    config: ScanConfig,
    latestCheckpointRef: AtomicReference<WalkCheckpoint>?,
  ): Flow<ScanEvent> = flow {
    val startNanos = System.nanoTime()
    emit(ScanEvent.Started(sessionId = config.sessionId, resumed = config.resumed))

    val snapshot = installedAppsRepository.syncKnownApps()
    val classifier = OrphanDirectoryClassifier(
      presentApps = snapshot.presentApps,
      knownApps = snapshot.knownApps,
      applicationId = applicationId,
    )

    val root = config.pathGuard.storageRoot
    var scannedFiles = 0L
    var scannedDirs = 0L
    var scannedBytes = 0L
    var candidatesCount = 0
    var errorCount = config.initialErrorCount

    suspend fun emitError(path: Path, throwable: Throwable) {
      errorCount += 1
      emit(
        ScanEvent.ErrorLogged(
          WalkError(
            path = path.toString(),
            errorType = throwable.javaClass.simpleName.ifBlank { "IOException" },
            message = throwable.message ?: "Erro ao inspecionar diretório",
          ),
        ),
      )
    }

    suspend fun inspectCandidateDirectory(
      dir: Path,
      relativeSegments: List<String>,
      match: OrphanMatch,
    ) {
      currentCoroutineContext().ensureActive()
      if (!config.pathGuard.isAllowed(dir)) return
      if (config.pathGuard.hasProtectedDescendant(dir)) return

      var dirBytes = 0L
      var dirFiles = 0L
      var dirSubdirs = 1L
      var latestMtime = runCatching {
        Files.getLastModifiedTime(dir, LinkOption.NOFOLLOW_LINKS).toMillis()
      }.getOrDefault(0L)

      val dirErrors = mutableListOf<Pair<Path, Throwable>>()

      runCatching {
        Files.walkFileTree(
          dir,
          EnumSet.noneOf(FileVisitOption::class.java),
          Int.MAX_VALUE,
          object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(subDir: Path, attrs: BasicFileAttributes): FileVisitResult {
              if (Files.isSymbolicLink(subDir)) return FileVisitResult.SKIP_SUBTREE
              if (!config.pathGuard.isAllowed(subDir)) return FileVisitResult.SKIP_SUBTREE
              if (subDir != dir) {
                dirSubdirs += 1
              }
              return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
              if (attrs.isSymbolicLink || Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE
              if (!config.pathGuard.isAllowed(file)) return FileVisitResult.CONTINUE
              if (attrs.isRegularFile) {
                dirFiles += 1
                dirBytes += attrs.size()
                val mtime = attrs.lastModifiedTime().toMillis()
                if (mtime > latestMtime) {
                  latestMtime = mtime
                }
              }
              return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
              dirErrors.add(file to exc)
              return FileVisitResult.CONTINUE
            }
          },
        )
      }.onFailure { throwable ->
        dirErrors.add(dir to throwable)
      }

      for ((errPath, err) in dirErrors) {
        emitError(errPath, err)
      }

      scannedFiles += dirFiles
      scannedDirs += dirSubdirs
      scannedBytes += dirBytes

      val folderName = relativeSegments.last()
      val candidate = Candidate(
        path = dir.toString(),
        sizeBytes = dirBytes,
        lastModified = latestMtime,
        category = Category.ORPHAN,
        ruleId = match.ruleId.ruleId,
        confidence = match.confidence,
        groupId = match.matchedApp?.packageName ?: folderName,
        preselected = match.preselected,
      )
      val reason = OrphanDirectoryClassifier.formatReason(match, folderName)
      candidatesCount += 1
      emit(ScanEvent.Found(candidate = candidate, reason = reason))

      val checkpoint = WalkCheckpoint(
        lastProcessedPath = dir.toString(),
        completedSubtrees = setOf(relativeSegments.joinToString("/")),
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
          currentPath = dir.toString(),
          errorCount = errorCount,
          checkpoint = checkpoint,
        ),
      )
    }

    suspend fun listDirectChildDirectories(parent: Path): List<Path> {
      currentCoroutineContext().ensureActive()
      if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS) ||
        !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(parent)
      ) {
        return emptyList()
      }
      val children = mutableListOf<Path>()
      try {
        Files.newDirectoryStream(parent).use { stream ->
          val iterator = stream.iterator()
          while (true) {
            currentCoroutineContext().ensureActive()
            val child = try {
              if (!iterator.hasNext()) break
              iterator.next()
            } catch (die: DirectoryIteratorException) {
              emitError(parent, die.cause ?: die)
              break
            }
            if (!Files.isSymbolicLink(child) &&
              Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)
            ) {
              children.add(child)
            }
          }
        }
      } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        emitError(parent, t)
      }
      return children.sortedBy { it.fileName?.toString()?.lowercase().orEmpty() }
    }

    // 1. Subpastas de Android/media (ORPHAN_MEDIA_PKG)
    val androidMediaDir = root.resolve("Android").resolve("media")
    if (config.pathGuard.isTraversalAllowed(androidMediaDir)) {
      for (child in listDirectChildDirectories(androidMediaDir)) {
        currentCoroutineContext().ensureActive()
        scannedDirs += 1
        val segments = listOf("Android", "media", child.fileName?.toString().orEmpty())
        val match = classifier.evaluateDirectory(segments)
        if (match != null) {
          inspectCandidateDirectory(child, segments, match)
        }
      }
    }

    // 2. Subpastas diretas na raiz (ORPHAN_ROOT_KNOWN e ORPHAN_ROOT_PATTERN)
    for (child in listDirectChildDirectories(root)) {
      currentCoroutineContext().ensureActive()
      scannedDirs += 1
      val name = child.fileName?.toString().orEmpty()
      val segments = listOf(name)
      val match = classifier.evaluateDirectory(segments)
      if (match != null) {
        inspectCandidateDirectory(child, segments, match)
      }
    }

    // 3. Subpastas sob Documents, Pictures, Movies, Music e Download (ORPHAN_ROOT_KNOWN)
    for (parentName in KNOWN_SUBPARENT_FOLDER_NAMES) {
      currentCoroutineContext().ensureActive()
      val parentDir = findDirectChildCaseInsensitive(root, parentName) ?: continue
      if (!config.pathGuard.isTraversalAllowed(parentDir)) continue
      val actualParentName = parentDir.fileName?.toString() ?: parentName
      for (child in listDirectChildDirectories(parentDir)) {
        currentCoroutineContext().ensureActive()
        scannedDirs += 1
        val segments = listOf(actualParentName, child.fileName?.toString().orEmpty())
        val match = classifier.evaluateDirectory(segments)
        if (match != null) {
          inspectCandidateDirectory(child, segments, match)
        }
      }
    }

    val finalCheckpoint = WalkCheckpoint(
      lastProcessedPath = root.toString(),
      completedSubtrees = setOf(ScopeOrphanMarker),
      settledFiles = scannedFiles,
      settledDirs = scannedDirs,
      settledBytes = scannedBytes,
    )
    latestCheckpointRef?.set(finalCheckpoint)
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

  private fun findDirectChildCaseInsensitive(parent: Path, targetName: String): Path? {
    val exact = parent.resolve(targetName)
    if (Files.isDirectory(exact, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(exact)) {
      return exact
    }
    return runCatching {
      Files.newDirectoryStream(parent).use { stream ->
        stream.firstOrNull { entry ->
          !Files.isSymbolicLink(entry) &&
            Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) &&
            entry.fileName?.toString()?.equals(targetName, ignoreCase = true) == true
        }
      }
    }.getOrNull()
  }

  private companion object {
    val KNOWN_SUBPARENT_FOLDER_NAMES = listOf(
      "Documents",
      "Pictures",
      "Movies",
      "Music",
      "Download",
    )
    const val ScopeOrphanMarker = "__ORPHAN_SCAN__"
  }
}
