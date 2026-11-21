package io.github.km20197.limpadroid.data.scan

import io.github.km20197.limpadroid.data.db.ScanCandidateDao
import io.github.km20197.limpadroid.data.db.ScanCandidateEntity
import io.github.km20197.limpadroid.data.db.ScanErrorDao
import io.github.km20197.limpadroid.data.db.ScanErrorEntity
import io.github.km20197.limpadroid.data.db.ScanSessionDao
import io.github.km20197.limpadroid.data.db.ScanSessionEntity
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.CategorySummary
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.OverallCleanupOverview
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateKeepPolicy
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class ScanRepository(
  private val sessionDao: ScanSessionDao,
  private val candidateDao: ScanCandidateDao,
  private val errorDao: ScanErrorDao,
) {
  private val json = Json { ignoreUnknownKeys = true }

  val latestSession: Flow<ScanSession?> = sessionDao.observeLatestSession().map { it?.toDomain() }

  fun observeSession(sessionId: String): Flow<ScanSession?> =
    sessionDao.observeSession(sessionId).map { it?.toDomain() }

  fun observeLatestSessionForCategory(category: Category): Flow<ScanSession?> =
    sessionDao.observeLatestSessionByScopes(scopesFor(category)).map { it?.toDomain() }

  fun observeCandidates(sessionId: String): Flow<List<ScanCandidateRecord>> =
    candidateDao.observeForSession(sessionId).map { list -> list.map { it.toDomain() } }

  fun observeCategoryCandidates(category: Category): Flow<List<ScanCandidateRecord>> =
    sessionDao.observeLatestSessionByScopes(scopesFor(category)).flatMapLatest { session ->
      if (session == null) {
        flowOf(emptyList())
      } else {
        candidateDao.observeForSessionByCategory(session.id, category.name)
          .map { list -> list.map { it.toDomain() } }
      }
    }

  fun observeCategorySummary(category: Category): Flow<CategorySummary> =
    sessionDao.observeLatestSessionByScopes(scopesFor(category)).flatMapLatest { session ->
      if (session == null) {
        flowOf(CategorySummary(category = category))
      } else {
        candidateDao.observeForSessionByCategory(session.id, category.name)
          .map { list ->
            val countable = if (category == Category.DUPLICATE) {
              list.filter { !it.isKept }
            } else {
              list
            }
            CategorySummary(
              category = category,
              candidateCount = countable.size,
              totalBytes = countable.sumOf { it.sizeBytes },
              latestSessionId = session.id,
            )
          }
      }
    }

  fun observeOverallCleanupOverview(): Flow<OverallCleanupOverview> {
    val categoryFlows = Category.entries.map { category ->
      observeCategoryCandidates(category).map { records ->
        extractCategoryCleanupBucket(category, records)
      }
    }
    return combine(categoryFlows) { buckets ->
      OverallCleanupOverview(
        totalCandidatesCount = buckets.sumOf { it.totalCount },
        totalCandidatesBytes = buckets.sumOf { it.totalBytes },
        preselectedCount = buckets.sumOf { it.preselectedRecords.size },
        preselectedTrashCount = buckets.sumOf { bucket ->
          bucket.preselectedRecords.count {
            it.candidate.ruleId != SuperfluousRuleId.STALE_MEDIASTORE.ruleId
          }
        },
        preselectedTrashBytes = buckets.sumOf { bucket ->
          bucket.preselectedRecords
            .filter { it.candidate.ruleId != SuperfluousRuleId.STALE_MEDIASTORE.ruleId }
            .sumOf { it.candidate.sizeBytes }
        },
        preselectedMediaStoreCount = buckets.sumOf { bucket ->
          bucket.preselectedRecords.count {
            it.candidate.ruleId == SuperfluousRuleId.STALE_MEDIASTORE.ruleId
          }
        },
      )
    }
  }

  suspend fun getPreselectedCleanupRecordsBySession(): Map<String, List<ScanCandidateRecord>> {
    val allSelected = Category.entries.flatMap { category ->
      val session = sessionDao.getLatestSessionByScopes(scopesFor(category)) ?: return@flatMap emptyList()
      val records = candidateDao.getForSessionByCategory(session.id, category.name).map { it.toDomain() }
      extractCategoryCleanupBucket(category, records).preselectedRecords
    }
    return allSelected.groupBy { it.sessionId }
  }

  fun observeRecentErrors(sessionId: String, limit: Int = 200): Flow<List<WalkError>> =
    errorDao.observeRecentForSession(sessionId, limit).map { list -> list.map { it.toDomain() } }

  suspend fun setCandidatePreselected(candidateId: Long, preselected: Boolean) {
    val entity = candidateDao.getById(candidateId) ?: return
    if (entity.category == Category.DUPLICATE.name && !entity.groupId.isNullOrBlank()) {
      if (entity.isKept) {
        candidateDao.updatePreselected(candidateId, false)
        return
      }
      val group = candidateDao.getForGroup(entity.sessionId, entity.groupId).map { it.toDomain() }
      val adjusted = DuplicateKeepPolicy.enforceKeepAtLeastOneCopy(
        groupRecords = group,
        desiredSelectionById = mapOf(candidateId to preselected),
      )
      candidateDao.updatePreselected(candidateId, adjusted[candidateId] ?: false)
    } else {
      candidateDao.updatePreselected(candidateId, preselected)
    }
  }

  suspend fun setCandidatesPreselected(candidateIds: List<Long>, preselected: Boolean) {
    if (candidateIds.isEmpty()) return
    val entities = candidateIds.chunked(BATCH_PARAM_CHUNK).flatMap { candidateDao.getByIds(it) }
    val nonDupIds = entities.filter { it.category != Category.DUPLICATE.name }.map { it.id }
    nonDupIds.chunked(BATCH_PARAM_CHUNK).forEach { chunk ->
      candidateDao.updatePreselectedByIds(chunk, preselected)
    }

    val dupEntities = entities.filter { it.category == Category.DUPLICATE.name }
    if (dupEntities.isNotEmpty()) {
      val byGroup = dupEntities.groupBy { it.sessionId to it.groupId }
      for ((key, requested) in byGroup) {
        val (sessionId, groupId) = key
        if (groupId.isNullOrBlank()) continue
        val group = candidateDao.getForGroup(sessionId, groupId).map { it.toDomain() }
        val desired = requested.associate { it.id to preselected }
        val adjusted = DuplicateKeepPolicy.enforceKeepAtLeastOneCopy(group, desired)
        for ((id, targetSelected) in adjusted) {
          val current = group.firstOrNull { it.id == id }
          if (current != null && current.candidate.preselected != targetSelected) {
            candidateDao.updatePreselected(id, targetSelected)
          }
        }
      }
    }
  }

  suspend fun swapDuplicateKeptCandidate(
    sessionId: String,
    groupId: String,
    newKeepCandidateId: Long,
  ) {
    candidateDao.swapKeptCandidate(
      sessionId = sessionId,
      groupId = groupId,
      newKeepCandidateId = newKeepCandidateId,
    )
  }

  suspend fun removeCleanedCandidates(
    sessionId: String?,
    candidateIds: List<Long>,
    now: Long = System.currentTimeMillis(),
  ) {
    if (candidateIds.isEmpty()) return
    val deletedEntities = candidateIds.chunked(BATCH_PARAM_CHUNK).flatMap { candidateDao.getByIds(it) }
    val affectedDuplicateGroups = deletedEntities
      .filter { it.category == Category.DUPLICATE.name && !it.groupId.isNullOrBlank() }
      .map { it.sessionId to it.groupId!! }
      .distinct()

    candidateIds.chunked(BATCH_PARAM_CHUNK).forEach { chunk ->
      candidateDao.deleteByIds(chunk)
    }

    // Se restou apenas 1 cópia em um grupo de duplicados, o grupo foi resolvido e não é mais duplicado
    val resolvedSoloIds = mutableListOf<Long>()
    for ((dupSessionId, groupId) in affectedDuplicateGroups) {
      val remaining = candidateDao.getForGroup(dupSessionId, groupId)
      if (remaining.size == 1) {
        resolvedSoloIds.add(remaining.first().id)
      }
    }
    resolvedSoloIds.chunked(BATCH_PARAM_CHUNK).forEach { chunk ->
      candidateDao.deleteByIds(chunk)
    }

    if (sessionId != null) {
      val remaining = candidateDao.countForSession(sessionId)
      sessionDao.updateCandidatesCount(sessionId = sessionId, candidatesCount = remaining, updatedAt = now)
    }
  }

  suspend fun getCandidatesByIds(candidateIds: List<Long>): List<ScanCandidateRecord> {
    if (candidateIds.isEmpty()) return emptyList()
    return candidateIds
      .chunked(BATCH_PARAM_CHUNK)
      .flatMap { candidateDao.getByIds(it) }
      .map { it.toDomain() }
  }

  suspend fun getSession(sessionId: String): ScanSession? =
    sessionDao.getSession(sessionId)?.toDomain()

  suspend fun getLatestSession(): ScanSession? =
    sessionDao.getLatestSession()?.toDomain()

  suspend fun createSession(
    scope: ScanScope,
    rootPath: String,
    now: Long = System.currentTimeMillis(),
  ): ScanSession {
    val session = ScanSession(
      id = UUID.randomUUID().toString(),
      scope = scope,
      status = ScanStatus.RUNNING,
      rootPath = rootPath,
      startedAt = now,
      updatedAt = now,
    )
    sessionDao.upsert(session.toEntity())
    return session
  }

  suspend fun markRunningForResume(
    sessionId: String,
    now: Long = System.currentTimeMillis(),
  ): ScanSession? {
    val existing = sessionDao.getSession(sessionId) ?: return null
    sessionDao.updateStatus(
      sessionId = sessionId,
      status = ScanStatus.RUNNING.name,
      finishedAt = null,
      updatedAt = now,
    )
    return sessionDao.getSession(sessionId)?.toDomain() ?: existing.toDomain().copy(
      status = ScanStatus.RUNNING,
      finishedAt = null,
      updatedAt = now,
    )
  }

  suspend fun markRequestedStatus(
    sessionId: String,
    status: ScanStatus,
    now: Long = System.currentTimeMillis(),
  ) {
    val finishedAt = if (status == ScanStatus.CANCELLED || status == ScanStatus.FAILED) now else null
    sessionDao.updateStatus(
      sessionId = sessionId,
      status = status.name,
      finishedAt = finishedAt,
      updatedAt = now,
    )
  }

  suspend fun appendCandidatesAndErrors(
    sessionId: String,
    candidates: List<Pair<Candidate, String?>>,
    errors: List<WalkError>,
  ) {
    appendFoundEventsAndErrors(
      sessionId = sessionId,
      foundEvents = candidates.map { (candidate, reason) ->
        ScanEvent.Found(candidate = candidate, reason = reason, isKept = false)
      },
      errors = errors,
    )
  }

  suspend fun appendFoundEventsAndErrors(
    sessionId: String,
    foundEvents: List<ScanEvent.Found>,
    errors: List<WalkError>,
  ) {
    if (foundEvents.isNotEmpty()) {
      foundEvents.chunked(BATCH_PARAM_CHUNK).forEach { chunk ->
        candidateDao.insertAll(
          chunk.map { found ->
            ScanCandidateEntity(
              sessionId = sessionId,
              path = found.candidate.path,
              sizeBytes = found.candidate.sizeBytes,
              lastModified = found.candidate.lastModified,
              category = found.candidate.category.name,
              ruleId = found.candidate.ruleId,
              confidence = found.candidate.confidence.name,
              groupId = found.candidate.groupId,
              preselected = found.candidate.preselected,
              reason = found.reason,
              isKept = found.isKept,
            )
          },
        )
      }
    }
    if (errors.isNotEmpty()) {
      errors.chunked(BATCH_PARAM_CHUNK).forEach { chunk ->
        errorDao.insertAll(
          chunk.map { error ->
            ScanErrorEntity(
              sessionId = sessionId,
              path = error.path,
              errorType = error.errorType,
              message = error.message,
              timestamp = error.timestamp,
            )
          },
        )
      }
    }
  }

  suspend fun saveProgress(
    sessionId: String,
    scannedFiles: Long,
    scannedDirs: Long,
    scannedBytes: Long,
    checkpoint: WalkCheckpoint,
    now: Long = System.currentTimeMillis(),
  ) {
    val candidatesCount = candidateDao.countForSession(sessionId)
    val errorCount = errorDao.countForSession(sessionId)
    sessionDao.updateProgress(
      sessionId = sessionId,
      scannedFiles = scannedFiles,
      scannedDirs = scannedDirs,
      scannedBytes = scannedBytes,
      settledFiles = checkpoint.settledFiles,
      settledDirs = checkpoint.settledDirs,
      settledBytes = checkpoint.settledBytes,
      candidatesCount = candidatesCount,
      errorCount = errorCount,
      lastProcessedPath = checkpoint.lastProcessedPath,
      completedSubtreesJson = encodeSubtrees(checkpoint.completedSubtrees),
      updatedAt = now,
    )
  }

  suspend fun finalizeWorkerRun(
    sessionId: String,
    targetStatusIfStillRunning: ScanStatus,
    scannedFiles: Long,
    scannedDirs: Long,
    scannedBytes: Long,
    checkpoint: WalkCheckpoint,
    now: Long = System.currentTimeMillis(),
  ) {
    val current = sessionDao.getSession(sessionId) ?: return
    val currentStatus = runCatching { ScanStatus.valueOf(current.status) }.getOrDefault(ScanStatus.RUNNING)
    val finalStatus = if (currentStatus == ScanStatus.RUNNING) {
      targetStatusIfStillRunning
    } else {
      currentStatus
    }
    val finishedAt = when (finalStatus) {
      ScanStatus.COMPLETED, ScanStatus.CANCELLED, ScanStatus.FAILED -> current.finishedAt ?: now
      ScanStatus.PAUSED, ScanStatus.RUNNING -> null
    }
    val candidatesCount = candidateDao.countForSession(sessionId)
    val errorCount = errorDao.countForSession(sessionId)
    sessionDao.updateTerminalOrCheckpointState(
      sessionId = sessionId,
      status = finalStatus.name,
      scannedFiles = scannedFiles,
      scannedDirs = scannedDirs,
      scannedBytes = scannedBytes,
      settledFiles = checkpoint.settledFiles,
      settledDirs = checkpoint.settledDirs,
      settledBytes = checkpoint.settledBytes,
      candidatesCount = candidatesCount,
      errorCount = errorCount,
      lastProcessedPath = checkpoint.lastProcessedPath,
      completedSubtreesJson = encodeSubtrees(checkpoint.completedSubtrees),
      finishedAt = finishedAt,
      updatedAt = now,
    )
  }

  private fun encodeSubtrees(subtrees: Set<String>): String =
    json.encodeToString(subtrees.toList().sorted())

  private fun decodeSubtrees(raw: String): Set<String> =
    runCatching { json.decodeFromString<List<String>>(raw).toSet() }.getOrDefault(emptySet())

  private fun ScanSession.toEntity(): ScanSessionEntity = ScanSessionEntity(
    id = id,
    scope = scope.name,
    status = status.name,
    rootPath = rootPath,
    startedAt = startedAt,
    updatedAt = updatedAt,
    finishedAt = finishedAt,
    scannedFiles = scannedFiles,
    scannedDirs = scannedDirs,
    scannedBytes = scannedBytes,
    settledFiles = settledFiles,
    settledDirs = settledDirs,
    settledBytes = settledBytes,
    candidatesCount = candidatesCount,
    errorCount = errorCount,
    lastProcessedPath = lastProcessedPath,
    completedSubtreesJson = encodeSubtrees(completedSubtrees),
  )

  private fun ScanSessionEntity.toDomain(): ScanSession = ScanSession(
    id = id,
    scope = runCatching { ScanScope.valueOf(scope) }.getOrDefault(ScanScope.INFRASTRUCTURE),
    status = runCatching { ScanStatus.valueOf(status) }.getOrDefault(ScanStatus.FAILED),
    rootPath = rootPath,
    startedAt = startedAt,
    updatedAt = updatedAt,
    finishedAt = finishedAt,
    scannedFiles = scannedFiles,
    scannedDirs = scannedDirs,
    scannedBytes = scannedBytes,
    settledFiles = settledFiles,
    settledDirs = settledDirs,
    settledBytes = settledBytes,
    candidatesCount = candidatesCount,
    errorCount = errorCount,
    lastProcessedPath = lastProcessedPath,
    completedSubtrees = decodeSubtrees(completedSubtreesJson),
  )

  private fun ScanCandidateEntity.toDomain(): ScanCandidateRecord = ScanCandidateRecord(
    id = id,
    sessionId = sessionId,
    candidate = Candidate(
      path = path,
      sizeBytes = sizeBytes,
      lastModified = lastModified,
      category = Category.valueOf(category),
      ruleId = ruleId,
      confidence = Confidence.valueOf(confidence),
      groupId = groupId,
      preselected = preselected,
    ),
    reason = reason,
    isKept = isKept,
  )

  private fun ScanErrorEntity.toDomain(): WalkError = WalkError(
    path = path,
    errorType = errorType,
    message = message,
    timestamp = timestamp,
  )

  private data class CategoryCleanupBucket(
    val totalCount: Int,
    val totalBytes: Long,
    val preselectedRecords: List<ScanCandidateRecord>,
  )

  private fun extractCategoryCleanupBucket(
    category: Category,
    records: List<ScanCandidateRecord>,
  ): CategoryCleanupBucket {
    if (records.isEmpty()) {
      return CategoryCleanupBucket(0, 0L, emptyList())
    }
    if (category != Category.DUPLICATE) {
      return CategoryCleanupBucket(
        totalCount = records.size,
        totalBytes = records.sumOf { it.candidate.sizeBytes },
        preselectedRecords = records.filter { it.candidate.preselected },
      )
    }

    val removableAll = mutableListOf<ScanCandidateRecord>()
    val preselectedSafe = mutableListOf<ScanCandidateRecord>()
    val groups = records.groupBy { it.candidate.groupId ?: it.candidate.path }.values
    for (rawItems in groups) {
      if (rawItems.size < 2) continue
      val safeSelection = DuplicateKeepPolicy.enforceKeepAtLeastOneCopy(
        groupRecords = rawItems,
        desiredSelectionById = rawItems.associate { it.id to it.candidate.preselected },
      )
      for (record in rawItems) {
        if (record.isKept) continue
        removableAll.add(record)
        if (safeSelection[record.id] == true) {
          preselectedSafe.add(record)
        }
      }
    }
    return CategoryCleanupBucket(
      totalCount = removableAll.size,
      totalBytes = removableAll.sumOf { it.candidate.sizeBytes },
      preselectedRecords = preselectedSafe,
    )
  }

  companion object {
    private const val BATCH_PARAM_CHUNK = 400

    private fun scopesFor(category: Category): List<String> = when (category) {
      Category.SUPERFLUOUS -> listOf(ScanScope.SUPERFLUOUS.name, ScanScope.FULL.name)
      Category.ORPHAN -> listOf(ScanScope.ORPHAN.name, ScanScope.FULL.name)
      Category.DUPLICATE -> listOf(ScanScope.DUPLICATE.name, ScanScope.FULL.name)
      Category.WHATSAPP -> listOf(ScanScope.WHATSAPP.name, ScanScope.FULL.name)
    }
  }
}
