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
  fun `ORPHAN_MEDIA_PKG detects HIGH only for known removed apps and MEDIUM unselected otherwise`() {
    // Positivo com aplicativo removido conhecido -> HIGH + preselected
    val knownMedia = classifier.evaluateDirectory(listOf("Android", "media", "com.removed.game"))
    assertNotNull(knownMedia)
    assertEquals(OrphanRuleId.ORPHAN_MEDIA_PKG, knownMedia!!.ruleId)
    assertEquals(Confidence.HIGH, knownMedia.confidence)
    assertTrue(knownMedia.preselected)
    assertEquals("Super Jogo - Retro_2", knownMedia.matchedApp?.label)
    val reason = OrphanDirectoryClassifier.formatReason(knownMedia, "com.removed.game")
    assertTrue(reason.contains("Super Jogo - Retro_2"))
    assertTrue(reason.contains("Removido em"))

    // Pacote ausente sem correspondência em known_apps removidos -> MEDIUM e desmarcado (A3)
    val unseenMedia = classifier.evaluateDirectory(listOf("Android", "media", "com.unseen.legacy"))
    assertNotNull(unseenMedia)
    assertEquals(OrphanRuleId.ORPHAN_MEDIA_PKG, unseenMedia!!.ruleId)
    assertEquals(Confidence.MEDIUM, unseenMedia.confidence)
    assertFalse(unseenMedia.preselected)
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
  fun `ORPHAN_ROOT_KNOWN matches normalized root folders and ignores personal content parent dirs`() {
    // Positivo na raiz: normalização remove espaços, hífens e sublinhados
    val rootMatch = classifier.evaluateDirectory(listOf("SuperJogoRetro2"))
    assertNotNull(rootMatch)
    assertEquals(OrphanRuleId.ORPHAN_ROOT_KNOWN, rootMatch!!.ruleId)
    assertEquals(Confidence.MEDIUM, rootMatch.confidence)
    assertFalse(rootMatch.preselected)
    assertEquals("com.removed.game", rootMatch.matchedApp?.packageName)

    // M6: Pastas de conteúdo pessoal do usuário (Documents, Download, Pictures, Movies, Music) não geram candidatos
    listOf("Documents", "Download", "Pictures", "Movies", "Music").forEach { parent ->
      assertNull("Personal library $parent must be ignored", classifier.evaluateDirectory(listOf(parent, "super-jogo_retro2")))
      assertNull("Personal library $parent must be ignored", classifier.evaluateDirectory(listOf(parent, "com.removed.game")))
    }

    // Negativos: pastas-raiz padrão e apps presentes/arquivados nunca são sugeridos
    assertNull(classifier.evaluateDirectory(listOf("Music")))
    assertNull(classifier.evaluateDirectory(listOf("Documents")))
    assertNull(classifier.evaluateDirectory(listOf("Documents", "LimpaDroid")))
    assertNull(classifier.evaluateDirectory(listOf("Notas_Ativas")))
    assertNull(classifier.evaluateDirectory(listOf("Leitor Arquivado")))
    assertNull(classifier.evaluateDirectory(listOf("com.archived.reader")))
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
