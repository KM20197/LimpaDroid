package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.FileEntry
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ClassificationTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `ClassificationPolicy never preselects LOW or MEDIUM candidates`() {
    val entry = FileEntry("/storage/emulated/0/test.tmp", 128L, 1_000L, false)

    val highCandidate = ClassificationPolicy.toCandidate(
      entry,
      RuleMatch("RULE_HIGH", Category.SUPERFLUOUS, Confidence.HIGH),
    )
    val highOverridden = ClassificationPolicy.toCandidate(
      entry,
      RuleMatch("RULE_WA", Category.WHATSAPP, Confidence.HIGH, preselectedOverride = false),
    )
    val mediumCandidate = ClassificationPolicy.toCandidate(
      entry,
      RuleMatch("RULE_MED", Category.ORPHAN, Confidence.MEDIUM),
    )
    val lowCandidate = ClassificationPolicy.toCandidate(
      entry,
      RuleMatch("RULE_LOW", Category.SUPERFLUOUS, Confidence.LOW),
    )

    assertTrue(highCandidate.preselected)
    assertFalse(highOverridden.preselected)
    assertFalse(mediumCandidate.preselected)
    assertFalse(lowCandidate.preselected)
  }

  @Test
  fun `InfrastructureScanEngine classifies entries via registered ScanRules and emits Done`() = runTest {
    val root = tempFolder.newFolder("scan_root").toPath().toRealPath()
    val tmpFile = Files.writeString(root.resolve("cache.tmp"), "123456")
    Files.writeString(root.resolve("keep.pdf"), "1234567890")

    val tmpRule = object : ScanRule {
      override val ruleId: String = "TEST_TMP"
      override val category: Category = Category.SUPERFLUOUS
      override val confidence: Confidence = Confidence.HIGH
      override fun evaluate(entry: FileEntry, storageRootPath: String): RuleMatch? =
        if (!entry.isDirectory && entry.path.endsWith(".tmp")) {
          RuleMatch(ruleId, category, confidence, reason = "Extensão temporária")
        } else {
          null
        }
    }

    val engine = InfrastructureScanEngine(FileWalker())
    val events = engine.scan(
      ScanConfig(
        sessionId = "s1",
        scope = ScanScope.INFRASTRUCTURE,
        rootPath = root,
        pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid"),
        rules = listOf(tmpRule),
      ),
    ).toList()

    val found = events.filterIsInstance<ScanEvent.Found>()
    val done = events.filterIsInstance<ScanEvent.Done>().single()

    assertEquals(1, found.size)
    assertEquals(tmpFile.toString(), found.single().candidate.path)
    assertEquals(2L, done.scannedFiles)
    assertEquals(1, done.candidatesCount)
  }
}
