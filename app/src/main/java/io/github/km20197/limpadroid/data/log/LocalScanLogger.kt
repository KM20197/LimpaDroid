package io.github.km20197.limpadroid.data.log

import java.io.File
import java.time.Instant

interface ScanLogger {
  fun logEvent(tag: String, message: String)
}

/**
 * Local rotating file logger (1 MiB max per file, 3 files total).
 * Stores only paths, rules, counts, and operational outcomes — never file contents.
 */
class LocalScanLogger(
  private val logDirectory: File,
  private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
  private val maxFiles: Int = DEFAULT_MAX_FILES,
) : ScanLogger {

  private val lock = Any()

  override fun logEvent(tag: String, message: String) {
    runCatching {
      synchronized(lock) {
        if (!logDirectory.exists()) {
          logDirectory.mkdirs()
        }
        val activeFile = File(logDirectory, BASE_FILE_NAME)
        val line = "${Instant.now()} [$tag] ${message.replace('\n', ' ')}\n"
        val lineBytes = line.toByteArray(Charsets.UTF_8)

        if (activeFile.exists() && activeFile.length() + lineBytes.size > maxFileBytes) {
          rotateLogs()
        }
        activeFile.appendText(line, Charsets.UTF_8)
      }
    }
  }

  fun readRecentLines(maxLines: Int = 100): List<String> = synchronized(lock) {
    val activeFile = File(logDirectory, BASE_FILE_NAME)
    if (!activeFile.exists()) return emptyList()
    return runCatching {
      activeFile.readLines(Charsets.UTF_8).takeLast(maxLines)
    }.getOrDefault(emptyList())
  }

  private fun rotateLogs() {
    val oldest = File(logDirectory, "$BASE_FILE_NAME.${maxFiles - 1}")
    if (oldest.exists()) {
      oldest.delete()
    }
    for (index in (maxFiles - 2) downTo 1) {
      val src = File(logDirectory, "$BASE_FILE_NAME.$index")
      val dst = File(logDirectory, "$BASE_FILE_NAME.${index + 1}")
      if (src.exists()) {
        src.renameTo(dst)
      }
    }
    val activeFile = File(logDirectory, BASE_FILE_NAME)
    if (activeFile.exists() && maxFiles > 1) {
      activeFile.renameTo(File(logDirectory, "$BASE_FILE_NAME.1"))
    } else if (activeFile.exists()) {
      activeFile.delete()
    }
  }

  companion object {
    const val DEFAULT_MAX_FILE_BYTES = 1024L * 1024L // 1 MiB
    const val DEFAULT_MAX_FILES = 3
    private const val BASE_FILE_NAME = "scan.log"
  }
}
