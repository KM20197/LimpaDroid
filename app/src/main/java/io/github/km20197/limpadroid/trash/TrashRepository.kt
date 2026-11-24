package io.github.km20197.limpadroid.trash

import io.github.km20197.limpadroid.data.db.OperationHistoryDao
import io.github.km20197.limpadroid.data.db.OperationHistoryEntity
import io.github.km20197.limpadroid.data.db.ScanCandidateDao
import io.github.km20197.limpadroid.data.db.TrashEntryDao
import io.github.km20197.limpadroid.data.db.TrashEntryEntity
import io.github.km20197.limpadroid.data.fs.StorageRootResolution
import io.github.km20197.limpadroid.data.fs.StorageVolumeResolver
import io.github.km20197.limpadroid.data.log.ScanLogger
import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.fs.Hasher
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook
import io.github.km20197.limpadroid.domain.fs.PathGuardResult
import io.github.km20197.limpadroid.domain.fs.SystemTrashGateway
import io.github.km20197.limpadroid.domain.fs.SystemTrashPurgeResult
import io.github.km20197.limpadroid.domain.fs.SystemTrashStats
import io.github.km20197.limpadroid.domain.fs.TrashStorage
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.BatchTrashMoveSummary
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.MediaStoreRepairSummary
import io.github.km20197.limpadroid.domain.model.OperationHistoryRecord
import io.github.km20197.limpadroid.domain.model.OperationType
import io.github.km20197.limpadroid.domain.model.PurgeSummary
import io.github.km20197.limpadroid.domain.model.ScanCandidateRecord
import io.github.km20197.limpadroid.domain.model.TrashEntry
import io.github.km20197.limpadroid.domain.model.TrashEntryState
import io.github.km20197.limpadroid.domain.model.TrashMetadataPayload
import io.github.km20197.limpadroid.domain.model.TrashMoveResult
import io.github.km20197.limpadroid.domain.model.TrashRestoreResult
import io.github.km20197.limpadroid.domain.model.TrashStats
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateKeepPolicy
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TrashRepository(
  private val trashEntryDao: TrashEntryDao,
  private val operationHistoryDao: OperationHistoryDao,
  private val storageVolumeResolver: StorageVolumeResolver,
  private val mediaScannerHook: MediaScannerHook,
  private val scanLogger: ScanLogger,
  private val applicationId: String,
  private val trashStorage: TrashStorage = TrashStorage(),
  private val clock: AppClock = AppClock.System,
  private val scanCandidateDao: ScanCandidateDao? = null,
  private val systemTrashGateway: SystemTrashGateway = SystemTrashGateway.NoOp,
  private val usableSpaceProvider: (Path) -> Long = { path ->
    runCatching { path.toFile().usableSpace }.getOrDefault(0L)
  },
) {
  private val mutex = Mutex()
  private val _systemTrashStats = MutableStateFlow(SystemTrashStats())

  val inTrashEntries: Flow<List<TrashEntry>> =
    trashEntryDao.observeInTrashEntries().map { list -> list.map { it.toDomain() } }

  val trashStats: Flow<TrashStats> = combine(
    trashEntryDao.observeInTrashEntries(),
    trashEntryDao.observePurgedEntries(),
    _systemTrashStats,
  ) { active, purged, systemTrash ->
    val now = clock.nowMillis()
    val soonThreshold = now + EXPIRING_SOON_MILLIS
    TrashStats(
      inTrashCount = active.size,
      inTrashBytes = active.sumOf { it.sizeBytes },
      expiringSoonCount = active.count { it.expiresAt <= soonThreshold },
      purgedCount = purged.size,
      freedBytes = purged.sumOf { it.sizeBytes },
      systemTrashCount = systemTrash.itemCount,
      systemTrashBytes = systemTrash.totalBytes,
    )
  }

  val recentHistory: Flow<List<OperationHistoryRecord>> =
    operationHistoryDao.observeRecent().map { list -> list.map { it.toDomain() } }

  suspend fun getEntry(id: String): TrashEntry? =
    trashEntryDao.getById(id)?.toDomain()

  suspend fun refreshSystemTrashStats() {
    val stats = try {
      systemTrashGateway.queryStats()
    } catch (ce: CancellationException) {
      throw ce
    } catch (_: Throwable) {
      return
    }
    _systemTrashStats.value = stats
  }

  suspend fun moveToTrash(
    candidate: Candidate,
    expectedSizeBytes: Long? = candidate.sizeBytes,
    expectedMtime: Long? = candidate.lastModified,
    userExcludedPaths: Set<Path> = emptySet(),
    forceCopyFallback: Boolean = false,
  ): TrashMoveResult = mutex.withLock {
    moveToTrashUnlocked(
      candidate = candidate,
      expectedSizeBytes = expectedSizeBytes,
      expectedMtime = expectedMtime,
      userExcludedPaths = userExcludedPaths,
      forceCopyFallback = forceCopyFallback,
      scanMediaImmediately = true,
    )
  }

  private suspend fun moveToTrashUnlocked(
    candidate: Candidate,
    expectedSizeBytes: Long? = candidate.sizeBytes,
    expectedMtime: Long? = candidate.lastModified,
    userExcludedPaths: Set<Path> = emptySet(),
    forceCopyFallback: Boolean = false,
    scanMediaImmediately: Boolean = true,
    onBeforePostMovePersistenceForTest: (suspend () -> Unit)? = null,
  ): TrashMoveResult {
    val rootResolution = storageVolumeResolver.resolvePrimarySharedStorage()
    val storageRoot = when (rootResolution) {
      is StorageRootResolution.Available -> rootResolution.rootPath
      is StorageRootResolution.Unavailable -> {
        return TrashMoveResult.Failed("Armazenamento indisponível: ${rootResolution.reason}")
      }
    }

    val pathGuard = DefaultPathGuard(
      rawStorageRoot = storageRoot,
      applicationId = applicationId,
      userExcludedPaths = userExcludedPaths,
    )
    val sourcePath = Paths.get(candidate.path)
    when (val guard = pathGuard.validate(sourcePath)) {
      is PathGuardResult.Allowed -> Unit
      is PathGuardResult.Rejected -> {
        scanLogger.logEvent("TRASH_MOVE_REJECTED", "path=${candidate.path} reason=${guard.reason}")
        return TrashMoveResult.Rejected(guard.detail)
      }
    }

    if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(sourcePath)) {
      return TrashMoveResult.StaleCandidate("Arquivo ausente ou inválido na origem: ${candidate.path}")
    }

    if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS) &&
      pathGuard.hasProtectedDescendant(sourcePath)
    ) {
      scanLogger.logEvent(
        "TRASH_MOVE_REJECTED",
        "path=${candidate.path} reason=CONTAINS_PROTECTED_DESCENDANT",
      )
      return TrashMoveResult.Rejected(
        "Diretório contém subcaminho protegido e não pode ser movido: ${candidate.path}",
      )
    }

    var verifiedSha256: String? = null
    if (Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
      val currentSize = Files.size(sourcePath)
      val currentMtime = Files.getLastModifiedTime(sourcePath, LinkOption.NOFOLLOW_LINKS).toMillis()
      if (expectedSizeBytes != null && currentSize != expectedSizeBytes) {
        return TrashMoveResult.StaleCandidate("Arquivo modificado (tamanho divergente) antes da movimentação.")
      }
      if (expectedMtime != null && currentMtime != expectedMtime) {
        return TrashMoveResult.StaleCandidate("Arquivo modificado (data divergente) antes da movimentação.")
      }
      val expectedSha256 = if (candidate.category == Category.DUPLICATE) {
        candidate.groupId?.takeIf { isValidSha256Hex(it) }
      } else {
        null
      }
      if (expectedSha256 != null) {
        val currentSha256 = runCatching { Hasher.sha256(sourcePath) }.getOrNull()
        if (currentSha256 == null || !currentSha256.equals(expectedSha256, ignoreCase = true)) {
          return TrashMoveResult.StaleCandidate(
            "Arquivo modificado (hash SHA-256 divergente) antes da movimentação.",
          )
        }
        verifiedSha256 = currentSha256
      }
    }

    val now = clock.nowMillis()
    val expiresAt = now + TrashEntry.RETENTION_MILLIS
    val entryId = UUID.randomUUID().toString()
    val trashRoot = trashStorage.resolveTrashRoot(pathGuard.storageRoot)
    val lowSpaceReason = checkFreeSpaceForCleanup(
      targetPath = trashRoot,
      extraRequiredBytes = if (forceCopyFallback) candidate.sizeBytes else 0L,
    )
    if (lowSpaceReason != null) {
      scanLogger.logEvent("TRASH_MOVE_LOW_SPACE", "path=${candidate.path} reason=$lowSpaceReason")
      return TrashMoveResult.Failed(lowSpaceReason)
    }
    val entryDir = trashStorage.allocateEntryDir(trashRoot, entryId, now)
    val plannedTrashPath = trashStorage.resolvePayloadPath(entryDir)

    val pendingEntity = TrashEntryEntity(
      id = entryId,
      originalPath = sourcePath.toString(),
      trashPath = plannedTrashPath.toString(),
      sizeBytes = candidate.sizeBytes,
      sha256 = verifiedSha256,
      originalMtime = candidate.lastModified,
      category = candidate.category.name,
      ruleId = candidate.ruleId,
      deletedAt = now,
      expiresAt = expiresAt,
      state = TrashEntryState.PENDING.name,
    )
    trashEntryDao.upsert(pendingEntity)

    var daoMovedUpdated = false
    var historyInserted = false
    return try {
      val movedInfo = trashStorage.moveToTrash(
        source = sourcePath,
        trashRoot = trashRoot,
        entryDir = entryDir,
        precomputedSha256 = verifiedSha256,
        forceCopyFallback = forceCopyFallback,
        metadataBuilder = { dest, size, sha, mtime ->
          TrashMetadataPayload(
            id = entryId,
            originalPath = sourcePath.toString(),
            trashPath = dest.toString(),
            sizeBytes = size,
            sha256 = sha,
            originalMtime = mtime,
            category = candidate.category.name,
            ruleId = candidate.ruleId,
            deletedAt = now,
            expiresAt = expiresAt,
          )
        },
      )

      onBeforePostMovePersistenceForTest?.invoke()

      trashEntryDao.updateMovedState(
        id = entryId,
        state = TrashEntryState.IN_TRASH.name,
        trashPath = movedInfo.trashPath.toString(),
        sizeBytes = movedInfo.sizeBytes,
        sha256 = movedInfo.sha256,
        originalMtime = movedInfo.originalMtime,
        failureReason = null,
      )
      daoMovedUpdated = true

      operationHistoryDao.insert(
        OperationHistoryEntity(
          operationType = OperationType.MOVE_TO_TRASH.name,
          category = candidate.category.name,
          itemCount = 1,
          totalBytes = movedInfo.sizeBytes,
          timestamp = now,
          detail = sourcePath.fileName?.toString(),
        ),
      )
      historyInserted = true

      if (scanMediaImmediately) {
        mediaScannerHook.scanPaths(listOf(sourcePath.toString()))
      }
      scanLogger.logEvent(
        "TRASH_MOVED",
        "id=$entryId rule=${candidate.ruleId} bytes=${movedInfo.sizeBytes} path=$sourcePath",
      )

      TrashMoveResult.Moved(
        pendingEntity.copy(
          state = TrashEntryState.IN_TRASH.name,
          trashPath = movedInfo.trashPath.toString(),
          sizeBytes = movedInfo.sizeBytes,
          sha256 = movedInfo.sha256,
          originalMtime = movedInfo.originalMtime,
        ).toDomain(),
      )
    } catch (t: Throwable) {
      withContext(NonCancellable) {
        val sourceExists = Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS)
        val payloadExists = Files.exists(plannedTrashPath, LinkOption.NOFOLLOW_LINKS)

        if (!sourceExists && payloadExists) {
          val recoveredSize = if (Files.isRegularFile(plannedTrashPath, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { Files.size(plannedTrashPath) }.getOrDefault(candidate.sizeBytes)
          } else {
            candidate.sizeBytes
          }
          val recoveredMtime = runCatching {
            Files.getLastModifiedTime(plannedTrashPath, LinkOption.NOFOLLOW_LINKS).toMillis()
          }.getOrDefault(candidate.lastModified)
          val metadataPath = trashStorage.resolveMetadataPath(entryDir)
          if (!Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
            runCatching {
              trashStorage.writeMetadata(
                metadataPath,
                TrashMetadataPayload(
                  id = entryId,
                  originalPath = sourcePath.toString(),
                  trashPath = plannedTrashPath.toString(),
                  sizeBytes = recoveredSize,
                  sha256 = verifiedSha256,
                  originalMtime = recoveredMtime,
                  category = candidate.category.name,
                  ruleId = candidate.ruleId,
                  deletedAt = now,
                  expiresAt = expiresAt,
                ),
              )
            }
          }

          val promoted = if (daoMovedUpdated) {
            true
          } else {
            runCatching {
              trashEntryDao.updateMovedState(
                id = entryId,
                state = TrashEntryState.IN_TRASH.name,
                trashPath = plannedTrashPath.toString(),
                sizeBytes = recoveredSize,
                sha256 = verifiedSha256,
                originalMtime = recoveredMtime,
                failureReason = null,
              )
            }.isSuccess
          }

          if (!historyInserted) {
            runCatching {
              operationHistoryDao.insert(
                OperationHistoryEntity(
                  operationType = OperationType.MOVE_TO_TRASH.name,
                  category = candidate.category.name,
                  itemCount = 1,
                  totalBytes = recoveredSize,
                  timestamp = now,
                  detail = sourcePath.fileName?.toString(),
                ),
              )
            }
          }

          if (scanMediaImmediately) {
            runCatching { mediaScannerHook.scanPaths(listOf(sourcePath.toString())) }
          }
          scanLogger.logEvent(
            "TRASH_MOVED_RECOVERED",
            "id=$entryId rule=${candidate.ruleId} bytes=$recoveredSize path=$sourcePath promoted=$promoted",
          )

          if (t is CancellationException) {
            throw t
          }
          if (promoted) {
            return@withContext TrashMoveResult.Moved(
              pendingEntity.copy(
                state = TrashEntryState.IN_TRASH.name,
                trashPath = plannedTrashPath.toString(),
                sizeBytes = recoveredSize,
                sha256 = verifiedSha256,
                originalMtime = recoveredMtime,
              ).toDomain(),
            )
          }
          return@withContext TrashMoveResult.Failed(t.message ?: t.javaClass.simpleName)
        }

        if (sourceExists && payloadExists) {
          runCatching { trashStorage.deleteTrashedPayload(trashRoot, plannedTrashPath) }
        }

        val reason = t.message ?: t.javaClass.simpleName
        runCatching {
          trashEntryDao.updateLifecycleState(
            id = entryId,
            state = TrashEntryState.FAILED.name,
            restoredAt = null,
            restoredPath = null,
            purgedAt = null,
            failureReason = reason,
          )
        }
        scanLogger.logEvent("TRASH_MOVE_FAILED", "id=$entryId path=$sourcePath reason=$reason")
        if (t is CancellationException) {
          throw t
        }
        TrashMoveResult.Failed(reason)
      }
    }
  }

  suspend fun moveToTrashForTestWithPostMoveHook(
    candidate: Candidate,
    onBeforePostMovePersistence: suspend () -> Unit,
  ): TrashMoveResult = mutex.withLock {
    moveToTrashUnlocked(
      candidate = candidate,
      onBeforePostMovePersistenceForTest = onBeforePostMovePersistence,
    )
  }

  suspend fun moveCandidatesToTrash(
    records: List<ScanCandidateRecord>,
    userExcludedPaths: Set<Path> = emptySet(),
  ): BatchTrashMoveSummary = mutex.withLock {
    if (records.isEmpty()) {
      return@withLock BatchTrashMoveSummary(
        movedCandidateIds = emptyList(),
        movedBytes = 0L,
        staleOrSkippedCount = 0,
        failedCount = 0,
      )
    }

    val batchTrashRoot = resolveTrashRootOrNull()
    if (batchTrashRoot != null) {
      val batchLowSpace = checkFreeSpaceForCleanup(batchTrashRoot)
      if (batchLowSpace != null) {
        scanLogger.logEvent("TRASH_MOVE_LOW_SPACE", "batch=${records.size} reason=$batchLowSpace")
        return@withLock BatchTrashMoveSummary(
          movedCandidateIds = emptyList(),
          movedBytes = 0L,
          staleOrSkippedCount = 0,
          failedCount = records.size,
        )
      }
    }

    val presentRecords = ArrayList<ScanCandidateRecord>(records.size)
    var missingDuplicateCount = 0
    for (record in records) {
      if (record.candidate.category == Category.DUPLICATE &&
        !Files.exists(Paths.get(record.candidate.path), LinkOption.NOFOLLOW_LINKS)
      ) {
        missingDuplicateCount += 1
      } else {
        presentRecords.add(record)
      }
    }

    val existingGroupCounts = HashMap<Pair<String, String>, Int>()
    if (scanCandidateDao != null) {
      val dupGroups = presentRecords
        .filter { it.candidate.category == Category.DUPLICATE && !it.candidate.groupId.isNullOrBlank() }
        .map { it.sessionId to it.candidate.groupId!! }
        .distinct()
      for ((sessionId, groupId) in dupGroups) {
        val groupItems = scanCandidateDao.getForGroup(sessionId, groupId)
        val existingOnDisk = groupItems.count { item ->
          Files.exists(Paths.get(item.path), LinkOption.NOFOLLOW_LINKS)
        }
        existingGroupCounts[sessionId to groupId] = existingOnDisk
      }
    }

    val (safeRecords, invariantBlocked) = DuplicateKeepPolicy.partitionBatchPreservingAtLeastOne(
      batchRecords = presentRecords,
      existingCopiesInGroupProvider = { sessionId, groupId ->
        existingGroupCounts[sessionId to groupId] ?: 0
      },
    )

    val movedIds = mutableListOf<Long>()
    val movedOriginalPaths = mutableListOf<String>()
    var movedBytes = 0L
    var staleOrSkipped = missingDuplicateCount + invariantBlocked.size
    var failed = 0

    for (blockedRecord in invariantBlocked) {
      scanLogger.logEvent(
        "TRASH_MOVE_BLOCKED_INVARIANT",
        "path=${blockedRecord.candidate.path} group=${blockedRecord.candidate.groupId}",
      )
    }

    for (record in safeRecords.sortedByDescending { it.candidate.path.length }) {
      when (
        val result = moveToTrashUnlocked(
          candidate = record.candidate,
          userExcludedPaths = userExcludedPaths,
          scanMediaImmediately = false,
        )
      ) {
        is TrashMoveResult.Moved -> {
          movedIds.add(record.id)
          movedOriginalPaths.add(result.entry.originalPath)
          movedBytes += result.entry.sizeBytes
        }
        is TrashMoveResult.StaleCandidate, is TrashMoveResult.Rejected -> {
          staleOrSkipped += 1
        }
        is TrashMoveResult.Failed -> {
          failed += 1
        }
      }
    }

    if (movedOriginalPaths.isNotEmpty()) {
      runCatching { mediaScannerHook.scanPaths(movedOriginalPaths) }
    }

    BatchTrashMoveSummary(
      movedCandidateIds = movedIds,
      movedBytes = movedBytes,
      staleOrSkippedCount = staleOrSkipped,
      failedCount = failed,
    )
  }

  suspend fun repairStaleMediaStore(
    records: List<ScanCandidateRecord>,
    userExcludedPaths: Set<Path> = emptySet(),
  ): MediaStoreRepairSummary = mutex.withLock {
    if (records.isEmpty()) {
      return@withLock MediaStoreRepairSummary(repairedCandidateIds = emptyList(), rejectedOrExistingCount = 0)
    }
    val rootResolution = storageVolumeResolver.resolvePrimarySharedStorage()
    val storageRoot = when (rootResolution) {
      is StorageRootResolution.Available -> rootResolution.rootPath
      is StorageRootResolution.Unavailable -> {
        return@withLock MediaStoreRepairSummary(
          repairedCandidateIds = emptyList(),
          rejectedOrExistingCount = records.size,
        )
      }
    }

    val pathGuard = DefaultPathGuard(
      rawStorageRoot = storageRoot,
      applicationId = applicationId,
      userExcludedPaths = userExcludedPaths,
    )
    val validRecords = mutableListOf<ScanCandidateRecord>()
    var skipped = 0

    for (record in records) {
      val path = Paths.get(record.candidate.path)
      val allowed = pathGuard.validate(path) is PathGuardResult.Allowed
      val stillMissing = !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
      if (allowed && stillMissing) {
        validRecords.add(record)
      } else {
        skipped += 1
      }
    }

    if (validRecords.isNotEmpty()) {
      mediaScannerHook.scanPaths(validRecords.map { it.candidate.path })
      val now = clock.nowMillis()
      operationHistoryDao.insert(
        OperationHistoryEntity(
          operationType = OperationType.MEDIASTORE_REPAIR.name,
          category = Category.SUPERFLUOUS.name,
          itemCount = validRecords.size,
          totalBytes = 0L,
          timestamp = now,
          detail = "Registros MediaStore atualizados (${validRecords.size})",
        ),
      )
      scanLogger.logEvent("MEDIASTORE_REPAIRED", "count=${validRecords.size}")
    }

    MediaStoreRepairSummary(
      repairedCandidateIds = validRecords.map { it.id },
      rejectedOrExistingCount = skipped,
    )
  }

  suspend fun restoreEntry(
    entryId: String,
    forceCopyFallback: Boolean = false,
  ): TrashRestoreResult = mutex.withLock {
    val entity = trashEntryDao.getById(entryId)
      ?: return@withLock TrashRestoreResult.MissingPayload("Entrada não encontrada na lixeira.")
    if (entity.state != TrashEntryState.IN_TRASH.name) {
      return@withLock TrashRestoreResult.Rejected("Item não está mais disponível para restauração (${entity.state}).")
    }

    val rootResolution = storageVolumeResolver.resolvePrimarySharedStorage()
    val storageRoot = when (rootResolution) {
      is StorageRootResolution.Available -> rootResolution.rootPath
      is StorageRootResolution.Unavailable -> {
        return@withLock TrashRestoreResult.Failed("Armazenamento indisponível: ${rootResolution.reason}")
      }
    }

    val pathGuard = DefaultPathGuard(rawStorageRoot = storageRoot, applicationId = applicationId)
    val trashRoot = trashStorage.resolveTrashRoot(pathGuard.storageRoot)
    val payloadPath = Paths.get(entity.trashPath)

    return@withLock try {
      val validated = trashStorage.validatePayloadPath(trashRoot, payloadPath)
      if (!Files.exists(validated.payloadPath, LinkOption.NOFOLLOW_LINKS)) {
        return@withLock TrashRestoreResult.MissingPayload("Arquivo ausente na lixeira: ${validated.payloadPath}")
      }

      val restored = trashStorage.restoreFromTrash(
        trashRoot = trashRoot,
        payloadPath = validated.payloadPath,
        desiredOriginalPath = Paths.get(entity.originalPath),
        originalMtime = entity.originalMtime,
        expectedSha256 = entity.sha256,
        pathGuard = pathGuard,
        forceCopyFallback = forceCopyFallback,
      )
      val now = clock.nowMillis()
      val restoredPathStr = restored.restoredPath.toString()

      trashEntryDao.updateLifecycleState(
        id = entryId,
        state = TrashEntryState.RESTORED.name,
        restoredAt = now,
        restoredPath = restoredPathStr,
        purgedAt = null,
        failureReason = null,
      )

      operationHistoryDao.insert(
        OperationHistoryEntity(
          operationType = OperationType.RESTORE.name,
          category = entity.category,
          itemCount = 1,
          totalBytes = restored.sizeBytes,
          timestamp = now,
          detail = restored.restoredPath.fileName?.toString(),
        ),
      )

      mediaScannerHook.scanPaths(listOf(restoredPathStr))
      scanLogger.logEvent("TRASH_RESTORED", "id=$entryId path=$restoredPathStr")

      val updated = trashEntryDao.getById(entryId)?.toDomain() ?: entity.toDomain().copy(
        state = TrashEntryState.RESTORED,
        restoredAt = now,
        restoredPath = restoredPathStr,
      )
      TrashRestoreResult.Restored(entry = updated, restoredPath = restoredPathStr)
    } catch (ce: CancellationException) {
      throw ce
    } catch (se: SecurityException) {
      val reason = se.message ?: "Destino ou caminho da lixeira bloqueado"
      scanLogger.logEvent("TRASH_RESTORE_REJECTED", "id=$entryId reason=$reason")
      TrashRestoreResult.Rejected(reason)
    } catch (t: Throwable) {
      val reason = t.message ?: t.javaClass.simpleName
      scanLogger.logEvent("TRASH_RESTORE_FAILED", "id=$entryId reason=$reason")
      TrashRestoreResult.Failed(reason)
    }
  }

  suspend fun deletePermanently(entryId: String): PurgeSummary = mutex.withLock {
    val entity = trashEntryDao.getById(entryId) ?: return@withLock PurgeSummary(0, 0L)
    if (entity.state != TrashEntryState.IN_TRASH.name) return@withLock PurgeSummary(0, 0L)

    val trashRoot = resolveTrashRootOrNull() ?: return@withLock PurgeSummary(0, 0L)
    try {
      trashStorage.deleteTrashedPayload(
        trashRoot = trashRoot,
        trashPath = Paths.get(entity.trashPath),
        originalPath = Paths.get(entity.originalPath),
      )
    } catch (ce: CancellationException) {
      throw ce
    } catch (ipe: java.nio.file.InvalidPathException) {
      val reason = ipe.message ?: "Caminho da lixeira malformado"
      trashEntryDao.updateLifecycleState(
        id = entryId,
        state = TrashEntryState.FAILED.name,
        restoredAt = null,
        restoredPath = null,
        purgedAt = null,
        failureReason = reason,
      )
      scanLogger.logEvent("TRASH_PURGE_MANUAL_FAILED", "id=$entryId reason=$reason")
      return@withLock PurgeSummary(0, 0L)
    } catch (se: SecurityException) {
      val reason = se.message ?: "Caminho da lixeira inválido ou fora da área gerenciada"
      trashEntryDao.updateLifecycleState(
        id = entryId,
        state = TrashEntryState.FAILED.name,
        restoredAt = null,
        restoredPath = null,
        purgedAt = null,
        failureReason = reason,
      )
      scanLogger.logEvent("TRASH_PURGE_MANUAL_FAILED", "id=$entryId reason=$reason")
      return@withLock PurgeSummary(0, 0L)
    } catch (t: Throwable) {
      scanLogger.logEvent("TRASH_PURGE_MANUAL_FAILED", "id=$entryId reason=${t.message}")
      return@withLock PurgeSummary(0, 0L)
    }
    val now = clock.nowMillis()
    trashEntryDao.updateLifecycleState(
      id = entryId,
      state = TrashEntryState.PURGED.name,
      restoredAt = null,
      restoredPath = null,
      purgedAt = now,
      failureReason = null,
    )
    operationHistoryDao.insert(
      OperationHistoryEntity(
        operationType = OperationType.PURGE_MANUAL.name,
        category = entity.category,
        itemCount = 1,
        totalBytes = entity.sizeBytes,
        timestamp = now,
        detail = Paths.get(entity.originalPath).fileName?.toString(),
      ),
    )
    runCatching { mediaScannerHook.scanPaths(listOf(entity.originalPath, entity.trashPath)) }
    scanLogger.logEvent("TRASH_PURGED_MANUAL", "id=$entryId bytes=${entity.sizeBytes}")
    PurgeSummary(purgedCount = 1, freedBytes = entity.sizeBytes)
  }

  suspend fun emptyTrash(): PurgeSummary = mutex.withLock {
    val active = trashEntryDao.getByState(TrashEntryState.IN_TRASH.name)
    val trashRoot = resolveTrashRootOrNull()
    val now = clock.nowMillis()
    var freed = 0L
    var count = 0
    val purgedPaths = mutableListOf<String>()

    if (trashRoot != null) {
      for (entry in active) {
        try {
          trashStorage.deleteTrashedPayload(
            trashRoot = trashRoot,
            trashPath = Paths.get(entry.trashPath),
            originalPath = Paths.get(entry.originalPath),
          )
          trashEntryDao.updateLifecycleState(
            id = entry.id,
            state = TrashEntryState.PURGED.name,
            restoredAt = null,
            restoredPath = null,
            purgedAt = now,
            failureReason = null,
          )
          freed += entry.sizeBytes
          count += 1
          purgedPaths.add(entry.originalPath)
          purgedPaths.add(entry.trashPath)
        } catch (ce: CancellationException) {
          throw ce
        } catch (ipe: java.nio.file.InvalidPathException) {
          val reason = ipe.message ?: "Caminho da lixeira malformado"
          trashEntryDao.updateLifecycleState(
            id = entry.id,
            state = TrashEntryState.FAILED.name,
            restoredAt = null,
            restoredPath = null,
            purgedAt = null,
            failureReason = reason,
          )
          scanLogger.logEvent("TRASH_PURGE_ITEM_FAILED", "id=${entry.id} reason=$reason")
        } catch (se: SecurityException) {
          val reason = se.message ?: "Caminho da lixeira inválido ou fora da área gerenciada"
          trashEntryDao.updateLifecycleState(
            id = entry.id,
            state = TrashEntryState.FAILED.name,
            restoredAt = null,
            restoredPath = null,
            purgedAt = null,
            failureReason = reason,
          )
          scanLogger.logEvent("TRASH_PURGE_ITEM_FAILED", "id=${entry.id} reason=$reason")
        } catch (t: Throwable) {
          scanLogger.logEvent("TRASH_PURGE_ITEM_FAILED", "id=${entry.id} reason=${t.message}")
        }
      }
      pruneEmptyDateDirsUnderTrashRoot(trashRoot)
    }

    if (purgedPaths.isNotEmpty()) {
      runCatching { mediaScannerHook.scanPaths(purgedPaths) }
    }

    val systemResult = try {
      systemTrashGateway.emptySystemTrash()
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      scanLogger.logEvent("SYSTEM_TRASH_EMPTY_FAILED", "reason=${t.message}")
      SystemTrashPurgeResult()
    }
    _systemTrashStats.value = try {
      systemTrashGateway.queryStats()
    } catch (ce: CancellationException) {
      throw ce
    } catch (_: Throwable) {
      SystemTrashStats()
    }

    val internalCount = count
    freed += systemResult.freedBytes
    count += systemResult.purgedCount

    if (count > 0) {
      val detail = if (systemResult.purgedCount > 0) {
        "Interna: $internalCount · Sistema Android: ${systemResult.purgedCount}"
      } else {
        null
      }
      operationHistoryDao.insert(
        OperationHistoryEntity(
          operationType = OperationType.EMPTY_TRASH.name,
          category = null,
          itemCount = count,
          totalBytes = freed,
          timestamp = now,
          detail = detail,
        ),
      )
      scanLogger.logEvent(
        "TRASH_EMPTIED",
        "internal=$internalCount system=${systemResult.purgedCount} bytes=$freed",
      )
    }
    PurgeSummary(purgedCount = count, freedBytes = freed)
  }

  suspend fun purgeExpired(): PurgeSummary = mutex.withLock {
    val now = clock.nowMillis()
    val expired = trashEntryDao.getExpiredEntries(now)
    if (expired.isEmpty()) return@withLock PurgeSummary(0, 0L)

    val trashRoot = resolveTrashRootOrNull() ?: return@withLock PurgeSummary(0, 0L)
    var freed = 0L
    var count = 0
    val purgedPaths = mutableListOf<String>()
    for (entry in expired) {
      try {
        trashStorage.deleteTrashedPayload(
          trashRoot = trashRoot,
          trashPath = Paths.get(entry.trashPath),
          originalPath = Paths.get(entry.originalPath),
        )
        trashEntryDao.updateLifecycleState(
          id = entry.id,
          state = TrashEntryState.PURGED.name,
          restoredAt = null,
          restoredPath = null,
          purgedAt = now,
          failureReason = null,
        )
        freed += entry.sizeBytes
        count += 1
        purgedPaths.add(entry.originalPath)
        purgedPaths.add(entry.trashPath)
      } catch (ce: CancellationException) {
        throw ce
      } catch (t: Throwable) {
        scanLogger.logEvent("TRASH_EXPIRED_PURGE_REJECTED", "id=${entry.id} reason=${t.message}")
      }
    }
    if (purgedPaths.isNotEmpty()) {
      runCatching { mediaScannerHook.scanPaths(purgedPaths) }
    }
    if (count > 0) {
      operationHistoryDao.insert(
        OperationHistoryEntity(
          operationType = OperationType.PURGE_EXPIRED.name,
          category = null,
          itemCount = count,
          totalBytes = freed,
          timestamp = now,
          detail = null,
        ),
      )
      scanLogger.logEvent("TRASH_PURGED_EXPIRED", "count=$count bytes=$freed")
    }
    PurgeSummary(purgedCount = count, freedBytes = freed)
  }

  suspend fun reconcilePendingAndRebuildIndex() = mutex.withLock {
    val trashRoot = resolveTrashRootOrNull()
    val candidatesToReconcile =
      trashEntryDao.getByState(TrashEntryState.PENDING.name) +
        trashEntryDao.getByState(TrashEntryState.FAILED.name)

    for (entry in candidatesToReconcile) {
      val wasPending = entry.state == TrashEntryState.PENDING.name
      val source = Paths.get(entry.originalPath)
      val rawPayload = Paths.get(entry.trashPath)

      val validatedPayload = if (trashRoot != null) {
        runCatching { trashStorage.validatePayloadPath(trashRoot, rawPayload) }.getOrNull()
      } else {
        null
      }

      if (validatedPayload == null) {
        if (wasPending) {
          trashEntryDao.updateLifecycleState(
            id = entry.id,
            state = TrashEntryState.FAILED.name,
            restoredAt = null,
            restoredPath = null,
            purgedAt = null,
            failureReason = "Caminho da lixeira inválido na reconciliação.",
          )
          scanLogger.logEvent("TRASH_RECONCILED_INVALID_PATH", "id=${entry.id}")
        }
        continue
      }

      val sourceExists = Files.exists(source, LinkOption.NOFOLLOW_LINKS)
      val payloadExists = Files.exists(validatedPayload.payloadPath, LinkOption.NOFOLLOW_LINKS)

      when {
        !sourceExists && payloadExists -> {
          val recoveredSize = if (Files.isRegularFile(validatedPayload.payloadPath, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { Files.size(validatedPayload.payloadPath) }.getOrDefault(entry.sizeBytes)
          } else {
            entry.sizeBytes
          }
          val metadataPath = trashStorage.resolveMetadataPath(validatedPayload.entryDir)
          if (!Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
            runCatching {
              trashStorage.writeMetadata(
                metadataPath,
                TrashMetadataPayload(
                  id = entry.id,
                  originalPath = entry.originalPath,
                  trashPath = validatedPayload.payloadPath.toString(),
                  sizeBytes = recoveredSize,
                  sha256 = entry.sha256,
                  originalMtime = entry.originalMtime,
                  category = entry.category,
                  ruleId = entry.ruleId,
                  deletedAt = entry.deletedAt,
                  expiresAt = entry.expiresAt,
                ),
              )
            }
          }
          trashEntryDao.updateMovedState(
            id = entry.id,
            state = TrashEntryState.IN_TRASH.name,
            trashPath = validatedPayload.payloadPath.toString(),
            sizeBytes = recoveredSize,
            sha256 = entry.sha256,
            originalMtime = entry.originalMtime,
            failureReason = null,
          )
          scanLogger.logEvent("TRASH_RECONCILED_PROMOTED", "id=${entry.id} from=${entry.state}")
        }

        sourceExists && payloadExists -> {
          // Falha durante cópia: preserva o original intacto e remove payload parcial validado.
          runCatching { trashStorage.deleteTrashedPayload(trashRoot!!, validatedPayload.payloadPath) }
          trashEntryDao.updateLifecycleState(
            id = entry.id,
            state = TrashEntryState.FAILED.name,
            restoredAt = null,
            restoredPath = null,
            purgedAt = null,
            failureReason = "Operação interrompida; arquivo original preservado.",
          )
          scanLogger.logEvent("TRASH_RECONCILED_ROLLED_BACK", "id=${entry.id}")
        }

        wasPending -> {
          trashEntryDao.updateLifecycleState(
            id = entry.id,
            state = TrashEntryState.FAILED.name,
            restoredAt = null,
            restoredPath = null,
            purgedAt = null,
            failureReason = "Origem e destino ausentes na reconciliação.",
          )
          scanLogger.logEvent("TRASH_RECONCILED_MISSING", "id=${entry.id}")
        }
      }
    }

    // Reconstrução de índice a partir de metadata.json (ex.: após atualização/reinstalação).
    if (trashRoot != null) {
      val diskEntries = runCatching { trashStorage.scanRecoverableEntries(trashRoot) }.getOrDefault(emptyList())
      for (meta in diskEntries) {
        val existing = trashEntryDao.getById(meta.id)
        if (existing == null) {
          trashEntryDao.upsert(
            TrashEntryEntity(
              id = meta.id,
              originalPath = meta.originalPath,
              trashPath = meta.trashPath,
              sizeBytes = meta.sizeBytes,
              sha256 = meta.sha256,
              originalMtime = meta.originalMtime,
              category = meta.category,
              ruleId = meta.ruleId,
              deletedAt = meta.deletedAt,
              expiresAt = meta.expiresAt,
              state = TrashEntryState.IN_TRASH.name,
            ),
          )
          scanLogger.logEvent("TRASH_INDEX_REBUILT", "id=${meta.id}")
        }
      }
    }

    _systemTrashStats.value = try {
      systemTrashGateway.queryStats()
    } catch (ce: CancellationException) {
      throw ce
    } catch (_: Throwable) {
      SystemTrashStats()
    }
  }

  private fun checkFreeSpaceForCleanup(
    targetPath: Path,
    extraRequiredBytes: Long = 0L,
  ): String? {
    val probePath = existingAncestorOrSelf(targetPath)
    val usableBytes = runCatching { usableSpaceProvider(probePath) }.getOrDefault(0L)
    val requiredBytes = if (extraRequiredBytes > Long.MAX_VALUE - CLEANUP_SAFETY_MARGIN_BYTES) {
      Long.MAX_VALUE
    } else {
      CLEANUP_SAFETY_MARGIN_BYTES + extraRequiredBytes.coerceAtLeast(0L)
    }
    if (usableBytes < requiredBytes) {
      return "Espaço livre insuficiente para mover com segurança (${usableBytes} B disponíveis; mínimo exigido de ${requiredBytes} B)."
    }
    return null
  }

  private fun existingAncestorOrSelf(path: Path): Path {
    var current: Path? = path.toAbsolutePath().normalize()
    while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
      current = current.parent
    }
    return current ?: path
  }

  private fun pruneEmptyDateDirsUnderTrashRoot(trashRoot: Path) {
    if (!Files.isDirectory(trashRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(trashRoot)) return
    runCatching {
      Files.newDirectoryStream(trashRoot).use { stream ->
        for (child in stream) {
          if (Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
            continue
          }
          val isEmpty = runCatching {
            Files.newDirectoryStream(child).use { sub -> !sub.iterator().hasNext() }
          }.getOrDefault(false)
          if (isEmpty) {
            runCatching { Files.deleteIfExists(child) }
          }
        }
      }
    }
  }

  private fun resolveTrashRootOrNull(): Path? {
    val rootResolution = storageVolumeResolver.resolvePrimarySharedStorage() as? StorageRootResolution.Available
      ?: return null
    val guard = DefaultPathGuard(rawStorageRoot = rootResolution.rootPath, applicationId = applicationId)
    return trashStorage.resolveTrashRoot(guard.storageRoot)
  }

  private fun TrashEntryEntity.toDomain(): TrashEntry = TrashEntry(
    id = id,
    originalPath = originalPath,
    trashPath = trashPath,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    originalMtime = originalMtime,
    category = runCatching { Category.valueOf(category) }.getOrDefault(Category.SUPERFLUOUS),
    ruleId = ruleId,
    deletedAt = deletedAt,
    expiresAt = expiresAt,
    state = runCatching { TrashEntryState.valueOf(state) }.getOrDefault(TrashEntryState.IN_TRASH),
    restoredAt = restoredAt,
    restoredPath = restoredPath,
    purgedAt = purgedAt,
    failureReason = failureReason,
  )

  private fun OperationHistoryEntity.toDomain(): OperationHistoryRecord = OperationHistoryRecord(
    id = id,
    operationType = runCatching { OperationType.valueOf(operationType) }.getOrDefault(OperationType.MOVE_TO_TRASH),
    category = category?.let { raw -> runCatching { Category.valueOf(raw) }.getOrNull() },
    itemCount = itemCount,
    totalBytes = totalBytes,
    timestamp = timestamp,
    detail = detail,
  )

  private fun isValidSha256Hex(raw: String): Boolean =
    raw.length == 64 && raw.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }

  companion object {
    private const val EXPIRING_SOON_MILLIS = 3L * TrashEntry.MILLIS_PER_DAY
    const val CLEANUP_SAFETY_MARGIN_BYTES = 100L * 1024L * 1024L
  }
}
