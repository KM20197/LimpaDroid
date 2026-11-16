package io.github.km20197.limpadroid.domain.fs

import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileWalkerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var root: Path
  private lateinit var guard: DefaultPathGuard
  private val walker = FileWalker()

  @Before
  fun setUp() {
    root = tempFolder.newFolder("storage").toPath().toRealPath()
    guard = DefaultPathGuard(root, "io.github.km20197.limpadroid")
  }

  @Test
  fun `walk traverses recursively while skipping fixed exclusions and symlinks`() = runTest {
    val allowedA = Files.createDirectories(root.resolve("Download/Sub"))
    val fileA = Files.writeString(allowedA.resolve("doc.pdf"), "12345")
    Files.createDirectories(root.resolve("Pictures"))
    val fileB = Files.writeString(root.resolve("Pictures/pic.jpg"), "123")

    val androidData = Files.createDirectories(root.resolve("Android/data/pkg"))
    Files.writeString(androidData.resolve("secret.db"), "ignored")
    val trash = Files.createDirectories(root.resolve("Documents/LimpaDroid/.lixeira/item1"))
    Files.writeString(trash.resolve("deleted.bin"), "ignored")

    val outsideDir = tempFolder.newFolder("external").toPath().toRealPath()
    val outsideFile = Files.writeString(outsideDir.resolve("outside.txt"), "secret")
    runCatching { Files.createSymbolicLink(root.resolve("Download/link_out"), outsideDir) }

    val events = walker.walk(WalkConfig(rootPath = root, pathGuard = guard)).toList()
    val visitedFiles = events.filterIsInstance<WalkEvent.Entry>()
      .filter { !it.entry.isDirectory }
      .map { it.entry.path }
      .toSet()

    assertTrue(visitedFiles.contains(fileA.toString()))
    assertTrue(visitedFiles.contains(fileB.toString()))
    assertFalse(visitedFiles.any { it.contains("Android/data") })
    assertFalse(visitedFiles.any { it.contains(".lixeira") })
    assertFalse(visitedFiles.contains(outsideFile.toString()))

    val done = events.filterIsInstance<WalkEvent.Completed>().single()
    assertEquals(2L, done.scannedFiles)
    assertEquals(8L, done.scannedBytes)
  }

  @Test
  fun `walk logs error for unreadable directory without aborting remaining tree`() = runTest {
    val blockedDir = Files.createDirectories(root.resolve("Blocked"))
    Files.writeString(blockedDir.resolve("hidden.txt"), "abc")
    val okDir = Files.createDirectories(root.resolve("OkDir"))
    val okFile = Files.writeString(okDir.resolve("visible.txt"), "hello")

    val deterministicWalker = FileWalker(
      readPermissionChecker = { path -> path.fileName?.toString() != "Blocked" },
    )
    val events = deterministicWalker.walk(WalkConfig(rootPath = root, pathGuard = guard)).toList()
    val errors = events.filterIsInstance<WalkEvent.Error>()
    val visitedFiles = events.filterIsInstance<WalkEvent.Entry>()
      .filter { !it.entry.isDirectory }
      .map { it.entry.path }

    assertTrue(errors.isNotEmpty())
    assertTrue(errors.any { it.error.path == blockedDir.toString() })
    assertTrue(visitedFiles.contains(okFile.toString()))
  }

  @Test
  fun `walk skips completed subtrees and preserves exact settled counts on resume`() = runTest {
    val doneDir = Files.createDirectories(root.resolve("PartialParent/DoneChild"))
    val skippedFile = Files.writeString(doneDir.resolve("already.txt"), "old")
    val pendingFile = Files.writeString(root.resolve("PartialParent/remaining.txt"), "new_data")

    val events = walker.walk(
      WalkConfig(
        rootPath = root,
        pathGuard = guard,
        initialCheckpoint = WalkCheckpoint(
          lastProcessedPath = skippedFile.toString(),
          completedSubtrees = setOf("PartialParent/DoneChild"),
          settledFiles = 1L,
          settledDirs = 1L,
          settledBytes = 3L,
        ),
      ),
    ).toList()

    val visitedPaths = events.filterIsInstance<WalkEvent.Entry>()
      .filter { !it.entry.isDirectory }
      .map { it.entry.path }

    assertFalse(visitedPaths.contains(skippedFile.toString()))
    assertEquals(listOf(pendingFile.toString()), visitedPaths)

    val done = events.filterIsInstance<WalkEvent.Completed>().single()
    assertEquals(2L, done.scannedFiles)
    assertEquals(2L, done.scannedDirs)
    assertEquals(11L, done.scannedBytes)
  }

  @Test
  fun `walk delivers more entries than channel capacity without dropping events`() = runTest {
    val batchDir = Files.createDirectories(root.resolve("ManyFiles"))
    val total = 1_100
    for (i in 0 until total) {
      Files.writeString(batchDir.resolve("f_$i.txt"), "a")
    }

    val checkpointRef = AtomicReference(WalkCheckpoint())
    val events = walker.walk(
      WalkConfig(rootPath = root, pathGuard = guard, progressIntervalFiles = 200),
      latestCheckpointRef = checkpointRef,
    ).toList()

    val fileEntries = events.filterIsInstance<WalkEvent.Entry>().count { !it.entry.isDirectory }
    val completed = events.filterIsInstance<WalkEvent.Completed>().single()

    assertEquals(total, fileEntries)
    assertEquals(total.toLong(), completed.scannedFiles)
    assertEquals(total.toLong(), checkpointRef.get().settledFiles)
    assertTrue(checkpointRef.get().completedSubtrees.contains("ManyFiles"))
  }
}
