package io.github.km20197.limpadroid.domain.fs

import io.github.km20197.limpadroid.domain.model.FileEntry
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

data class WalkConfig(
  val rootPath: Path,
  val pathGuard: PathGuard,
  val initialCheckpoint: WalkCheckpoint = WalkCheckpoint(),
  val initialErrorCount: Int = 0,
  val progressIntervalFiles: Int = 50,
  val progressIntervalMs: Long = 250L,
)

sealed interface WalkEvent {
  data class Entry(val entry: FileEntry) : WalkEvent
  data class Progress(
    val scannedFiles: Long,
    val scannedDirs: Long,
    val scannedBytes: Long,
    val currentPath: String?,
    val errorCount: Int,
    val checkpoint: WalkCheckpoint,
  ) : WalkEvent

  data class Error(val error: WalkError) : WalkEvent
  data class Interrupted(
    val checkpoint: WalkCheckpoint,
    val scannedFiles: Long,
    val scannedDirs: Long,
    val scannedBytes: Long,
    val errorCount: Int,
  ) : WalkEvent

  data class Completed(
    val scannedFiles: Long,
    val scannedDirs: Long,
    val scannedBytes: Long,
    val errorCount: Int,
    val durationMs: Long,
  ) : WalkEvent
}

private data class DirFrame(
  val dirKey: String,
  var files: Long = 0L,
  var dirs: Long = 0L,
  var bytes: Long = 0L,
)

