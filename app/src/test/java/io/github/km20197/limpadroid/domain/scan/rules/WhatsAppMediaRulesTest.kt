package io.github.km20197.limpadroid.domain.scan.rules

import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppMediaRulesTest {

  @Test
  fun `classifyMediaSubfolder accepts all standard and Business media folders when enabled`() {
    val allEnabled = WhatsAppMediaType.entries.toSet()

    val img = WhatsAppMediaRules.classifyMediaSubfolder("WhatsApp Images", allEnabled)
    assertNotNull(img)
    assertEquals(WhatsAppMediaType.IMAGES, img!!.mediaType)
    assertEquals(WhatsAppRuleId.WHATSAPP_IMAGES, img.ruleId)
    assertFalse(img.isBusiness)

    val bizVideo = WhatsAppMediaRules.classifyMediaSubfolder("WhatsApp Business Video", allEnabled)
    assertNotNull(bizVideo)
    assertEquals(WhatsAppMediaType.VIDEO, bizVideo!!.mediaType)
    assertEquals(WhatsAppRuleId.WHATSAPP_VIDEO, bizVideo.ruleId)
    assertTrue(bizVideo.isBusiness)

    val audio = WhatsAppMediaRules.classifyMediaSubfolder("WhatsApp Audio", allEnabled)
    assertEquals(WhatsAppMediaType.AUDIO, audio?.mediaType)

    val voice = WhatsAppMediaRules.classifyMediaSubfolder("WhatsApp Voice Notes", allEnabled)
    assertEquals(WhatsAppMediaType.VOICE_NOTES, voice?.mediaType)

    val gifs = WhatsAppMediaRules.classifyMediaSubfolder("WhatsApp Business Animated Gifs", allEnabled)
    assertNotNull(gifs)
    assertEquals(WhatsAppMediaType.ANIMATED_GIFS, gifs!!.mediaType)
    assertTrue(gifs.isBusiness)
  }

  @Test
  fun `classifyMediaSubfolder blocks Animated Gifs by default and never touches forbidden folders`() {
    val defaults = WhatsAppMediaType.DEFAULT_ENABLED_TYPES
    assertFalse(WhatsAppMediaType.ANIMATED_GIFS in defaults)
    assertNull(WhatsAppMediaRules.classifyMediaSubfolder("WhatsApp Animated Gifs", defaults))
    assertNull(WhatsAppMediaRules.classifyMediaSubfolder("WhatsApp Business Animated Gifs", defaults))

    val allEnabled = WhatsAppMediaType.entries.toSet()
    val forbidden = listOf(
      ".Statuses",
      "Backups",
      "Databases",
      "WhatsApp Documents",
      "WhatsApp Business Documents",
      "WhatsApp Stickers",
      "WhatsApp Profile Photos",
      "WhatsApp Bug Reports",
      ".Shared",
      ".Links",
      ".trash",
      ".hidden",
      "WhatsApp Images Extra",
      "TempFolder",
    )
    forbidden.forEach { folder ->
      assertNull("Pasta '$folder' nunca deve corresponder", WhatsAppMediaRules.classifyMediaSubfolder(folder, allEnabled))
    }
  }

  @Test
  fun `isForbiddenFolderName blocks hidden and protected names case-insensitively`() {
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName(".Statuses"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName(".Shared"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName(".Links"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName(".trash"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName(".nomediaFolder"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName("BACKUPS"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName("Databases"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName("WhatsApp Documents"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName("WhatsApp Stickers"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName("WhatsApp Profile Photos"))
    assertTrue(WhatsAppMediaRules.isForbiddenFolderName("WhatsApp Bug Reports"))

    assertFalse(WhatsAppMediaRules.isForbiddenFolderName("Sent"))
    assertFalse(WhatsAppMediaRules.isForbiddenFolderName("Private"))
    assertFalse(WhatsAppMediaRules.isForbiddenFolderName("202546"))
  }

  @Test
  fun `clampAgeDays enforces 7 to 730 bounds and matchesMinAge checks exact cutoff`() {
    assertEquals(7, WhatsAppMediaRules.clampAgeDays(-5))
    assertEquals(7, WhatsAppMediaRules.clampAgeDays(0))
    assertEquals(7, WhatsAppMediaRules.clampAgeDays(7))
    assertEquals(90, WhatsAppMediaRules.clampAgeDays(90))
    assertEquals(730, WhatsAppMediaRules.clampAgeDays(730))
    assertEquals(730, WhatsAppMediaRules.clampAgeDays(9999))

    val now = 2_000_000_000_000L
    val exact90DaysAgo = now - 90L * WhatsAppMediaRules.MILLIS_PER_DAY
    val justUnder90Days = exact90DaysAgo + 1_000L

    assertTrue(WhatsAppMediaRules.matchesMinAge(exact90DaysAgo, now, 90))
    assertFalse(WhatsAppMediaRules.matchesMinAge(justUnder90Days, now, 90))
    assertTrue(WhatsAppMediaRules.matchesMinAge(now - 7L * WhatsAppMediaRules.MILLIS_PER_DAY, now, 1))
  }

  @Test
  fun `formatReason describes origin subfolder Sent Private weekly folder and age`() {
    val now = 1_800_000_000_000L
    val hundredDaysAgo = now - 100L * WhatsAppMediaRules.MILLIS_PER_DAY

    val modernRoot = WhatsAppMediaRules.KNOWN_MEDIA_ROOTS[0]
    val mediaFolder = Paths.get("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images")

    val receivedReason = WhatsAppMediaRules.formatReason(
      root = modernRoot,
      mediaFolder = mediaFolder,
      filePath = mediaFolder.resolve("IMG-2026.jpg"),
      lastModifiedMillis = hundredDaysAgo,
      nowMillis = now,
    )
    assertEquals("WhatsApp · Recebido · 100 dias", receivedReason)

    val sentReason = WhatsAppMediaRules.formatReason(
      root = modernRoot,
      mediaFolder = mediaFolder,
      filePath = mediaFolder.resolve("Sent/IMG-2026.jpg"),
      lastModifiedMillis = hundredDaysAgo,
      nowMillis = now,
    )
    assertEquals("WhatsApp · Enviado (Sent) · 100 dias", sentReason)

    val bizLegacyRoot = WhatsAppMediaRules.KNOWN_MEDIA_ROOTS[3]
    val voiceFolder = Paths.get("/storage/emulated/0/WhatsApp Business/Media/WhatsApp Business Voice Notes")
    val weeklyReason = WhatsAppMediaRules.formatReason(
      root = bizLegacyRoot,
      mediaFolder = voiceFolder,
      filePath = voiceFolder.resolve("202546/PTT-001.opus"),
      lastModifiedMillis = hundredDaysAgo,
      nowMillis = now,
    )
    assertEquals("WhatsApp Business (legado) · Subpasta 202546 · 100 dias", weeklyReason)
  }

  @Test
  fun `parseEnabledNames ignores unknown names cleanly`() {
    val parsed = WhatsAppMediaType.parseEnabledNames(setOf("IMAGES", "INVALID", "ANIMATED_GIFS"))
    assertEquals(setOf(WhatsAppMediaType.IMAGES, WhatsAppMediaType.ANIMATED_GIFS), parsed)
  }
}
