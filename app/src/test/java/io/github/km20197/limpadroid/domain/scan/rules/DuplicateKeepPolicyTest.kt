package io.github.km20197.limpadroid.domain.scan.rules

import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateKeepPolicyTest {

  private val root = Paths.get("/storage/emulated/0")

  @Test
  fun `priority folder index beats non-priority and higher nested index beats lower parent index`() {
    val cam = DuplicateFileEntry("/storage/emulated/0/DCIM/Camera/img.jpg", 8_192L, 500L)
    val dcim = DuplicateFileEntry("/storage/emulated/0/DCIM/Restored/img.jpg", 8_192L, 100L)
    val download = DuplicateFileEntry("/storage/emulated/0/Download/img_copy.jpg", 8_192L, 50L)

    val resolution = DuplicateKeepPolicy.resolve(
      sha256 = "a".repeat(64),
      entries = listOf(download, dcim, cam),
      priorityFolders = DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS,
      storageRoot = root,
    )

    assertTrue(resolution.hasPriorityMatch)
    assertEquals(cam, resolution.keptEntry)
    assertEquals(setOf(dcim, download), resolution.removableEntries.toSet())
  }

  @Test
  fun `case-insensitive matching respects custom priority folder order`() {
    val docs = DuplicateFileEntry("/storage/emulated/0/Documents/doc.pdf", 8_192L, 200L)
    val work = DuplicateFileEntry("/storage/emulated/0/trabalho/projetos/doc.pdf", 8_192L, 300L)

    val resolution = DuplicateKeepPolicy.resolve(
      sha256 = "b".repeat(64),
      entries = listOf(docs, work),
      priorityFolders = listOf("Trabalho/Projetos", "Documents"),
      storageRoot = root,
    )

    assertTrue(resolution.hasPriorityMatch)
    assertEquals(work, resolution.keptEntry)
    assertEquals(listOf(docs), resolution.removableEntries)
  }

  @Test
  fun `tie inside same priority folder preserves oldest lastModified`() {
    val newer = DuplicateFileEntry("/storage/emulated/0/Pictures/b_new.png", 4_096L, 2_000L)
    val older = DuplicateFileEntry("/storage/emulated/0/Pictures/z_old.png", 4_096L, 1_000L)

    val resolution = DuplicateKeepPolicy.resolve(
      sha256 = "c".repeat(64),
      entries = listOf(newer, older),
      priorityFolders = DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS,
      storageRoot = root,
    )

    assertTrue(resolution.hasPriorityMatch)
    assertEquals(older, resolution.keptEntry)
    assertEquals(listOf(newer), resolution.removableEntries)
  }

  @Test
  fun `tie with equal priority and equal lastModified preserves lexicographically smallest path`() {
    val pathB = DuplicateFileEntry("/storage/emulated/0/Documents/b.pdf", 4_096L, 1_000L)
    val pathA = DuplicateFileEntry("/storage/emulated/0/Documents/a.pdf", 4_096L, 1_000L)

    val resolution = DuplicateKeepPolicy.resolve(
      sha256 = "d".repeat(64),
      entries = listOf(pathB, pathA),
      priorityFolders = DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS,
      storageRoot = root,
    )

    assertTrue(resolution.hasPriorityMatch)
    assertEquals(pathA, resolution.keptEntry)
    assertEquals(listOf(pathB), resolution.removableEntries)
  }

  @Test
  fun `no copy in priority folder marks hasPriorityMatch false and keeps oldest then lexicographical path`() {
    val newer = DuplicateFileEntry("/storage/emulated/0/Download/file_1.zip", 10_000L, 5_000L)
    val olderB = DuplicateFileEntry("/storage/emulated/0/Backup/z.zip", 10_000L, 1_000L)
    val olderA = DuplicateFileEntry("/storage/emulated/0/Archive/a.zip", 10_000L, 1_000L)

    val resolution = DuplicateKeepPolicy.resolve(
      sha256 = "e".repeat(64),
      entries = listOf(newer, olderB, olderA),
      priorityFolders = DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS,
      storageRoot = root,
    )

    assertFalse(resolution.hasPriorityMatch)
    assertEquals(olderA, resolution.keptEntry)
    assertEquals(setOf(newer, olderB), resolution.removableEntries.toSet())
  }

  @Test
  fun `enforceKeepAtLeastOneCopy prevents selecting kept copy or all copies in group`() {
    val sha = "f".repeat(64)
    val kept = dupRecord(id = 1L, path = "/storage/emulated/0/DCIM/Camera/a.jpg", groupId = sha, isKept = true, preselected = false)
    val dup = dupRecord(id = 2L, path = "/storage/emulated/0/Download/a.jpg", groupId = sha, isKept = false, preselected = true)

    val adjusted = DuplicateKeepPolicy.enforceKeepAtLeastOneCopy(
      groupRecords = listOf(kept, dup),
      desiredSelectionById = mapOf(1L to true, 2L to true),
    )

    assertEquals(false, adjusted[1L])
    assertEquals(true, adjusted[2L])
    assertTrue(adjusted.values.any { !it })

    // Mesmo sem isKept explícito, impede que todas as cópias fiquem marcadas
    val unflagged1 = kept.copy(isKept = false)
    val unflagged2 = dup.copy(isKept = false)
    val fallbackAdjusted = DuplicateKeepPolicy.enforceKeepAtLeastOneCopy(
      groupRecords = listOf(unflagged1, unflagged2),
      desiredSelectionById = mapOf(1L to true, 2L to true),
    )
    assertEquals(1, fallbackAdjusted.values.count { !it })
    assertEquals(1, fallbackAdjusted.values.count { it })
  }

  @Test
  fun `partitionBatchPreservingAtLeastOne never allows all copies of a group to enter trash`() {
    val sha = "1".repeat(64)
    val kept = dupRecord(id = 1L, path = "/storage/emulated/0/DCIM/a.jpg", groupId = sha, isKept = true, preselected = false)
    val copy1 = dupRecord(id = 2L, path = "/storage/emulated/0/Download/a1.jpg", groupId = sha, isKept = false, preselected = true)
    val copy2 = dupRecord(id = 3L, path = "/storage/emulated/0/Download/a2.jpg", groupId = sha, isKept = false, preselected = true)

    // Caso 1: lote contém todas as cópias incluindo isKept -> isKept bloqueado, removíveis passam pois copy kept existe fora
    val (safe1, blocked1) = DuplicateKeepPolicy.partitionBatchPreservingAtLeastOne(
      batchRecords = listOf(kept, copy1, copy2),
      existingCopiesInGroupProvider = { _, _ -> 3 },
    )
    assertEquals(listOf(copy1.id, copy2.id), safe1.map { it.id })
    assertEquals(listOf(kept.id), blocked1.map { it.id })

    // Caso 2: lote tenta enviar ambas as cópias restantes sem deixar nenhuma fora -> bloqueia 1 cópia
    val (safe2, blocked2) = DuplicateKeepPolicy.partitionBatchPreservingAtLeastOne(
      batchRecords = listOf(copy1, copy2),
      existingCopiesInGroupProvider = { _, _ -> 2 },
    )
    assertEquals(1, safe2.size)
    assertEquals(1, blocked2.size)
  }

  private fun dupRecord(
    id: Long,
    path: String,
    groupId: String,
    isKept: Boolean,
    preselected: Boolean,
  ): ScanCandidateRecord = ScanCandidateRecord(
    id = id,
    sessionId = "s1",
    candidate = Candidate(
      path = path,
      sizeBytes = 8_192L,
      lastModified = 100L + id,
      category = Category.DUPLICATE,
      ruleId = DuplicateRuleId.DUPLICATE_PRIORITY.ruleId,
      confidence = Confidence.HIGH,
      groupId = groupId,
      preselected = preselected,
    ),
    isKept = isKept,
  )
}
