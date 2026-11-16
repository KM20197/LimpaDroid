package io.github.km20197.limpadroid.data.fs

import android.content.Intent
import io.github.km20197.limpadroid.data.permission.PermissionChecker
import io.github.km20197.limpadroid.domain.diagnostic.DirectoryInspector
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageDiagnosticRepositoryTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `inspectStorage populates root, Android media, and WhatsApp roots`() = runTest {
    val root = tempFolder.newFolder("emulated0").toPath()
    val androidMedia = Files.createDirectories(root.resolve("Android/media"))
    val waMedia = Files.createDirectories(androidMedia.resolve("com.whatsapp/WhatsApp/Media"))
    Files.createDirectory(waMedia.resolve("WhatsApp Images"))
    Files.createDirectory(root.resolve("Download"))

    val checker = object : PermissionChecker {
      override fun hasAllFilesAccess(): Boolean = true
      override fun hasNotificationPermission(): Boolean = false
      override fun createAllFilesAccessIntent(): Intent = Intent("test.ACTION")
    }

    val repo = DefaultStorageDiagnosticRepository(
      permissionChecker = checker,
      volumeResolver = object : StorageVolumeResolver {
        override fun resolvePrimarySharedStorage(): StorageRootResolution =
          StorageRootResolution.Available(rootPath = root, volumeDescription = "Armazenamento simulado")
      },
      inspector = DirectoryInspector(),
    )

    val report = repo.inspectStorage(appVersion = "0.1.0 (1)", sdkInt = 37)

    assertTrue(report.storageAvailable)
    assertTrue(report.hasAllFilesAccess)
    assertTrue(report.androidMediaAccessible)
    assertEquals(4, report.whatsappKnownRoots.size)
    assertTrue(report.whatsappKnownRoots[0].exists)
    assertEquals(listOf("WhatsApp Images"), report.whatsappKnownRoots[0].subFolders)
    assertFalse(report.whatsappKnownRoots[1].exists)
    assertTrue(report.rootEntries.any { it.name == "Download" })
    assertTrue(report.androidMediaEntries.any { it.name == "com.whatsapp" })
  }

  @Test
  fun `inspectStorage returns unavailable report when primary volume is not mounted`() = runTest {
    val checker = object : PermissionChecker {
      override fun hasAllFilesAccess(): Boolean = true
      override fun hasNotificationPermission(): Boolean = true
      override fun createAllFilesAccessIntent(): Intent = Intent("test.ACTION")
    }

    val repo = DefaultStorageDiagnosticRepository(
      permissionChecker = checker,
      volumeResolver = object : StorageVolumeResolver {
        override fun resolvePrimarySharedStorage(): StorageRootResolution =
          StorageRootResolution.Unavailable("Volume primário não montado (unmounted)")
      },
      inspector = DirectoryInspector(),
    )

    val report = repo.inspectStorage(appVersion = "0.1.0 (1)", sdkInt = 30)

    assertFalse(report.storageAvailable)
    assertFalse(report.androidMediaAccessible)
    assertTrue(report.rootEntries.isEmpty())
    assertTrue(report.whatsappKnownRoots.isEmpty())
    assertTrue(report.storageRoot.contains("não montado"))
  }
}
