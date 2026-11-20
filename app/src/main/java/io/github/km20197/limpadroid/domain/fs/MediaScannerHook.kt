package io.github.km20197.limpadroid.domain.fs

fun interface MediaScannerHook {
  fun scanPaths(paths: List<String>)
}
