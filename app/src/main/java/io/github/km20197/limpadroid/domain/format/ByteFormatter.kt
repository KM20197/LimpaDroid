package io.github.km20197.limpadroid.domain.format

import java.util.Locale

object ByteFormatter {
  private val ptBrLocale: Locale = Locale.forLanguageTag("pt-BR")

  fun format(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    return when {
      bytes < 1024L -> "$bytes B"
      bytes < 1024L * 1024L -> String.format(ptBrLocale, "%.1f KiB", bytes / kib)
      bytes < 1024L * 1024L * 1024L -> String.format(ptBrLocale, "%.1f MiB", bytes / mib)
      else -> String.format(ptBrLocale, "%.2f GiB", bytes / gib)
    }
  }
}
