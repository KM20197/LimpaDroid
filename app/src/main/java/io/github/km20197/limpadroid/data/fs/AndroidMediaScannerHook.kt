package io.github.km20197.limpadroid.data.fs

import android.content.Context
import android.media.MediaScannerConnection
import io.github.km20197.limpadroid.domain.fs.MediaScannerHook

class AndroidMediaScannerHook(
  private val context: Context,
) : MediaScannerHook {
  override fun scanPaths(paths: List<String>) {
    val nonBlank = paths.filter { it.isNotBlank() }
    if (nonBlank.isEmpty()) return
    runCatching {
      MediaScannerConnection.scanFile(
        context.applicationContext,
        nonBlank.toTypedArray(),
        null,
        null,
      )
    }
  }
}
