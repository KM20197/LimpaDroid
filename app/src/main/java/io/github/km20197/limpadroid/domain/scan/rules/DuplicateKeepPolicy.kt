package io.github.km20197.limpadroid.domain.scan.rules

import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import java.nio.file.Path
import java.nio.file.Paths

enum class DuplicateRuleId(val ruleId: String) {
  DUPLICATE_PRIORITY("DUPLICATE_PRIORITY"),
  DUPLICATE_NO_PRIORITY("DUPLICATE_NO_PRIORITY"),
}

data class DuplicateFileEntry(
  val path: String,
  val sizeBytes: Long,
  val lastModified: Long,
)

data class DuplicateGroupResolution(
  val sha256: String,
  val sizeBytes: Long,
  val keptEntry: DuplicateFileEntry,
  val removableEntries: List<DuplicateFileEntry>,
  val hasPriorityMatch: Boolean,
)

object DuplicateKeepPolicy {
  const val DEFAULT_MIN_SIZE_BYTES: Long = 4L * 1024L // 4 KiB

  val DEFAULT_PRIORITY_FOLDERS: List<String> = listOf(
    "DCIM/Camera",
    "DCIM",
    "Documents",
    "Pictures",
    "Movies",
    "Music",
  )

  fun normalizeFolderSegments(rawFolder: String): List<String> =
    rawFolder
      .trim()
      .replace('\\', '/')
      .trim('/')
      .split('/')
      .map { it.trim() }
      .filter { it.isNotEmpty() && it != "." && it != ".." }

  fun resolve(
    sha256: String,
    entries: List<DuplicateFileEntry>,
    priorityFolders: List<String> = DEFAULT_PRIORITY_FOLDERS,
    storageRoot: Path,
  ): DuplicateGroupResolution {
    require(entries.size >= 2) {
      "Um grupo de duplicados exige ao menos dois arquivos (recebido: ${entries.size})."
    }

    val normalizedRoot = storageRoot.toAbsolutePath().normalize()
    val parsedPriorities = priorityFolders
      .map(::normalizeFolderSegments)
      .filter { it.isNotEmpty() }

    data class RankedEntry(
      val entry: DuplicateFileEntry,
      val priorityIndex: Int,
      val normalizedPath: String,
    )

    val ranked = entries.map { entry ->
      val pathObj = runCatching { Paths.get(entry.path).toAbsolutePath().normalize() }.getOrNull()
      val dirSegments = if (pathObj != null && pathObj.startsWith(normalizedRoot)) {
        val rel = normalizedRoot.relativize(pathObj)
        val count = rel.nameCount
        if (count > 1) {
          (0 until count - 1).map { idx -> rel.getName(idx).toString() }
        } else {
          emptyList()
        }
      } else {
        emptyList()
      }

      val matchedIndex = parsedPriorities.indexOfFirst { prefix ->
        startsWithSegmentsIgnoreCase(dirSegments, prefix)
      }.let { idx -> if (idx >= 0) idx else Int.MAX_VALUE }

      RankedEntry(
        entry = entry,
        priorityIndex = matchedIndex,
        normalizedPath = pathObj?.toString() ?: entry.path,
      )
    }.sortedWith(
      compareBy<RankedEntry> { it.priorityIndex }
        .thenBy { it.entry.lastModified }
        .thenBy { it.normalizedPath },
    )

    val winner = ranked.first()
    val removables = ranked.drop(1).map { it.entry }

    return DuplicateGroupResolution(
      sha256 = sha256,
      sizeBytes = winner.entry.sizeBytes,
      keptEntry = winner.entry,
      removableEntries = removables,
      hasPriorityMatch = winner.priorityIndex != Int.MAX_VALUE,
    )
  }

  /**
   * Garante no domínio que ao menos uma cópia de cada grupo de duplicados permaneça não selecionada
   * (fora da lixeira).
   */
  fun enforceKeepAtLeastOneCopy(
    groupRecords: List<ScanCandidateRecord>,
    desiredSelectionById: Map<Long, Boolean>,
  ): Map<Long, Boolean> {
    if (groupRecords.isEmpty()) return emptyMap()
    val result = LinkedHashMap<Long, Boolean>(groupRecords.size)
    val explicitKept = groupRecords.firstOrNull { it.isKept }
    val protectedId = explicitKept?.id ?: groupRecords.minWith(
      compareBy<ScanCandidateRecord> { it.candidate.lastModified }
        .thenBy { it.candidate.path },
    ).id

    for (record in groupRecords) {
      if (record.isKept || record.id == protectedId) {
        result[record.id] = false
      } else {
        result[record.id] = desiredSelectionById[record.id] ?: record.candidate.preselected
      }
    }

    if (result.values.none { !it }) {
      result[protectedId] = false
    }
    return result
  }

  /**
   * Filtra um lote enviado à lixeira garantindo que nenhum grupo de duplicados tenha todas as cópias removidas.
   * Retorna (registrosSegurosParaLixeira, registrosBloqueadosPeloInvariante).
   */
  fun partitionBatchPreservingAtLeastOne(
    batchRecords: List<ScanCandidateRecord>,
    knownGroupSizeProvider: (sessionId: String, groupId: String) -> Int = { _, _ -> 0 },
    existingCopiesInGroupProvider: (sessionId: String, groupId: String) -> Int = { _, _ -> 0 },
  ): Pair<List<ScanCandidateRecord>, List<ScanCandidateRecord>> {
    if (batchRecords.isEmpty()) return emptyList<ScanCandidateRecord>() to emptyList()

    val nonDuplicates = mutableListOf<ScanCandidateRecord>()
    val duplicateGroups = LinkedHashMap<Pair<String, String>, MutableList<ScanCandidateRecord>>()
    val blocked = mutableListOf<ScanCandidateRecord>()

    for (record in batchRecords) {
      if (record.candidate.category != Category.DUPLICATE) {
        nonDuplicates.add(record)
        continue
      }
      if (record.isKept) {
        blocked.add(record)
        continue
      }
      val groupKey = record.sessionId to (record.candidate.groupId ?: record.candidate.path)
      duplicateGroups.getOrPut(groupKey) { mutableListOf() }.add(record)
    }

    val safe = ArrayList<ScanCandidateRecord>(batchRecords.size)
    safe.addAll(nonDuplicates)

    for ((groupKey, items) in duplicateGroups) {
      val (sessionId, groupId) = groupKey
      val existingTotal = maxOf(
        existingCopiesInGroupProvider(sessionId, groupId),
        knownGroupSizeProvider(sessionId, groupId),
        items.size,
      )
      // Quantas cópias existem fora deste lote? Se existingTotal > items.size, sobra ao menos 1 fora do lote
      val copiesOutsideBatch = existingTotal - items.size
      if (copiesOutsideBatch >= 1) {
        safe.addAll(items)
      } else {
        // Todo o grupo está no lote (ou grupo tem tamanho insuficiente): preserva ao menos 1 cópia
        val sorted = items.sortedWith(
          compareBy<ScanCandidateRecord> { it.candidate.lastModified }
            .thenBy { it.candidate.path },
        )
        blocked.add(sorted.first())
        safe.addAll(sorted.drop(1))
      }
    }

    return safe to blocked
  }

  private fun startsWithSegmentsIgnoreCase(pathSegments: List<String>, prefix: List<String>): Boolean {
    if (pathSegments.size < prefix.size) return false
    for (i in prefix.indices) {
      if (!pathSegments[i].equals(prefix[i], ignoreCase = true)) {
        return false
      }
    }
    return true
  }
}
