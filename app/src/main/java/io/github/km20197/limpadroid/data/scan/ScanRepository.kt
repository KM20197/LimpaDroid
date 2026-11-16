package io.github.km20197.limpadroid.data.scan

import io.github.km20197.limpadroid.data.db.ScanCandidateDao
import io.github.km20197.limpadroid.data.db.ScanCandidateEntity
import io.github.km20197.limpadroid.data.db.ScanErrorDao
import io.github.km20197.limpadroid.data.db.ScanErrorEntity
import io.github.km20197.limpadroid.data.db.ScanSessionDao
import io.github.km20197.limpadroid.data.db.ScanSessionEntity
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.model.ScanSession
import io.github.km20197.limpadroid.domain.model.ScanStatus
import io.github.km20197.limpadroid.domain.model.WalkCheckpoint
import io.github.km20197.limpadroid.domain.model.WalkError
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ScanRepository(
  private val sessionDao: ScanSessionDao,
  private val candidateDao: ScanCandidateDao,
  private val errorDao: ScanErrorDao,
) {
  private val json = Json { ignoreUnknownKeys = true }

  val latestSession: Flow<ScanSession?> = sessionDao.observeLatestSession().map { it?.toDomain() }

  fun observeSession(sessionId: String): Flow<ScanSession?> =
    sessionDao.observeSession(sessionId).map { it?.toDomain() }

  fun observeCandidates(sessionId: String): Flow<List<ScanCandidateRecord>> =
    candidateDao.observeForSession(sessionId).map { list -> list.map { it.toDomain() } }

  fun observeRecentErrors(sessionId: String, limit: Int = 200): Flow<List<WalkError>> =
    errorDao.observeRecentForSession(sessionId, limit).map { list -> list.map { it.toDomain() } }

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
    if (candidates.isNotEmpty()) {
      candidateDao.insertAll(
        candidates.map { (candidate, reason) ->
          ScanCandidateEntity(
            sessionId = sessionId,
            path = candidate.path,
            sizeBytes = candidate.sizeBytes,
            lastModified = candidate.lastModified,
            category = candidate.category.name,
            ruleId = candidate.ruleId,
            confidence = candidate.confidence.name,
            groupId = candidate.groupId,
            preselected = candidate.preselected,
            reason = reason,
          )
        },
      )
    }
    if (errors.isNotEmpty()) {
      errorDao.insertAll(
        errors.map { error ->
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
  )

  private fun ScanErrorEntity.toDomain(): WalkError = WalkError(
    path = path,
    errorType = errorType,
    message = message,
    timestamp = timestamp,
  )
}
