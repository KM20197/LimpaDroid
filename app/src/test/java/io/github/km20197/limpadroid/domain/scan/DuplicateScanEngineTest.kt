package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateRuleId
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicateScanEngineTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `4-stage pipeline detects exact duplicates excludes Android except WhatsApp media and respects min size`() = runTest {
    val root = tempFolder.newFolder("dup_root").toPath().toRealPath()

    // 1. Duplicado verdadeiro com pasta de prioridade (DCIM/Camera vs Download) >= 4 KiB
    val payloadA = ByteArray(150 * 1024) { idx -> (idx % 251).toByte() }
    val camDir = Files.createDirectories(root.resolve("DCIM/Camera"))
    val dlDir = Files.createDirectories(root.resolve("Download"))
    val camFile = Files.write(camDir.resolve("foto.jpg"), payloadA)
    val dlFile = Files.write(dlDir.resolve("foto_copia.jpg"), payloadA)
    Files.setLastModifiedTime(camFile, FileTime.fromMillis(5_000L))
    Files.setLastModifiedTime(dlFile, FileTime.fromMillis(1_000L))

    // 2. Mesmo tamanho e mesmos 64 KiB iniciais/finais, mas byte diferente no meio -> NÃO é duplicado
    val payloadB = payloadA.copyOf()
    payloadB[80 * 1024] = (payloadB[80 * 1024].toInt() xor 0x55).toByte()
    Files.write(dlDir.resolve("foto_meio_diferente.jpg"), payloadB)

    // 3. Mesmo tamanho (150 KiB) com início diferente -> descartado no estágio 2 (hash parcial)
    val payloadC = payloadA.copyOf()
    payloadC[10] = (payloadC[10].toInt() xor 0x33).toByte()
    Files.write(dlDir.resolve("foto_inicio_diferente.jpg"), payloadC)

    // 4. Duplicado fora de pasta de prioridade -> DUPLICATE_NO_PRIORITY (MEDIUM, preselected = false)
    val misc1 = Files.createDirectories(root.resolve("Temp/A"))
    val misc2 = Files.createDirectories(root.resolve("Outros/B"))
    val noPrioPayload = ByteArray(5_000) { 0x42 }
    val noPrioOld = Files.write(misc1.resolve("arq.bin"), noPrioPayload)
    val noPrioNew = Files.write(misc2.resolve("arq_dup.bin"), noPrioPayload)
    Files.setLastModifiedTime(noPrioOld, FileTime.fromMillis(1_000L))
    Files.setLastModifiedTime(noPrioNew, FileTime.fromMillis(3_000L))

    // 5. Arquivos menores que 4 KiB -> ignorados mesmo se idênticos
    val tinyPayload = ByteArray(1_024) { 0x11 }
    Files.write(camDir.resolve("tiny1.bin"), tinyPayload)
    Files.write(dlDir.resolve("tiny2.bin"), tinyPayload)

    // 6. Exclusões: .thumbnails, pasta oculta, Android/data, Android/media/com.other, .Statuses, e WhatsApp Images INCLUÍDA
    val thumbsDir = Files.createDirectories(root.resolve("DCIM/.thumbnails"))
    Files.write(thumbsDir.resolve("thumb_dup.jpg"), payloadA)
    val hiddenDir = Files.createDirectories(root.resolve(".oculto"))
    Files.write(hiddenDir.resolve("hidden_dup.jpg"), payloadA)
    val androidData = Files.createDirectories(root.resolve("Android/data/com.app"))
    Files.write(androidData.resolve("data_dup.jpg"), payloadA)
    val otherMedia = Files.createDirectories(root.resolve("Android/media/com.other.app"))
    Files.write(otherMedia.resolve("other_dup.jpg"), payloadA)
    val waStatuses = Files.createDirectories(root.resolve("Android/media/com.whatsapp/WhatsApp/Media/.Statuses"))
    Files.write(waStatuses.resolve("status_dup.jpg"), payloadA)
    val waBackups = Files.createDirectories(root.resolve("Android/media/com.whatsapp/WhatsApp/Backups"))
    Files.write(waBackups.resolve("backup_dup.jpg"), payloadA)

    val waImages = Files.createDirectories(root.resolve("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent"))
    val waImgDup = Files.write(waImages.resolve("wa_foto.jpg"), payloadA)

    val cacheRepo = InMemoryHashCacheRepository()
    val engine = DuplicateScanEngine(
      hashCacheRepository = cacheRepo,
      clock = AppClock { 99_999L },
    )
    val config = ScanConfig(
      sessionId = "s-dup-1",
      scope = ScanScope.DUPLICATE,
      rootPath = root,
      pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid"),
    )

    val events = engine.scan(config).toList()
    val found = events.filterIsInstance<ScanEvent.Found>()

    // Grupo 1 (payloadA): camFile (kept), dlFile (removable), waImgDup (removable) = 3 itens
    // Grupo 2 (noPrioPayload): noPrioOld (kept), noPrioNew (removable) = 2 itens
    assertEquals(5, found.size)

    val groupA = found.filter { it.candidate.sizeBytes == payloadA.size.toLong() }
    assertEquals(3, groupA.size)
    assertEquals(1, groupA.count { it.isKept })
    assertEquals(camFile.toString(), groupA.single { it.isKept }.candidate.path)
    assertFalse(groupA.single { it.isKept }.candidate.preselected)
    assertEquals(
      setOf(dlFile.toString(), waImgDup.toString()),
      groupA.filter { !it.isKept }.map { it.candidate.path }.toSet(),
    )
    assertTrue(groupA.filter { !it.isKept }.all {
      it.candidate.ruleId == DuplicateRuleId.DUPLICATE_PRIORITY.ruleId &&
        it.candidate.confidence == Confidence.HIGH &&
        it.candidate.preselected
    })

    val groupNoPrio = found.filter { it.candidate.sizeBytes == noPrioPayload.size.toLong() }
    assertEquals(2, groupNoPrio.size)
    assertEquals(noPrioOld.toString(), groupNoPrio.single { it.isKept }.candidate.path)
    val noPrioRemovable = groupNoPrio.single { !it.isKept }
    assertEquals(noPrioNew.toString(), noPrioRemovable.candidate.path)
    assertEquals(DuplicateRuleId.DUPLICATE_NO_PRIORITY.ruleId, noPrioRemovable.candidate.ruleId)
    assertEquals(Confidence.MEDIUM, noPrioRemovable.candidate.confidence)
    assertFalse(noPrioRemovable.candidate.preselected)

    // Cache preenchido apenas pelos sobreviventes do estágio 3 (2 + 3 = 5 entradas no máximo, ou 7 se meio-diferente/noPrio também entraram)
    assertTrue(cacheRepo.storage.containsKey(camFile.toString()))
    assertTrue(cacheRepo.storage.containsKey(waImgDup.toString()))
    assertFalse(cacheRepo.storage.containsKey(root.resolve("Download/foto_inicio_diferente.jpg").toString()))
  }

  @Test
  fun `hash_cache reuses cached sha256 when size and mtime match and recomputes when mtime changes`() = runTest {
    val root = tempFolder.newFolder("cache_root").toPath().toRealPath()
    val dirA = Files.createDirectories(root.resolve("DCIM"))
    val dirB = Files.createDirectories(root.resolve("Download"))

    val content = ByteArray(8_192) { 0x2A }
    val fileA = Files.write(dirA.resolve("a.dat"), content)
    val fileB = Files.write(dirB.resolve("b.dat"), content)
    Files.setLastModifiedTime(fileA, FileTime.fromMillis(10_000L))
    Files.setLastModifiedTime(fileB, FileTime.fromMillis(20_000L))

    val cacheRepo = InMemoryHashCacheRepository()
    val engine = DuplicateScanEngine(hashCacheRepository = cacheRepo)
    val config = ScanConfig(
      sessionId = "s-cache",
      scope = ScanScope.DUPLICATE,
      rootPath = root,
      pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid"),
    )

    // 1a execução: preenche o cache
    engine.scan(config).toList()
    assertEquals(1, cacheRepo.upsertCalls)
    assertEquals(2, cacheRepo.lastUpsertedCount)

    // 2a execução com mesmo size e mtime: reaproveita o cache sem gravar novamente
    engine.scan(config).toList()
    assertEquals(1, cacheRepo.upsertCalls)

    // Altera mtime de fileB: invalida cache apenas de fileB e recalcula
    Files.setLastModifiedTime(fileB, FileTime.fromMillis(30_000L))
    engine.scan(config).toList()
    assertEquals(2, cacheRepo.upsertCalls)
    assertEquals(1, cacheRepo.lastUpsertedCount)
  }

  @Test
  fun `respects user excluded paths and symlink filtering`() = runTest {
    val root = tempFolder.newFolder("excl_root").toPath().toRealPath()
    val keepDir = Files.createDirectories(root.resolve("Documents"))
    val privDir = Files.createDirectories(root.resolve("Privado"))
    val payload = ByteArray(4_096) { 0x7E }
    val f1 = Files.write(keepDir.resolve("doc.bin"), payload)
    val f2 = Files.write(privDir.resolve("doc_priv.bin"), payload)

    val externalDir = tempFolder.newFolder("outside").toPath().toRealPath()
    val extFile = Files.write(externalDir.resolve("ext.bin"), payload)
    runCatching { Files.createSymbolicLink(keepDir.resolve("link.bin"), extFile) }

    val engine = DuplicateScanEngine()
    val config = ScanConfig(
      sessionId = "s-excl",
      scope = ScanScope.DUPLICATE,
      rootPath = root,
      pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid", setOf(privDir)),
    )

    val found = engine.scan(config).toList().filterIsInstance<ScanEvent.Found>()
    assertTrue(found.isEmpty())
    assertTrue(Files.exists(f1))
    assertTrue(Files.exists(f2))
  }

  private class InMemoryHashCacheRepository : HashCacheRepository {
    val storage = LinkedHashMap<String, CachedHashEntry>()
    var upsertCalls = 0
    var lastUpsertedCount = 0

    override suspend fun getByPath(path: String): CachedHashEntry? = storage[path]

    override suspend fun getByPaths(paths: List<String>): Map<String, CachedHashEntry> =
      paths.mapNotNull { path -> storage[path]?.let { path to it } }.toMap()

    override suspend fun upsertAll(entries: List<CachedHashEntry>) {
      if (entries.isEmpty()) return
      upsertCalls += 1
      lastUpsertedCount = entries.size
      entries.forEach { storage[it.path] = it }
    }
  }
}
