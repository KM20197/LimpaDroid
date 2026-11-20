package io.github.km20197.limpadroid.domain.scan.rules

import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.FileEntry
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.scan.ScanConfig
import io.github.km20197.limpadroid.domain.scan.SuperfluousScanEngine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SuperfluousRulesTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val fixedNow = 1_750_000_000_000L
  private val clock = AppClock { fixedNow }

  @Test
  fun `ThumbsRule matches positive examples and rejects negative examples`() {
    val root = "/storage/emulated/0"

    ThumbsRule.positiveExamples.forEach { rel ->
      val match = ThumbsRule.evaluate(
        FileEntry(path = "$root/$rel", sizeBytes = 512L, lastModified = 100L, isDirectory = false),
        root,
      )
      assertNotNull("Expected match for $rel", match)
      assertEquals("THUMBS", match?.ruleId)
      assertEquals(Confidence.HIGH, match?.confidence)
    }

    ThumbsRule.negativeExamples.forEach { rel ->
      val isDir = rel == "DCIM/.thumbnails"
      val match = ThumbsRule.evaluate(
        FileEntry(path = "$root/$rel", sizeBytes = 512L, lastModified = 100L, isDirectory = isDir),
        root,
      )
      assertNull("Expected null for $rel", match)
    }
  }

  @Test
  fun `PartialDlRule enforces extensions Download root and 7 day age cutoff`() {
    val root = "/storage/emulated/0"
    val rule = PartialDlRule(clock)
    val oldMtime = fixedNow - PartialDlRule.SEVEN_DAYS_MILLIS - 1_000L
    val exactSevenDaysMtime = fixedNow - PartialDlRule.SEVEN_DAYS_MILLIS

    rule.positiveExamples.forEach { rel ->
      val match = rule.evaluate(
        FileEntry(path = "$root/$rel", sizeBytes = 1024L, lastModified = oldMtime, isDirectory = false),
        root,
      )
      assertNotNull("Expected match for $rel", match)
      assertEquals("PARTIAL_DL", match?.ruleId)
      assertEquals(Confidence.HIGH, match?.confidence)
    }

    assertNull(
      rule.evaluate(
        FileEntry("$root/Download/exato7d.part", 1024L, exactSevenDaysMtime, isDirectory = false),
        root,
      ),
    )
    assertNull(
      rule.evaluate(
        FileEntry("$root/Download/ativo.crdownload", 1024L, fixedNow - 60_000L, isDirectory = false),
        root,
      ),
    )
    assertNull(
      rule.evaluate(
        FileEntry("$root/Documents/rascunho.tmp", 1024L, oldMtime, isDirectory = false),
        root,
      ),
    )
    assertNull(
      rule.evaluate(
        FileEntry("$root/Download/manual.pdf", 1024L, oldMtime, isDirectory = false),
        root,
      ),
    )
    assertNull(
      rule.evaluate(
        FileEntry("$root/Download/pasta.tmp", 0L, oldMtime, isDirectory = true),
        root,
      ),
    )
  }

  @Test
  fun `LostDirRule matches files under root LOST_DIR only`() {
    val root = "/storage/emulated/0"

    LostDirRule.positiveExamples.forEach { rel ->
      val match = LostDirRule.evaluate(
        FileEntry(path = "$root/$rel", sizeBytes = 256L, lastModified = 100L, isDirectory = false),
        root,
      )
      assertNotNull("Expected match for $rel", match)
      assertEquals("LOST_DIR", match?.ruleId)
      assertEquals(Confidence.MEDIUM, match?.confidence)
    }

    assertNull(LostDirRule.evaluate(FileEntry("$root/LOST.DIR", 0L, 100L, isDirectory = true), root))
    assertNull(
      LostDirRule.evaluate(FileEntry("$root/Download/LOST.DIR/arquivo.txt", 100L, 100L, isDirectory = false), root),
    )
    assertNull(
      LostDirRule.evaluate(FileEntry("$root/Documents/lost/10421", 100L, 100L, isDirectory = false), root),
    )
  }

  @Test
  fun `EmptyDirRule matches empty folders while preserving standard roots nomedia and parents`() {
    val root = tempFolder.newFolder("empty_rule_root").toPath().toRealPath()
    val standardRoots = listOf(
      "DCIM", "Download", "Documents", "Pictures", "Movies", "Music",
      "Alarms", "Notifications", "Ringtones", "Podcasts", "Audiobooks", "Recordings",
      "Android", "LOST.DIR",
    )
    standardRoots.forEach { Files.createDirectories(root.resolve(it)) }
    Files.createDirectories(root.resolve("Android/media"))
    Files.createDirectories(root.resolve("Documents/LimpaDroid/sub"))

    val emptySub = Files.createDirectories(root.resolve("Download/pasta_vazia"))
    val nestedParent = Files.createDirectories(root.resolve("Documents/rascunho_antigo"))
    val nestedChild = Files.createDirectories(nestedParent.resolve("vazia"))

    val withNomedia = Files.createDirectories(root.resolve("Download/com_nomedia"))
    Files.writeString(withNomedia.resolve(".nomedia"), "")

    val nonEmpty = Files.createDirectories(root.resolve("Download/nao_vazia"))
    Files.writeString(nonEmpty.resolve("a.txt"), "ok")

    fun evalDir(path: Path) = EmptyDirRule.evaluate(
      FileEntry(path = path.toString(), sizeBytes = 0L, lastModified = 100L, isDirectory = true),
      root.toString(),
    )

    assertEquals("EMPTY_DIR", evalDir(emptySub)?.ruleId)
    assertEquals(Confidence.MEDIUM, evalDir(emptySub)?.confidence)
    assertEquals("EMPTY_DIR", evalDir(nestedChild)?.ruleId)
    assertNull("Parent holding an empty child must not be marked empty", evalDir(nestedParent))
    assertNull(evalDir(withNomedia))
    assertNull(evalDir(nonEmpty))
    assertNull(evalDir(root.resolve("Android/media")))
    assertNull(evalDir(root.resolve("Documents/LimpaDroid/sub")))
    standardRoots.forEach { std ->
      assertNull("Standard root $std must never match", evalDir(root.resolve(std)))
    }
  }

  @Test
  fun `ZeroByteRule skips nomedia dot-files non-zero files and directories`() {
    val root = "/storage/emulated/0"

    ZeroByteRule.positiveExamples.forEach { rel ->
      val match = ZeroByteRule.evaluate(
        FileEntry(path = "$root/$rel", sizeBytes = 0L, lastModified = 100L, isDirectory = false),
        root,
      )
      assertNotNull("Expected match for $rel", match)
      assertEquals("ZERO_BYTE", match?.ruleId)
      assertEquals(Confidence.MEDIUM, match?.confidence)
    }

    assertNull(ZeroByteRule.evaluate(FileEntry("$root/Pictures/.nomedia", 0L, 100L, false), root))
    assertNull(ZeroByteRule.evaluate(FileEntry("$root/Documents/.hidden_lock", 0L, 100L, false), root))
    assertNull(ZeroByteRule.evaluate(FileEntry("$root/Download/com_conteudo.txt", 12L, 100L, false), root))
    assertNull(ZeroByteRule.evaluate(FileEntry("$root/Download/pasta_vazia", 0L, 100L, true), root))
  }

  @Test
  fun `OldApkRule matches installers in Download with LOW confidence only`() {
    val root = "/storage/emulated/0"

    OldApkRule.positiveExamples.forEach { rel ->
      val match = OldApkRule.evaluate(
        FileEntry(path = "$root/$rel", sizeBytes = 5_000_000L, lastModified = 100L, isDirectory = false),
        root,
      )
      assertNotNull("Expected match for $rel", match)
      assertEquals("OLD_APK", match?.ruleId)
      assertEquals(Confidence.LOW, match?.confidence)
    }

    assertNull(OldApkRule.evaluate(FileEntry("$root/Documents/backup.apk", 5_000L, 100L, false), root))
    assertNull(OldApkRule.evaluate(FileEntry("$root/Download/manual.pdf", 5_000L, 100L, false), root))
    assertNull(OldApkRule.evaluate(FileEntry("$root/Download/instalador.apk", 0L, 100L, true), root))
  }

  @Test
  fun `SuperfluousRuleRegistry filters disabled rules accurately`() {
    val subset = SuperfluousRuleRegistry.buildFilesystemRules(
      enabledRuleIds = setOf(SuperfluousRuleId.THUMBS.ruleId, SuperfluousRuleId.OLD_APK.ruleId),
      clock = clock,
    )
    assertEquals(listOf("THUMBS", "OLD_APK"), subset.map { it.ruleId })
  }

  @Test
  fun `clean control storage tree produces zero false positives across all superfluous rules`() = runTest {
    val root = tempFolder.newFolder("control_tree").toPath().toRealPath()

    listOf(
      "DCIM/Camera",
      "Download",
      "Documents/Notas",
      "Pictures/Viagem",
      "Movies",
      "Music",
      "Alarms",
      "Notifications",
      "Ringtones",
      "Podcasts",
      "Audiobooks",
      "Recordings",
      "LOST.DIR",
      "Android/media/com.whatsapp/WhatsApp/Media",
      "Documents/LimpaDroid/.lixeira",
    ).forEach { Files.createDirectories(root.resolve(it)) }

    val recentMtime = FileTime.fromMillis(fixedNow - 60_000L)
    val files = listOf(
      "DCIM/Camera/IMG_20261117.jpg" to "jpegdata",
      "DCIM/Camera/.nomedia" to "",
      "Pictures/Viagem/.nomedia" to "",
      "Download/ativo.crdownload" to "downloading",
      "Download/manual.pdf" to "pdfcontent",
      "Documents/Notas/rascunho.tmp" to "note",
      "Documents/Notas/instalador.apk" to "apkoutside",
      "Documents/.sync_lock" to "",
      "Documents/LimpaDroid/.lixeira/meta.json" to "{}",
    )
    files.forEach { (rel, content) ->
      val p = Files.writeString(root.resolve(rel), content)
      Files.setLastModifiedTime(p, recentMtime)
    }

    val engine = SuperfluousScanEngine(
      fileWalker = FileWalker(),
      mediaStoreGateway = null,
      clock = clock,
    )
    val events = engine.scan(
      ScanConfig(
        sessionId = "control-session",
        scope = ScanScope.SUPERFLUOUS,
        rootPath = root,
        pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid"),
      ),
    ).toList()

    val found = events.filterIsInstance<ScanEvent.Found>()
    assertTrue("Expected zero false positives, got ${found.map { it.candidate.path }}", found.isEmpty())
    val done = events.filterIsInstance<ScanEvent.Done>().single()
    assertEquals(0, done.candidatesCount)
    assertEquals(Category.SUPERFLUOUS, engine.category)
  }
}
