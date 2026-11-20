package io.github.km20197.limpadroid.domain.scan.rules

import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.pkg.AppIdentifierNormalizer
import io.github.km20197.limpadroid.domain.pkg.InstalledAppInfo
import io.github.km20197.limpadroid.domain.pkg.KnownAppRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanRulesTest {

  private val selfAppId = "io.github.km20197.limpadroid"

  private val presentApps = listOf(
    InstalledAppInfo(packageName = selfAppId, label = "LimpaDroid", isArchived = false),
    InstalledAppInfo(packageName = "com.active.notes", label = "Notas Ativas", isArchived = false),
    InstalledAppInfo(packageName = "com.archived.reader", label = "Leitor Arquivado", isArchived = true),
    InstalledAppInfo(packageName = "com.retained.app", label = "App Retido", isArchived = false),
  )

  private val knownApps = listOf(
    KnownAppRecord(
      packageName = "com.removed.game",
      label = "Super Jogo - Retro_2",
      firstSeen = 1_000L,
      lastSeen = 2_000L,
      removedAt = 1_700_000_000_000L,
    ),
    KnownAppRecord(
      packageName = "com.old.scanner",
      label = "Music",
      firstSeen = 1_000L,
      lastSeen = 2_000L,
      removedAt = 1_700_100_000_000L,
    ),
    KnownAppRecord(
      packageName = "com.archived.reader",
      label = "Leitor Arquivado",
      firstSeen = 1_000L,
      lastSeen = 5_000L,
      removedAt = null,
    ),
    KnownAppRecord(
      packageName = "com.active.notes",
      label = "Notas Ativas",
      firstSeen = 1_000L,
      lastSeen = 5_000L,
      removedAt = null,
    ),
  )

  private val classifier = OrphanDirectoryClassifier(
    presentApps = presentApps,
    knownApps = knownApps,
    applicationId = selfAppId,
  )

  @Test
  fun `ORPHAN_MEDIA_PKG detects absent package folders with HIGH confidence and enrichment`() {
    // Positivos
    val knownMedia = classifier.evaluateDirectory(listOf("Android", "media", "com.removed.game"))
    assertNotNull(knownMedia)
    assertEquals(OrphanRuleId.ORPHAN_MEDIA_PKG, knownMedia!!.ruleId)
    assertEquals(Confidence.HIGH, knownMedia.confidence)
    assertTrue(knownMedia.preselected)
    assertEquals("Super Jogo - Retro_2", knownMedia.matchedApp?.label)
    val reason = OrphanDirectoryClassifier.formatReason(knownMedia, "com.removed.game")
    assertTrue(reason.contains("Super Jogo - Retro_2"))
    assertTrue(reason.contains("Removido em"))

    val unseenMedia = classifier.evaluateDirectory(listOf("Android", "media", "com.unseen.legacy"))
    assertNotNull(unseenMedia)
    assertEquals(OrphanRuleId.ORPHAN_MEDIA_PKG, unseenMedia!!.ruleId)
    assertEquals(Confidence.HIGH, unseenMedia.confidence)
    assertTrue(unseenMedia.preselected)
    assertNull(unseenMedia.matchedApp)

    // Negativos
    assertNull(classifier.evaluateDirectory(listOf("Android", "media", "com.active.notes")))
    assertNull(classifier.evaluateDirectory(listOf("Android", "media", "com.archived.reader")))
    assertNull(classifier.evaluateDirectory(listOf("Android", "media", "com.retained.app")))
    assertNull(classifier.evaluateDirectory(listOf("Android", "media", selfAppId)))
    assertNull(classifier.evaluateDirectory(listOf("Android", "media", "com.whatsapp")))
    assertNull(classifier.evaluateDirectory(listOf("Android", "media", "com.whatsapp.w4b")))
    assertNull(classifier.evaluateDirectory(listOf("Android", "media", "PastasSemPacote")))
  }

  @Test
  fun `ORPHAN_ROOT_KNOWN matches normalized labels and packages with MEDIUM unselected confidence`() {
    // Positivos: normalização remove espaços, hífens e sublinhados em raiz e subpastas permitidas
    val rootMatch = classifier.evaluateDirectory(listOf("SuperJogoRetro2"))
    assertNotNull(rootMatch)
    assertEquals(OrphanRuleId.ORPHAN_ROOT_KNOWN, rootMatch!!.ruleId)
    assertEquals(Confidence.MEDIUM, rootMatch.confidence)
    assertFalse(rootMatch.preselected)
    assertEquals("com.removed.game", rootMatch.matchedApp?.packageName)

    val docsMatch = classifier.evaluateDirectory(listOf("Documents", "Super_Jogo-Retro 2"))
    assertNotNull(docsMatch)
    assertEquals(OrphanRuleId.ORPHAN_ROOT_KNOWN, docsMatch!!.ruleId)
    assertEquals(Confidence.MEDIUM, docsMatch.confidence)
    assertFalse(docsMatch.preselected)

    val downloadPkgMatch = classifier.evaluateDirectory(listOf("Download", "com.removed.game"))
    assertNotNull(downloadPkgMatch)
    assertEquals(OrphanRuleId.ORPHAN_ROOT_KNOWN, downloadPkgMatch!!.ruleId)

    listOf("Pictures", "Movies", "Music").forEach { parent ->
      val m = classifier.evaluateDirectory(listOf(parent, "super-jogo_retro2"))
      assertNotNull("Expected match under $parent", m)
      assertEquals(OrphanRuleId.ORPHAN_ROOT_KNOWN, m!!.ruleId)
    }

    // Negativos: pastas-raiz padrão e apps presentes/arquivados nunca são sugeridos
    assertNull(classifier.evaluateDirectory(listOf("Music")))
    assertNull(classifier.evaluateDirectory(listOf("Documents")))
    assertNull(classifier.evaluateDirectory(listOf("Documents", "LimpaDroid")))
    assertNull(classifier.evaluateDirectory(listOf("Documents", "Notas_Ativas")))
    assertNull(classifier.evaluateDirectory(listOf("Documents", "Leitor Arquivado")))
    assertNull(classifier.evaluateDirectory(listOf("Download", "com.archived.reader")))
    assertNull(classifier.evaluateDirectory(listOf("DCIM", "SuperJogoRetro2")))
  }

  @Test
  fun `ORPHAN_ROOT_PATTERN flags unknown package-like root dirs as LOW unselected and shields WhatsApp`() {
    // Positivo
    val patternMatch = classifier.evaluateDirectory(listOf("org.unknown.legacy"))
    assertNotNull(patternMatch)
    assertEquals(OrphanRuleId.ORPHAN_ROOT_PATTERN, patternMatch!!.ruleId)
    assertEquals(Confidence.LOW, patternMatch.confidence)
    assertFalse(patternMatch.preselected)

    // Se estiver em known_apps como removido, prefere ORPHAN_ROOT_KNOWN (MEDIUM)
    val knownRootPkg = classifier.evaluateDirectory(listOf("com.removed.game"))
    assertNotNull(knownRootPkg)
    assertEquals(OrphanRuleId.ORPHAN_ROOT_KNOWN, knownRootPkg!!.ruleId)
    assertEquals(Confidence.MEDIUM, knownRootPkg.confidence)

    // Negativos
    assertNull(classifier.evaluateDirectory(listOf("com.archived.reader")))
    assertNull(classifier.evaluateDirectory(listOf("com.active.notes")))
    assertNull(classifier.evaluateDirectory(listOf("com.whatsapp")))
    assertNull(classifier.evaluateDirectory(listOf("com.whatsapp.w4b")))
    assertNull(classifier.evaluateDirectory(listOf("WhatsApp")))
    assertNull(classifier.evaluateDirectory(listOf("WhatsApp Business")))
    assertNull(classifier.evaluateDirectory(listOf("LOST.DIR")))
    assertNull(classifier.evaluateDirectory(listOf(".hidden.dir")))
    assertNull(classifier.evaluateDirectory(listOf("MeusArquivos")))
  }

  @Test
  fun `normalizer strips spaces hyphens underscores and lowercases identifiers`() {
    assertEquals("meuapppro2", AppIdentifierNormalizer.normalize(" Meu App - Pro_2 "))
    assertTrue(AppIdentifierNormalizer.isPackageFormat("com.example.app_2"))
    assertFalse(AppIdentifierNormalizer.isPackageFormat("LOST.DIR"))
    assertFalse(AppIdentifierNormalizer.isPackageFormat("singleword"))
  }
}
