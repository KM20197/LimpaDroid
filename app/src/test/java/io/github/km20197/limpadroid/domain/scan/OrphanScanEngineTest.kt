package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.pkg.InstalledAppInfo
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsRepository
import io.github.km20197.limpadroid.domain.pkg.InstalledAppsSnapshot
import io.github.km20197.limpadroid.domain.pkg.KnownAppRecord
import io.github.km20197.limpadroid.domain.scan.rules.OrphanRuleId
import java.nio.file.Files
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OrphanScanEngineTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val selfAppId = "io.github.km20197.limpadroid"

  @Test
  fun `scan detects HIGH MEDIUM LOW orphan folders respects archived apps WhatsApp and nested user exclusions`() =
    runTest {
      val root = tempFolder.newFolder("shared").toPath().toRealPath()

      // HIGH: Android/media de pacote removido conhecido
      val mediaRemoved = Files.createDirectories(root.resolve("Android/media/com.removed.game/Saves"))
      Files.writeString(mediaRemoved.resolve("save1.bin"), "1234567890")

      // Arquivado em Android/media: nunca deve ser candidato
      val mediaArchived = Files.createDirectories(root.resolve("Android/media/com.archived.app/Cache"))
      Files.writeString(mediaArchived.resolve("data.bin"), "keep")

      // WhatsApp em Android/media e na raiz: protegido pela seção 7.5
      Files.createDirectories(root.resolve("Android/media/com.whatsapp/WhatsApp/Media"))
      Files.createDirectories(root.resolve("WhatsApp/Media"))

      // MEDIUM: pasta na raiz de app removido conhecido
      val rootKnownRemoved = Files.createDirectories(root.resolve("Antigo Editor"))
      Files.writeString(rootKnownRemoved.resolve("rascunho.txt"), "abcde")

      // Pastas pessoais do usuário (Documents, Download, Pictures) não entram em ORPHAN_ROOT_KNOWN (M6)
      Files.createDirectories(root.resolve("Documents/Antigo Editor")).also {
        Files.writeString(it.resolve("doc_pessoal.txt"), "manter")
      }
      val nestedExcluded = Files.createDirectories(root.resolve("Antigo_Editor_Protegido/Manter"))
      Files.writeString(nestedExcluded.resolve("importante.pdf"), "keep-me")

      // LOW: pasta na raiz em formato de pacote não presente
      val rootPattern = Files.createDirectories(root.resolve("net.unknown.tool"))
      Files.writeString(rootPattern.resolve("cache.dat"), "123")

      // Pasta própria do LimpaDroid e pastas padrão: protegidas
      Files.createDirectories(root.resolve("Documents/LimpaDroid/.lixeira"))
      Files.createDirectories(root.resolve("Music"))

      val fakeAppsRepo = FakeInstalledAppsRepository(
        snapshot = InstalledAppsSnapshot(
          presentApps = listOf(
            InstalledAppInfo(selfAppId, "LimpaDroid", isArchived = false),
            InstalledAppInfo("com.archived.app", "App Arquivado", isArchived = true),
          ),
          knownApps = listOf(
            KnownAppRecord("com.removed.game", "Removed Game", 100L, 200L, 1_700_000_000_000L),
            KnownAppRecord("com.antigo.editor", "Antigo Editor", 100L, 200L, 1_700_100_000_000L),
            KnownAppRecord("com.antigo.editor.prot", "Antigo Editor Protegido", 100L, 200L, 1_700_100_000_000L),
          ),
        ),
      )

      val guard = DefaultPathGuard(
        rawStorageRoot = root,
        applicationId = selfAppId,
        userExcludedPaths = setOf(nestedExcluded),
      )

      val engine = OrphanScanEngine(fakeAppsRepo, selfAppId)
      val events = engine.scan(
        ScanConfig(
          sessionId = "s-orphan-1",
          scope = ScanScope.ORPHAN,
          rootPath = root,
          pathGuard = guard,
        ),
      ).toList()

      assertTrue(fakeAppsRepo.synced)
      assertTrue(events.any { it is ScanEvent.Done })

      val foundEvents = events.filterIsInstance<ScanEvent.Found>()
      val byRule: Map<String, Pair<Candidate, String?>> =
        foundEvents.associate { it.candidate.ruleId to (it.candidate to it.reason) }

      assertEquals(3, foundEvents.size)

      // HIGH preselected com tamanho calculado e motivo detalhado
      val high = byRule.getValue(OrphanRuleId.ORPHAN_MEDIA_PKG.ruleId)
      assertEquals(root.resolve("Android/media/com.removed.game").toString(), high.first.path)
      assertEquals(10L, high.first.sizeBytes)
      assertEquals(Confidence.HIGH, high.first.confidence)
      assertTrue(high.first.preselected)
      assertTrue(high.second.orEmpty().contains("Removed Game"))

      // MEDIUM desmarcado na raiz e Documents/Antigo Editor protegido por M6
      val medium = byRule.getValue(OrphanRuleId.ORPHAN_ROOT_KNOWN.ruleId)
      assertEquals(rootKnownRemoved.toString(), medium.first.path)
      assertEquals(5L, medium.first.sizeBytes)
      assertEquals(Confidence.MEDIUM, medium.first.confidence)
      assertFalse(medium.first.preselected)
      assertTrue(medium.second.orEmpty().contains("Antigo Editor"))

      // LOW desmarcado
      val low = byRule.getValue(OrphanRuleId.ORPHAN_ROOT_PATTERN.ruleId)
      assertEquals(rootPattern.toString(), low.first.path)
      assertEquals(3L, low.first.sizeBytes)
      assertEquals(Confidence.LOW, low.first.confidence)
      assertFalse(low.first.preselected)

      // Nunca sugere arquivado, WhatsApp, subpastas de Documents/Download nem diretório com exceção aninhada
      val paths = foundEvents.map { it.candidate.path }
      assertFalse(paths.any { it.contains("com.archived.app") })
      assertFalse(paths.any { it.contains("WhatsApp") || it.contains("com.whatsapp") })
      assertFalse(paths.any { it.contains("Documents/Antigo Editor") })
      assertFalse(paths.any { it.contains("Antigo_Editor_Protegido") })
    }

  @Test
  fun `A3 aborts orphan scan cleanly when package inventory is empty or unreliable`() = runTest {
    val root = tempFolder.newFolder("unreliable_orphan").toPath().toRealPath()
    val mediaPkg = Files.createDirectories(root.resolve("Android/media/com.removed.game"))
    Files.writeString(mediaPkg.resolve("save.bin"), "1234")

    val emptyRepo = FakeInstalledAppsRepository(
      InstalledAppsSnapshot(
        presentApps = emptyList(),
        knownApps = listOf(KnownAppRecord("com.removed.game", "Removed", 1L, 2L, 3L)),
        isReliable = false,
        failureReason = "Inventário vazio",
      ),
    )

    val events = OrphanScanEngine(emptyRepo, selfAppId)
      .scan(
        ScanConfig(
          sessionId = "s-unreliable",
          scope = ScanScope.ORPHAN,
          rootPath = root,
          pathGuard = DefaultPathGuard(root, selfAppId),
        ),
      )
      .toList()

    assertTrue(events.filterIsInstance<ScanEvent.Found>().isEmpty())
    assertEquals(1, events.filterIsInstance<ScanEvent.ErrorLogged>().size)
    val done = events.filterIsInstance<ScanEvent.Done>().single()
    assertEquals(0, done.candidatesCount)
    assertEquals(1, done.errorCount)
  }

  @Test
  fun `scan stops cooperatively when flow collection cancels early`() = runTest {
    val root = tempFolder.newFolder("cancel_orphan").toPath().toRealPath()
    Files.createDirectories(root.resolve("Android/media/com.old.one"))
    Files.createDirectories(root.resolve("Android/media/com.old.two"))
    Files.createDirectories(root.resolve("Android/media/com.old.three"))

    val repo = FakeInstalledAppsRepository(
      InstalledAppsSnapshot(
        presentApps = listOf(InstalledAppInfo(selfAppId, "LimpaDroid")),
        knownApps = emptyList(),
      ),
    )

    val events = OrphanScanEngine(repo, selfAppId)
      .scan(
        ScanConfig(
          sessionId = "s-cancel",
          scope = ScanScope.ORPHAN,
          rootPath = root,
          pathGuard = DefaultPathGuard(root, selfAppId),
        ),
      )
      .take(2) // Started + 1 Found
      .toList()

    assertEquals(2, events.size)
    assertFalse(events.any { it is ScanEvent.Done })
  }

  @Test
  fun `scan skips excluded parent directories Android media completely`() = runTest {
    val root = tempFolder.newFolder("excluded_parents").toPath().toRealPath()
    val mediaPkg = Files.createDirectories(root.resolve("Android/media/com.removed.game"))
    Files.writeString(mediaPkg.resolve("data.bin"), "1234")
    val rootOrphan = Files.createDirectories(root.resolve("com.removed.game"))
    Files.writeString(rootOrphan.resolve("root.dat"), "90")

    val repo = FakeInstalledAppsRepository(
      InstalledAppsSnapshot(
        presentApps = listOf(InstalledAppInfo(selfAppId, "LimpaDroid")),
        knownApps = listOf(
          KnownAppRecord("com.removed.game", "Removed App", 100L, 200L, 1_700_000_000_000L),
        ),
      ),
    )

    val guard = DefaultPathGuard(
      rawStorageRoot = root,
      applicationId = selfAppId,
      userExcludedPaths = setOf(
        root.resolve("Android/media"),
      ),
    )

    val found = OrphanScanEngine(repo, selfAppId)
      .scan(
        ScanConfig(
          sessionId = "s-parent-excl",
          scope = ScanScope.ORPHAN,
          rootPath = root,
          pathGuard = guard,
        ),
      )
      .toList()
      .filterIsInstance<ScanEvent.Found>()

    assertEquals(1, found.size)
    assertEquals(rootOrphan.toString(), found.single().candidate.path)
  }

  private class FakeInstalledAppsRepository(
    private val snapshot: InstalledAppsSnapshot,
  ) : InstalledAppsRepository {
    var synced = false

    override suspend fun syncKnownApps(): InstalledAppsSnapshot {
      synced = true
      return snapshot
    }

    override suspend fun loadSnapshot(): InstalledAppsSnapshot = snapshot
  }
}
