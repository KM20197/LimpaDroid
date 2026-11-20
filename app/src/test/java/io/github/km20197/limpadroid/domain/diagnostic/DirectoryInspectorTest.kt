package io.github.km20197.limpadroid.domain.diagnostic

import io.github.km20197.limpadroid.domain.model.DiagnosticReport
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryInspectorTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val inspector = DirectoryInspector(maxEntriesPerLevel = 50)

  @Test
  fun `inspectTwoLevels returns two levels and omits third level`() {
    val root = tempFolder.newFolder("storage").toPath()
    val level1 = Files.createDirectory(root.resolve("Android"))
    val level2 = Files.createDirectory(level1.resolve("media"))
    Files.createDirectory(level2.resolve("com.whatsapp"))
    Files.writeString(root.resolve("root_file.txt"), "abc")
    Files.writeString(level1.resolve("nested.txt"), "12345")

    val entries = inspector.inspectTwoLevels(root)
    assertEquals(2, entries.size)
    assertEquals("Android", entries[0].name)
    assertTrue(entries[0].isDirectory)
    assertEquals(2, entries[0].children.size)

    val mediaEntry = entries[0].children.first { it.name == "media" }
    assertTrue(mediaEntry.isDirectory)
    assertTrue(mediaEntry.children.isEmpty())

    assertEquals("root_file.txt", entries[1].name)
    assertFalse(entries[1].isDirectory)
    assertEquals(3L, entries[1].sizeBytes)
  }

  @Test
  fun `inspectTwoLevels skips symbolic links`() {
    val root = tempFolder.newFolder("symroot").toPath()
    val target = tempFolder.newFolder("external_target").toPath()
    Files.writeString(target.resolve("secret.txt"), "secret")

    val linkPath = root.resolve("sym_dir")
    try {
      Files.createSymbolicLink(linkPath, target)
    } catch (_: UnsupportedOperationException) {
      return
    }

    val entries = inspector.inspectTwoLevels(root)
    assertTrue(entries.none { it.name == "sym_dir" })
  }

  @Test
  fun `inspectTwoLevels returns empty list for missing path`() {
    val missing = tempFolder.root.toPath().resolve("does_not_exist")
    assertTrue(inspector.inspectTwoLevels(missing).isEmpty())
  }

  @Test
  fun `inspectTwoLevels sorts before truncating and emits truncation notice`() {
    val limitedInspector = DirectoryInspector(maxEntriesPerLevel = 2)
    val root = tempFolder.newFolder("sorted_trunc").toPath()
    Files.writeString(root.resolve("zeta.txt"), "z")
    Files.writeString(root.resolve("alpha.txt"), "a")
    Files.createDirectory(root.resolve("BetaDir"))

    val entries = limitedInspector.inspectTwoLevels(root)
    assertEquals(3, entries.size)
    assertEquals("BetaDir", entries[0].name)
    assertEquals("alpha.txt", entries[1].name)
    assertTrue(entries[2].isTruncationNotice)

    val report = DiagnosticReport(
      storageRoot = root.toString(),
      hasAllFilesAccess = true,
      androidMediaAccessible = false,
      rootEntries = entries,
      androidMediaEntries = emptyList(),
      whatsappKnownRoots = emptyList(),
      generatedAtIso = "2026-11-17T00:00:00Z",
      appVersion = "0.1.0 (1)",
      sdkInt = 37,
    )
    assertTrue(DirectoryInspector.formatReportAsText(report).contains("(lista truncada)"))
  }

  @Test
  fun `inspectWhatsAppRoot detects existing folders and signals truncation`() {
    val root = tempFolder.newFolder("wa").toPath()
    Files.createDirectory(root.resolve("WhatsApp Video"))
    Files.createDirectory(root.resolve("WhatsApp Images"))
    Files.createDirectory(root.resolve("WhatsApp Audio"))

    val limited = DirectoryInspector(maxEntriesPerLevel = 2).inspectWhatsAppRoot("WhatsApp", root)
    assertTrue(limited.exists)
    assertTrue(limited.readable)
    assertTrue(limited.truncated)
    assertEquals(listOf("WhatsApp Audio", "WhatsApp Images"), limited.subFolders)

    val absent = inspector.inspectWhatsAppRoot("Business", root.resolve("Missing"))
    assertFalse(absent.exists)
    assertFalse(absent.readable)
  }

  @Test
  fun `formatReportAsText includes summary and whatsapp sections`() {
    val report = DiagnosticReport(
      storageRoot = "/storage/emulated/0",
      hasAllFilesAccess = true,
      androidMediaAccessible = true,
      rootEntries = emptyList(),
      androidMediaEntries = emptyList(),
      whatsappKnownRoots = emptyList(),
      generatedAtIso = "2026-11-16T00:00:00Z",
      appVersion = "0.1.0 (1)",
      sdkInt = 37,
    )

    val text = DirectoryInspector.formatReportAsText(report)
    assertTrue(text.contains("MANAGE_EXTERNAL_STORAGE: CONCEDIDA"))
    assertTrue(text.contains("Android/media acessível: SIM"))
    assertTrue(text.contains("Android SDK: 37"))
  }
}
