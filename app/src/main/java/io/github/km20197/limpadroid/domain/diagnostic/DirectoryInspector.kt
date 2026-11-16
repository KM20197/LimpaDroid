package io.github.km20197.limpadroid.domain.diagnostic

import io.github.km20197.limpadroid.domain.format.ByteFormatter
import io.github.km20197.limpadroid.domain.model.DiagnosticEntry
import io.github.km20197.limpadroid.domain.model.DiagnosticReport
import io.github.km20197.limpadroid.domain.model.WhatsAppRootStatus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class DirectoryInspector(
  private val maxEntriesPerLevel: Int = 200,
) {

  fun inspectTwoLevels(rootDir: Path): List<DiagnosticEntry> {
    if (!Files.exists(rootDir, LinkOption.NOFOLLOW_LINKS) ||
      !Files.isDirectory(rootDir, LinkOption.NOFOLLOW_LINKS)
    ) {
      return emptyList()
    }
    return readDirectoryChildren(rootDir, remainingDepth = 1)
  }

  fun inspectWhatsAppRoot(label: String, rootDir: Path): WhatsAppRootStatus {
    val exists = Files.exists(rootDir, LinkOption.NOFOLLOW_LINKS) &&
      Files.isDirectory(rootDir, LinkOption.NOFOLLOW_LINKS)
    if (!exists) {
      return WhatsAppRootStatus(
        label = label,
        path = rootDir.toString(),
        exists = false,
        readable = false,
      )
    }

    return try {
      val subFolders = mutableListOf<String>()
      Files.newDirectoryStream(rootDir).use { stream ->
        for (entry in stream) {
          val attrs = try {
            Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
          } catch (_: IOException) {
            null
          }
          if (attrs != null && attrs.isDirectory && !attrs.isSymbolicLink) {
            subFolders.add(entry.fileName?.toString().orEmpty())
          }
          if (subFolders.size >= maxEntriesPerLevel) break
        }
      }
      subFolders.sort()
      WhatsAppRootStatus(
        label = label,
        path = rootDir.toString(),
        exists = true,
        readable = true,
        subFolders = subFolders,
      )
    } catch (_: IOException) {
      WhatsAppRootStatus(
        label = label,
        path = rootDir.toString(),
        exists = true,
        readable = false,
      )
    } catch (_: SecurityException) {
      WhatsAppRootStatus(
        label = label,
        path = rootDir.toString(),
        exists = true,
        readable = false,
      )
    }
  }

  private fun readDirectoryChildren(dir: Path, remainingDepth: Int): List<DiagnosticEntry> {
    val entries = mutableListOf<DiagnosticEntry>()
    try {
      Files.newDirectoryStream(dir).use { stream ->
        for (child in stream) {
          if (entries.size >= maxEntriesPerLevel) break
          val name = child.fileName?.toString() ?: child.toString()
          val attrs = try {
            Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
          } catch (e: IOException) {
            entries.add(
              DiagnosticEntry(
                name = name,
                path = child.toString(),
                isDirectory = false,
                sizeBytes = 0L,
                error = e.javaClass.simpleName,
              ),
            )
            continue
          } catch (e: SecurityException) {
            entries.add(
              DiagnosticEntry(
                name = name,
                path = child.toString(),
                isDirectory = false,
                sizeBytes = 0L,
                error = e.javaClass.simpleName,
              ),
            )
            continue
          }

          if (attrs.isSymbolicLink) {
            continue
          }

          val isDir = attrs.isDirectory
          val children = if (isDir && remainingDepth > 0) {
            readDirectoryChildren(child, remainingDepth - 1)
          } else {
            emptyList()
          }
          entries.add(
            DiagnosticEntry(
              name = name,
              path = child.toString(),
              isDirectory = isDir,
              sizeBytes = if (isDir) 0L else attrs.size(),
              children = children,
            ),
          )
        }
      }
    } catch (e: IOException) {
      return listOf(
        DiagnosticEntry(
          name = dir.fileName?.toString() ?: dir.toString(),
          path = dir.toString(),
          isDirectory = true,
          sizeBytes = 0L,
          error = "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
        ),
      )
    } catch (e: SecurityException) {
      return listOf(
        DiagnosticEntry(
          name = dir.fileName?.toString() ?: dir.toString(),
          path = dir.toString(),
          isDirectory = true,
          sizeBytes = 0L,
          error = "${e.javaClass.simpleName}: ${e.message.orEmpty()}",
        ),
      )
    }

    return entries.sortedWith(
      compareByDescending<DiagnosticEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
    )
  }

  companion object {
    fun formatReportAsText(report: DiagnosticReport): String = buildString {
      appendLine("=== LimpaDroid - Modo Diagnóstico ===")
      appendLine("Gerado em: ${report.generatedAtIso}")
      appendLine("Versão do app: ${report.appVersion}")
      appendLine("Android SDK: ${report.sdkInt}")
      appendLine("Volume primário disponível: ${if (report.storageAvailable) "SIM" else "NÃO"}")
      appendLine("Raiz resolvida: ${report.storageRoot}")
      report.storageStatusMessage?.let { appendLine("Volume: $it") }
      appendLine("Escopo v1: Armazenamento interno compartilhado primário (cartão SD e USB fora do escopo)")
      appendLine("MANAGE_EXTERNAL_STORAGE: ${if (report.hasAllFilesAccess) "CONCEDIDA" else "NEGADA"}")
      appendLine("Android/media acessível: ${if (report.androidMediaAccessible) "SIM" else "NÃO"}")
      appendLine()
      appendLine("--- Raízes esperadas do WhatsApp ---")
      for (wa in report.whatsappKnownRoots) {
        val status = when {
          !wa.exists -> "ausente"
          !wa.readable -> "presente, sem leitura"
          else -> "presente e legível (${wa.subFolders.size} subpastas)"
        }
        appendLine("* ${wa.label}: $status")
        appendLine("  ${wa.path}")
        for (sub in wa.subFolders) {
          appendLine("    - $sub")
        }
      }
      appendLine()
      appendLine("--- Android/media (2 níveis) ---")
      if (report.androidMediaEntries.isEmpty()) {
        appendLine("(vazio ou inacessível)")
      } else {
        appendEntries(report.androidMediaEntries)
      }
      appendLine()
      appendLine("--- Armazenamento compartilhado primário (2 níveis) ---")
      if (report.rootEntries.isEmpty()) {
        appendLine("(vazio ou inacessível)")
      } else {
        appendEntries(report.rootEntries)
      }
    }

    private fun StringBuilder.appendEntries(entries: List<DiagnosticEntry>) {
      for (entry in entries) {
        val marker = if (entry.isDirectory) "[D]" else "[F]"
        val sizeSuffix = if (entry.isDirectory) "" else " (${ByteFormatter.format(entry.sizeBytes)})"
        val errorSuffix = entry.error?.let { " [erro: $it]" }.orEmpty()
        appendLine("$marker ${entry.name}$sizeSuffix$errorSuffix")
        for (child in entry.children) {
          val childMarker = if (child.isDirectory) "[D]" else "[F]"
          val childSize = if (child.isDirectory) "" else " (${ByteFormatter.format(child.sizeBytes)})"
          val childError = child.error?.let { " [erro: $it]" }.orEmpty()
          appendLine("  $childMarker ${child.name}$childSize$childError")
        }
      }
    }
  }
}