class FileWalker(
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  private val readPermissionChecker: (Path) -> Boolean = Files::isReadable,
) {

  fun walk(
    config: WalkConfig,
    latestCheckpointRef: AtomicReference<WalkCheckpoint>? = null,
  ): Flow<WalkEvent> = flow {
    val eventsChannel = Channel<WalkEvent>(capacity = EVENT_CHANNEL_CAPACITY)

    coroutineScope {
      val flowJob: Job = this.coroutineContext.job

      val producerJob = launch(ioDispatcher) {
        val startNanos = System.nanoTime()
        val normalizedRoot = config.pathGuard.storageRoot
        val completedSubtrees = config.initialCheckpoint.completedSubtrees.toMutableSet()
        var lastProcessedPath = config.initialCheckpoint.lastProcessedPath
        var settledFiles = config.initialCheckpoint.settledFiles
        var settledDirs = config.initialCheckpoint.settledDirs
        var settledBytes = config.initialCheckpoint.settledBytes
        var scannedFiles = settledFiles
        var scannedDirs = settledDirs
        var scannedBytes = settledBytes
        var errorCount = config.initialErrorCount
        var filesSinceProgress = 0
        var lastProgressMillis = System.currentTimeMillis()
        var wasInterrupted = false
        val frameStack = ArrayDeque<DirFrame>()

        fun isRunning(): Boolean = flowJob.isActive && !wasInterrupted

        fun publish(event: WalkEvent): Boolean {
          while (flowJob.isActive) {
            val result = eventsChannel.trySend(event)
            if (result.isSuccess) return true
            if (result.isClosed) return false
            LockSupport.parkNanos(BACKPRESSURE_PARK_NANOS)
          }
          return false
        }

        fun currentCheckpoint(): WalkCheckpoint {
          val snapshot = WalkCheckpoint(
            lastProcessedPath = lastProcessedPath,
            completedSubtrees = completedSubtrees.toSet(),
            settledFiles = settledFiles,
            settledDirs = settledDirs,
            settledBytes = settledBytes,
          )
          latestCheckpointRef?.set(snapshot)
          return snapshot
        }

        fun emitProgressIfDue(force: Boolean = false) {
          val now = System.currentTimeMillis()
          if (force || filesSinceProgress >= config.progressIntervalFiles ||
            (now - lastProgressMillis) >= config.progressIntervalMs
          ) {
            filesSinceProgress = 0
            lastProgressMillis = now
            publish(
              WalkEvent.Progress(
                scannedFiles = scannedFiles,
                scannedDirs = scannedDirs,
                scannedBytes = scannedBytes,
                currentPath = lastProcessedPath,
                errorCount = errorCount,
                checkpoint = currentCheckpoint(),
              ),
            )
          }
        }

        fun relativeDirKey(dir: Path): String {
          val normalized = dir.toAbsolutePath().normalize()
          if (normalized == normalizedRoot) return ""
          if (!normalized.startsWith(normalizedRoot)) return normalized.toString()
          return normalizedRoot.relativize(normalized).joinToString("/")
        }

        fun recordCompletedSubtree(dirKey: String) {
          if (dirKey.isEmpty()) return
          val prefix = "$dirKey/"
          val iterator = completedSubtrees.iterator()
          while (iterator.hasNext()) {
            if (iterator.next().startsWith(prefix)) {
              iterator.remove()
            }
          }
          if (completedSubtrees.size < MAX_CHECKPOINT_SUBTREES) {
            completedSubtrees.add(dirKey)
          }
        }

        fun emitError(path: Path, throwable: Throwable) {
          errorCount += 1
          publish(
            WalkEvent.Error(
              WalkError(
                path = path.toString(),
                errorType = throwable.javaClass.simpleName.ifBlank { "IOException" },
                message = throwable.message ?: "Erro de leitura de arquivo",
              ),
            ),
          )
        }

        currentCheckpoint()

        val visitor = object : SimpleFileVisitor<Path>() {
          override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!isRunning()) {
              wasInterrupted = true
              return FileVisitResult.TERMINATE
            }

            if (Files.isSymbolicLink(dir)) {
              return FileVisitResult.SKIP_SUBTREE
            }

            if (!config.pathGuard.isTraversalAllowed(dir)) {
              return FileVisitResult.SKIP_SUBTREE
            }

            val dirKey = relativeDirKey(dir)
            if (dirKey.isNotEmpty() && completedSubtrees.contains(dirKey)) {
              return FileVisitResult.SKIP_SUBTREE
            }

            if (!readPermissionChecker(dir)) {
              emitError(
                dir,
                AccessDeniedException(dir.toFile(), reason = "Diretório sem permissão de leitura"),
              )
              if (dirKey.isNotEmpty()) {
                recordCompletedSubtree(dirKey)
                currentCheckpoint()
              }
              return FileVisitResult.SKIP_SUBTREE
            }

            if (dirKey.isNotEmpty()) {
              scannedDirs += 1
              lastProcessedPath = dir.toString()
              frameStack.addLast(DirFrame(dirKey = dirKey, dirs = 1L))
              val mtime = runCatching { attrs.lastModifiedTime().toMillis() }.getOrDefault(0L)
              val published = publish(
                WalkEvent.Entry(
                  FileEntry(
                    path = dir.toString(),
                    sizeBytes = 0L,
                    lastModified = mtime,
                    isDirectory = true,
                  ),
                ),
              )
              if (!published) {
                wasInterrupted = true
                return FileVisitResult.TERMINATE
              }
              emitProgressIfDue()
            } else {
              frameStack.addLast(DirFrame(dirKey = ""))
            }
            return FileVisitResult.CONTINUE
          }

          override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!isRunning()) {
              wasInterrupted = true
              return FileVisitResult.TERMINATE
            }

            if (attrs.isSymbolicLink || Files.isSymbolicLink(file)) {
              return FileVisitResult.CONTINUE
            }

            if (!config.pathGuard.isAllowed(file)) {
              return FileVisitResult.CONTINUE
            }

            runCatching {
              val size = if (attrs.isRegularFile) attrs.size() else 0L
              val mtime = attrs.lastModifiedTime().toMillis()
              scannedFiles += 1
              scannedBytes += size
              filesSinceProgress += 1
              frameStack.lastOrNull()?.let { activeFrame ->
                activeFrame.files += 1
                activeFrame.bytes += size
              }
              lastProcessedPath = file.toString()
              val published = publish(
                WalkEvent.Entry(
                  FileEntry(
                    path = file.toString(),
                    sizeBytes = size,
                    lastModified = mtime,
                    isDirectory = false,
                  ),
                ),
              )
              if (!published) {
                wasInterrupted = true
                return FileVisitResult.TERMINATE
              }
              emitProgressIfDue()
            }.onFailure { throwable ->
              emitError(file, throwable)
            }

            return FileVisitResult.CONTINUE
          }

          override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            if (!isRunning()) {
              wasInterrupted = true
              return FileVisitResult.TERMINATE
            }
            if (!config.pathGuard.isProtectedOrExcluded(file)) {
              emitError(file, exc)
            }
            return FileVisitResult.CONTINUE
          }

          override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            val finishedFrame = if (frameStack.isNotEmpty()) frameStack.removeLast() else null
            if (exc != null && !config.pathGuard.isProtectedOrExcluded(dir)) {
              emitError(dir, exc)
            }
            if (!isRunning()) {
              wasInterrupted = true
              return FileVisitResult.TERMINATE
            }
            if (exc == null && finishedFrame != null) {
              settledFiles += finishedFrame.files
              settledDirs += finishedFrame.dirs
              settledBytes += finishedFrame.bytes
              if (finishedFrame.dirKey.isNotEmpty()) {
                recordCompletedSubtree(finishedFrame.dirKey)
              }
              currentCheckpoint()
            }
            return FileVisitResult.CONTINUE
          }
        }

        try {
          if (!Files.exists(normalizedRoot) || !Files.isDirectory(normalizedRoot)) {
            emitError(normalizedRoot, IOException("Raiz de armazenamento inacessível: $normalizedRoot"))
          } else {
            Files.walkFileTree(
              normalizedRoot,
              EnumSet.noneOf(FileVisitOption::class.java),
              Int.MAX_VALUE,
              visitor,
            )
          }

          val finalCheckpoint = currentCheckpoint()
          if (wasInterrupted || !flowJob.isActive) {
            eventsChannel.trySend(
              WalkEvent.Interrupted(
                checkpoint = finalCheckpoint,
                scannedFiles = scannedFiles,
                scannedDirs = scannedDirs,
                scannedBytes = scannedBytes,
                errorCount = errorCount,
              ),
            )
          } else {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
            emitProgressIfDue(force = true)
            publish(
              WalkEvent.Completed(
                scannedFiles = scannedFiles,
                scannedDirs = scannedDirs,
                scannedBytes = scannedBytes,
                errorCount = errorCount,
                durationMs = durationMs,
              ),
            )
          }
        } catch (ce: CancellationException) {
          currentCheckpoint()
          throw ce
        } catch (t: Throwable) {
          emitError(normalizedRoot, t)
          val durationMs = (System.nanoTime() - startNanos) / 1_000_000L
          publish(
            WalkEvent.Completed(
              scannedFiles = scannedFiles,
              scannedDirs = scannedDirs,
              scannedBytes = scannedBytes,
              errorCount = errorCount,
              durationMs = durationMs,
            ),
          )
        } finally {
          eventsChannel.close()
        }
      }

      try {
        for (event in eventsChannel) {
          emit(event)
        }
      } finally {
        eventsChannel.close()
        producerJob.cancel()
      }
    }
  }

  private companion object {
    const val EVENT_CHANNEL_CAPACITY = 512
    const val BACKPRESSURE_PARK_NANOS = 200_000L
    const val MAX_CHECKPOINT_SUBTREES = 2_000
  }
}
