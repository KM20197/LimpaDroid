package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.fs.FileWalker
import io.github.km20197.limpadroid.domain.fs.MediaStoreGateway
import io.github.km20197.limpadroid.domain.fs.PathGuard
import io.github.km20197.limpadroid.domain.fs.StaleMediaEntry
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.scan.rules.PartialDlRule
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SuperfluousScanEngineTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val fixedNow = 1_750_000_000_000L
  private val clock = AppClock { fixedNow }

  @Test
  fun `scan emits all superfluous categories enforces safety preselection and honors exclusions`() = runTest {
    val root = tempFolder.newFolder("engine_root").toPath().toRealPath()
    Files.createDirectories(root.resolve("DCIM/.thumbnails"))
    Files.createDirectories(root.resolve("Download"))
    Files.createDirectories(root.resolve("LOST.DIR"))
    Files.createDirectories(root.resolve("Documents/vazia"))
    Files.createDirectories(root.resolve("Documents/protegida"))
    Files.createDirectories(root.resolve("Download/protegida_rel"))

    val thumb = Files.writeString(root.resolve("DCIM/.thumbnails/cache.jpg"), "thumb")
    val oldPart = Files.writeString(root.resolve("Download/app.part"), "partial")
    Files.setLastModifiedTime(oldPart, FileTime.fromMillis(fixedNow - PartialDlRule.SEVEN_DAYS_MILLIS - 5_000L))
    val lost = Files.writeString(root.resolve("LOST.DIR/999"), "lostdata")
    val zero = Files.writeString(root.resolve("Documents/zero.txt"), "")
    val apk = Files.writeString(root.resolve("Download/installer.apk"), "apkbytes")
    val excludedZero = Files.writeString(root.resolve("Documents/protegida/zero_protegido.txt"), "")
    val relExcludedZero = Files.writeString(root.resolve("Download/protegida_rel/rel_zero.txt"), "")

    val missingMedia = root.resolve("Pictures/sumida.jpg").toString()
    val fakeGateway = object : MediaStoreGateway {
      override suspend fun findStaleEntries(
        storageRoot: Path,
        pathGuard: PathGuard,
      ): List<StaleMediaEntry> = listOf(
        StaleMediaEntry(mediaId = 10L, path = missingMedia, lastModifiedMillis = 123_456L),
        StaleMediaEntry(mediaId = 11L, path = excludedZero.toString(), lastModifiedMillis = 123_456L),
      ).filter { pathGuard.isAllowed(Path.of(it.path)) }
    }

    val engine = SuperfluousScanEngine(
      fileWalker = FileWalker(),
      mediaStoreGateway = fakeGateway,
      clock = clock,
    )

    val pathGuard = DefaultPathGuard(
      rawStorageRoot = root,
      applicationId = "io.github.km20197.limpadroid",
      userExcludedPaths = setOf(
        root.resolve("Documents/protegida"),
        Path.of("Download/protegida_rel"),
      ),
    )

    val events = engine.scan(
      ScanConfig(
        sessionId = "s-superfluous",
        scope = ScanScope.SUPERFLUOUS,
        rootPath = root,
        pathGuard = pathGuard,
      ),
    ).toList()

    val found = events.filterIsInstance<ScanEvent.Found>().map { it.candidate }
    val byRule = found.associateBy { it.ruleId }

    assertEquals(7, found.size)
    assertEquals(thumb.toString(), byRule[SuperfluousRuleId.THUMBS.ruleId]?.path)
    assertEquals(oldPart.toString(), byRule[SuperfluousRuleId.PARTIAL_DL.ruleId]?.path)
    assertEquals(lost.toString(), byRule[SuperfluousRuleId.LOST_DIR.ruleId]?.path)
    assertEquals(root.resolve("Documents/vazia").toString(), byRule[SuperfluousRuleId.EMPTY_DIR.ruleId]?.path)
    assertEquals(zero.toString(), byRule[SuperfluousRuleId.ZERO_BYTE.ruleId]?.path)
    assertEquals(apk.toString(), byRule[SuperfluousRuleId.OLD_APK.ruleId]?.path)
    assertEquals(missingMedia, byRule[SuperfluousRuleId.STALE_MEDIASTORE.ruleId]?.path)

    // Safety invariant: only HIGH confidence defaults to preselected = true
    found.forEach { candidate ->
      if (candidate.confidence == Confidence.HIGH) {
        assertTrue("Expected HIGH rule ${candidate.ruleId} to be preselected", candidate.preselected)
      } else {
        assertFalse("Expected ${candidate.confidence} rule ${candidate.ruleId} NOT preselected", candidate.preselected)
      }
    }

    // Excluded paths (absolute and relative) were skipped
    assertFalse(found.any { it.path == excludedZero.toString() })
    assertFalse(found.any { it.path == relExcludedZero.toString() })

    val done = events.filterIsInstance<ScanEvent.Done>().single()
    assertEquals(7, done.candidatesCount)
  }

  @Test
  fun `disabled rules including STALE_MEDIASTORE are omitted from scan results`() = runTest {
    val root = tempFolder.newFolder("engine_disabled_rules").toPath().toRealPath()
    Files.createDirectories(root.resolve("DCIM/.thumbnails"))
    Files.writeString(root.resolve("DCIM/.thumbnails/a.jpg"), "thumb")
    Files.writeString(root.resolve("Download_root_zero.txt"), "")

    var gatewayCalled = false
    val fakeGateway = object : MediaStoreGateway {
      override suspend fun findStaleEntries(
        storageRoot: Path,
        pathGuard: PathGuard,
      ): List<StaleMediaEntry> {
        gatewayCalled = true
        return listOf(StaleMediaEntry(1L, root.resolve("Pictures/missing.jpg").toString(), 100L))
      }
    }

    val engine = SuperfluousScanEngine(
      fileWalker = FileWalker(),
      mediaStoreGateway = fakeGateway,
      clock = clock,
      enabledRulesProvider = { setOf(SuperfluousRuleId.ZERO_BYTE.ruleId) },
    )

    val events = engine.scan(
      ScanConfig(
        sessionId = "s-disabled",
        scope = ScanScope.SUPERFLUOUS,
        rootPath = root,
        pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid"),
      ),
    ).toList()

    val found = events.filterIsInstance<ScanEvent.Found>().map { it.candidate }
    assertEquals(1, found.size)
    assertEquals(SuperfluousRuleId.ZERO_BYTE.ruleId, found.single().ruleId)
    assertFalse(gatewayCalled)
  }

  @Test
  fun `cooperative cancellation interrupts superfluous scan cleanly`() = runTest {
    val root = tempFolder.newFolder("engine_cancel").toPath().toRealPath()
    val thumbsDir = Files.createDirectories(root.resolve("DCIM/.thumbnails"))
    repeat(25) { index ->
      Files.writeString(thumbsDir.resolve("img_$index.jpg"), "data-$index")
    }

    val engine = SuperfluousScanEngine(fileWalker = FileWalker(), clock = clock)
    val partial = engine.scan(
      ScanConfig(
        sessionId = "s-cancel",
        scope = ScanScope.SUPERFLUOUS,
        rootPath = root,
        pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid"),
      ),
    ).take(3).toList()

    assertEquals(3, partial.size)
  }
}
