package io.github.km20197.limpadroid.domain.fs

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object Hasher {
  const val FULL_HASH_BUFFER_BYTES = 1024 * 1024 // 1 MiB
  const val PARTIAL_WINDOW_BYTES = 64 * 1024 // 64 KiB
  const val MAX_HASH_PARALLELISM = 4

  @OptIn(ExperimentalCoroutinesApi::class)
  fun createLimitedIoDispatcher(parallelism: Int = MAX_HASH_PARALLELISM): CoroutineDispatcher =
    Dispatchers.IO.limitedParallelism(parallelism.coerceIn(1, MAX_HASH_PARALLELISM))

  suspend fun sha256(
    path: Path,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ): String = withContext(dispatcher) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(FULL_HASH_BUFFER_BYTES)
    Files.newInputStream(path).use { input ->
      while (true) {
        coroutineContext.ensureActive()
        val bytesRead = input.read(buffer)
        if (bytesRead <= 0) break
        digest.update(buffer, 0, bytesRead)
      }
    }
    digest.digest().toHexString()
  }

  suspend fun partialSha256(
    path: Path,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
  ): String = withContext(dispatcher) {
    val digest = MessageDigest.getInstance("SHA-256")
    FileChannel.open(path, StandardOpenOption.READ).use { channel ->
      val size = channel.size()
      val sizeBytes = ByteBuffer.allocate(java.lang.Long.BYTES).putLong(size).array()
      digest.update(sizeBytes)

      if (size <= PARTIAL_WINDOW_BYTES * 2L) {
        val buf = ByteBuffer.allocate(PARTIAL_WINDOW_BYTES)
        while (true) {
          coroutineContext.ensureActive()
          buf.clear()
          val read = channel.read(buf)
          if (read <= 0) break
          digest.update(buf.array(), 0, read)
        }
      } else {
        val headBuf = ByteBuffer.allocate(PARTIAL_WINDOW_BYTES)
        readFullyAt(channel, 0L, headBuf)
        digest.update(headBuf.array(), 0, headBuf.position())

        coroutineContext.ensureActive()

        val tailBuf = ByteBuffer.allocate(PARTIAL_WINDOW_BYTES)
        readFullyAt(channel, size - PARTIAL_WINDOW_BYTES, tailBuf)
        digest.update(tailBuf.array(), 0, tailBuf.position())
      }
    }
    digest.digest().toHexString()
  }

  private suspend fun readFullyAt(
    channel: FileChannel,
    offset: Long,
    target: ByteBuffer,
  ) {
    var position = offset
    while (target.hasRemaining()) {
      coroutineContext.ensureActive()
      val read = channel.read(target, position)
      if (read <= 0) break
      position += read
    }
  }

  private fun ByteArray.toHexString(): String {
    val hexChars = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (i in indices) {
      val v = this[i].toInt() and 0xFF
      hexChars[i * 2] = digits[v ushr 4]
      hexChars[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(hexChars)
  }
}
