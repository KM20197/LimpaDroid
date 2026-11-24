package io.github.km20197.limpadroid.domain.fs

data class SystemTrashStats(
  val itemCount: Int = 0,
  val totalBytes: Long = 0L,
)

data class SystemTrashPurgeResult(
  val purgedCount: Int = 0,
  val freedBytes: Long = 0L,
  val failedCount: Int = 0,
)

interface SystemTrashGateway {
  suspend fun queryStats(): SystemTrashStats
  suspend fun emptySystemTrash(): SystemTrashPurgeResult

  companion object {
    val NoOp: SystemTrashGateway = object : SystemTrashGateway {
      override suspend fun queryStats(): SystemTrashStats = SystemTrashStats()
      override suspend fun emptySystemTrash(): SystemTrashPurgeResult = SystemTrashPurgeResult()
    }
  }
}
