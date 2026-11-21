package io.github.km20197.limpadroid.trash

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.km20197.limpadroid.LimpaDroidApp

import kotlinx.coroutines.CancellationException

class PurgeWorker(
  appContext: Context,
  params: WorkerParameters,
  private val injectedRepository: TrashRepository? = null,
) : CoroutineWorker(appContext, params) {

  constructor(
    appContext: Context,
    params: WorkerParameters,
  ) : this(appContext, params, null)

  override suspend fun doWork(): Result {
    val repository = resolveRepository() ?: return Result.failure()
    return try {
      repository.reconcilePendingAndRebuildIndex()
      repository.purgeExpired()
      Result.success()
    } catch (ce: CancellationException) {
      throw ce
    } catch (_: Throwable) {
      Result.retry()
    }
  }

  private fun resolveRepository(): TrashRepository? {
    if (injectedRepository != null) return injectedRepository
    val app = applicationContext as? LimpaDroidApp ?: return null
    return app.container.trashRepository
  }
}
