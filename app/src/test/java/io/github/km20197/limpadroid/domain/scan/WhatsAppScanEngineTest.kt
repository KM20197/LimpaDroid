package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaRules
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaType
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppRuleId
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppScanSettings
import java.nio.file.Files
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

class WhatsAppScanEngineTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val selfAppId = "io.github.km20197.limpadroid"
  private val fixedNow = 1_800_000_000_000L

  @Test
  fun `scan inspects all four roots Sent and weekly voice folders honors age cutoff and blocks forbidden folders`() =
    runTest {
      val root = tempFolder.newFolder("wa_shared").toPath().toRealPath()
      val oldTime = FileTime.fromMillis(fixedNow - 120L * WhatsAppMediaRules.MILLIS_PER_DAY)
      val recentTime = FileTime.fromMillis(fixedNow - 10L * WhatsAppMediaRules.MILLIS_PER_DAY)

      // 1) WhatsApp moderno (recebida + Sent + recente ignorada + pastas proibidas)
      val waMedia = Files.createDirectories(root.resolve("Android/media/com.whatsapp/WhatsApp/Media"))
      val waImgOld = Files.writeString(
        Files.createDirectories(waMedia.resolve("WhatsApp Images")).resolve("IMG-OLD.jpg"),
        "img-old",
      ).also { Files.setLastModifiedTime(it, oldTime) }
      val waImgSentOld = Files.writeString(
        Files.createDirectories(waMedia.resolve("WhatsApp Images/Sent")).resolve("IMG-SENT.jpg"),
        "img-sent",
      ).also { Files.setLastModifiedTime(it, oldTime) }
      val waImgRecent = Files.writeString(
        waMedia.resolve("WhatsApp Images/IMG-RECENT.jpg"),
        "img-recent",
      ).also { Files.setLastModifiedTime(it, recentTime) }
      val waGifOff = Files.writeString(
        Files.createDirectories(waMedia.resolve("WhatsApp Animated Gifs")).resolve("GIF-OLD.mp4"),
        "gif-old",
      ).also { Files.setLastModifiedTime(it, oldTime) }

      // Pastas proibidas (antigas, porém NUNCA tocadas)
      val forbiddenFolders = listOf(
        ".Statuses/status1.jpg",
        "Backups/msgstore.db",
        "Databases/msgstore.crypt14",
        "WhatsApp Documents/doc.pdf",
        "WhatsApp Stickers/stk.webp",
        "WhatsApp Profile Photos/avatar.jpg",
        "WhatsApp Bug Reports/log.txt",
        ".Shared/part.tmp",
        ".Links/link.dat",
        ".trash/deleted.jpg",
        "WhatsApp Images/.trash/internal.jpg",
      )
      forbiddenFolders.forEach { rel ->
        val p = waMedia.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, "forbidden-payload")
        Files.setLastModifiedTime(p, oldTime)
      }

      // 2) WhatsApp Business moderno (vídeo e áudio antigo)
      val bizMedia = Files.createDirectories(root.resolve("Android/media/com.whatsapp.w4b/WhatsApp Business/Media"))
      val bizVid = Files.writeString(
        Files.createDirectories(bizMedia.resolve("WhatsApp Business Video/Sent")).resolve("VID-BIZ.mp4"),
        "vid-biz",
      ).also { Files.setLastModifiedTime(it, oldTime) }
      val bizAudio = Files.writeString(
        Files.createDirectories(bizMedia.resolve("WhatsApp Business Audio")).resolve("AUD-BIZ.m4a"),
        "aud-biz",
      ).also { Files.setLastModifiedTime(it, oldTime) }

      // 3) WhatsApp legado (notas de voz semanais)
      val legacyWa = Files.createDirectories(root.resolve("WhatsApp/Media"))
      val legacyVoice = Files.writeString(
        Files.createDirectories(legacyWa.resolve("WhatsApp Voice Notes/202546")).resolve("PTT-OLD.opus"),
        "ptt-old",
      ).also { Files.setLastModifiedTime(it, oldTime) }

      // 4) WhatsApp Business legado
      val legacyBiz = Files.createDirectories(root.resolve("WhatsApp Business/Media"))
      val legacyBizImg = Files.writeString(
        Files.createDirectories(legacyBiz.resolve("WhatsApp Business Images")).resolve("BIZ-LEG.jpg"),
        "biz-leg",
      ).also { Files.setLastModifiedTime(it, oldTime) }

      val engine = WhatsAppScanEngine(
        settingsProvider = {
          WhatsAppScanSettings(
            minAgeDays = 90,
            enabledMediaTypes = WhatsAppMediaType.DEFAULT_ENABLED_TYPES,
            autoPreselect = false,
          )
        },
        clock = AppClock { fixedNow },
      )

      val events = engine.scan(
        ScanConfig(
          sessionId = "wa-1",
          scope = ScanScope.WHATSAPP,
          rootPath = root,
          pathGuard = DefaultPathGuard(root, selfAppId),
        ),
      ).toList()

      assertTrue(events.any { it is ScanEvent.Done })
      val found = events.filterIsInstance<ScanEvent.Found>()
      val paths = found.map { it.candidate.path }.toSet()

      assertEquals(
        setOf(
          waImgOld.toString(),
          waImgSentOld.toString(),
          bizVid.toString(),
          bizAudio.toString(),
          legacyVoice.toString(),
          legacyBizImg.toString(),
        ),
        paths,
      )
      assertFalse(waImgRecent.toString() in paths)
      assertFalse(waGifOff.toString() in paths)
      forbiddenFolders.forEach { rel ->
        assertFalse(
          "Pasta proibida não pode gerar candidato: $rel",
          paths.any { it.endsWith(rel) },
        )
      }

      found.forEach { ev ->
        assertEquals(Category.WHATSAPP, ev.candidate.category)
        assertEquals(Confidence.HIGH, ev.candidate.confidence)
        assertFalse("Primeira execução vem desmarcada", ev.candidate.preselected)
      }
      assertEquals(
        WhatsAppRuleId.WHATSAPP_VOICE_NOTES.ruleId,
        found.single { it.candidate.path == legacyVoice.toString() }.candidate.ruleId,
      )
      assertTrue(
        found.single { it.candidate.path == waImgSentOld.toString() }
          .reason.orEmpty().contains("Sent"),
      )
    }

  @Test
  fun `scan enables Animated Gifs and remembers autoPreselect when configured by user`() = runTest {
    val root = tempFolder.newFolder("wa_gifs").toPath().toRealPath()
    val oldTime = FileTime.fromMillis(fixedNow - 45L * WhatsAppMediaRules.MILLIS_PER_DAY)

    val waMedia = Files.createDirectories(root.resolve("Android/media/com.whatsapp/WhatsApp/Media"))
    val gifFile = Files.writeString(
      Files.createDirectories(waMedia.resolve("WhatsApp Animated Gifs")).resolve("GIF-01.mp4"),
      "gif-data",
    ).also { Files.setLastModifiedTime(it, oldTime) }
    val imgFile = Files.writeString(
      Files.createDirectories(waMedia.resolve("WhatsApp Images")).resolve("IMG-01.jpg"),
      "img-data",
    ).also { Files.setLastModifiedTime(it, oldTime) }

    val engine = WhatsAppScanEngine(
      settingsProvider = {
        WhatsAppScanSettings(
          minAgeDays = 30,
          enabledMediaTypes = setOf(WhatsAppMediaType.ANIMATED_GIFS),
          autoPreselect = true,
        )
      },
      clock = AppClock { fixedNow },
    )

    val found = engine.scan(
      ScanConfig(
        sessionId = "wa-2",
        scope = ScanScope.WHATSAPP,
        rootPath = root,
        pathGuard = DefaultPathGuard(root, selfAppId),
      ),
    ).toList().filterIsInstance<ScanEvent.Found>()

    assertEquals(1, found.size)
    assertEquals(gifFile.toString(), found.single().candidate.path)
    assertEquals(WhatsAppRuleId.WHATSAPP_ANIMATED_GIFS.ruleId, found.single().candidate.ruleId)
    assertTrue(found.single().candidate.preselected)
    assertFalse(found.any { it.candidate.path == imgFile.toString() })
  }

  @Test
  fun `scan skips symlinks honors user exclusions and logs unreadable folders without failing`() = runTest {
    val root = tempFolder.newFolder("wa_guards").toPath().toRealPath()
    val oldTime = FileTime.fromMillis(fixedNow - 100L * WhatsAppMediaRules.MILLIS_PER_DAY)

    val waMedia = Files.createDirectories(root.resolve("Android/media/com.whatsapp/WhatsApp/Media"))
    val imgsDir = Files.createDirectories(waMedia.resolve("WhatsApp Images"))
    val excludedDir = Files.createDirectories(imgsDir.resolve("Privado"))
    Files.writeString(excludedDir.resolve("priv.jpg"), "priv").also {
      Files.setLastModifiedTime(it, oldTime)
    }
    val unreadableDir = Files.createDirectories(waMedia.resolve("WhatsApp Video"))
    Files.writeString(unreadableDir.resolve("vid.mp4"), "vid").also {
      Files.setLastModifiedTime(it, oldTime)
    }
    val validFile = Files.writeString(imgsDir.resolve("ok.jpg"), "ok").also {
      Files.setLastModifiedTime(it, oldTime)
    }

    val targetOutside = tempFolder.newFolder("outside").toPath().resolve("link_target.jpg")
    Files.writeString(targetOutside, "outside")
    runCatching {
      Files.createSymbolicLink(imgsDir.resolve("sym.jpg"), targetOutside)
    }

    val guard = DefaultPathGuard(
      rawStorageRoot = root,
      applicationId = selfAppId,
      userExcludedPaths = setOf(excludedDir),
    )

    val engine = WhatsAppScanEngine(
      settingsProvider = { WhatsAppScanSettings(minAgeDays = 90) },
      clock = AppClock { fixedNow },
      readPermissionChecker = { path -> path != unreadableDir },
    )

    val events = engine.scan(
      ScanConfig(
        sessionId = "wa-3",
        scope = ScanScope.WHATSAPP,
        rootPath = root,
        pathGuard = guard,
      ),
    ).toList()

    val found = events.filterIsInstance<ScanEvent.Found>()
    assertEquals(1, found.size)
    assertEquals(validFile.toString(), found.single().candidate.path)

    val errors = events.filterIsInstance<ScanEvent.ErrorLogged>()
    assertEquals(1, errors.size)
    assertEquals(unreadableDir.toString(), errors.single().error.path)
    assertTrue(events.any { it is ScanEvent.Done })
  }

  @Test
  fun `scan supports cooperative cancellation`() = runTest {
    val root = tempFolder.newFolder("wa_cancel").toPath().toRealPath()
    val oldTime = FileTime.fromMillis(fixedNow - 100L * WhatsAppMediaRules.MILLIS_PER_DAY)
    val imgsDir = Files.createDirectories(root.resolve("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images"))
    repeat(5) { idx ->
      val f = Files.writeString(imgsDir.resolve("IMG-$idx.jpg"), "data-$idx")
      Files.setLastModifiedTime(f, oldTime)
    }

    val events = WhatsAppScanEngine(clock = AppClock { fixedNow })
      .scan(
        ScanConfig(
          sessionId = "wa-cancel",
          scope = ScanScope.WHATSAPP,
          rootPath = root,
          pathGuard = DefaultPathGuard(root, selfAppId),
        ),
      )
      .take(2)
      .toList()

    assertEquals(2, events.size)
    assertFalse(events.any { it is ScanEvent.Done })
  }
}
