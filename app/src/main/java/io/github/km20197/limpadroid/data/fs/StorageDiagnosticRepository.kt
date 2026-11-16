package io.github.km20197.limpadroid.data.fs

import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.domain.diagnostic.DirectoryInspector
import io.github.km20197.limpadroid.domain.model.DiagnosticReport
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface StorageDiagnosticRepository {
  suspend fun inspectStorage(appVersion: String, sdkInt: Int): DiagnosticReport
}

class DefaultStorageDiagnosticRepository(
  private val permissionChecker: PermissionChecker,
  private val inspector: DirectoryInspector = DirectoryInspector(),
  private val storageRootPath: Path = Paths.get(DEFAULT_STORAGE_ROOT),
) : StorageDiagnosticRepository {

  override suspend fun inspectStorage(appVersion: String, sdkInt: Int): DiagnosticReport =
    withContext(Dispatchers.IO) {
      val androidMediaPath = storageRootPath.resolve("Android/media")
      val androidMediaReadable =
        Files.exists(androidMediaPath, LinkOption.NOFOLLOW_LINKS) &&
          Files.isDirectory(androidMediaPath, LinkOption.NOFOLLOW_LINKS) &&
          Files.isReadable(androidMediaPath)

      val rootEntries = inspector.inspectTwoLevels(storageRootPath)
      val androidMediaEntries = inspector.inspectTwoLevels(androidMediaPath)

      val whatsappRoots = listOf(
        "WhatsApp (Android/media)" to storageRootPath.resolve("Android/media/com.whatsapp/WhatsApp/Media"),
        "WhatsApp Business (Android/media)" to
          storageRootPath.resolve("Android/media/com.whatsapp.w4b/WhatsApp Business/Media"),
        "WhatsApp (Legado raiz)" to storageRootPath.resolve("WhatsApp/Media"),
        "WhatsApp Business (Legado raiz)" to storageRootPath.resolve("WhatsApp Business/Media"),
      ).map { (label, path) ->
        inspector.inspectWhatsAppRoot(label = label, rootDir = path)
      }

      DiagnosticReport(
        storageRoot = storageRootPath.toString(),
        hasAllFilesAccess = permissionChecker.hasAllFilesAccess(),
        androidMediaAccessible = androidMediaReadable,
        rootEntries = rootEntries,
        androidMediaEntries = androidMediaEntries,
        whatsappKnownRoots = whatsappRoots,
        generatedAtIso = Instant.now().toString(),
        appVersion = appVersion,
        sdkInt = sdkInt,
      )
    }

  companion object {
    const val DEFAULT_STORAGE_ROOT = "/storage/emulated/0"
  }
}
