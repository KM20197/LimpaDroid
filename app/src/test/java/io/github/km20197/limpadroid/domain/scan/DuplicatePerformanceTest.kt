package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.fs.DefaultPathGuard
import io.github.km20197.limpadroid.domain.model.ScanEvent
import io.github.km20197.limpadroid.domain.model.ScanScope
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DuplicatePerformanceTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `200k synthetic files tree completes without OOM and cancels under 2 seconds`() = runBlocking {
    val root = tempFolder.newFolder("perf_200k").toPath().toRealPath()
    val totalFiles = 200_000
    val dirsCount = 400
    val filesPerDir = totalFiles / dirsCount // 500

    val dcimDir = Files.createDirectories(root.resolve("DCIM/Camera"))
    val dlDir = Files.createDirectories(root.resolve("Download"))

    val dirs = ArrayList<Path>(dirsCount)
    for (d in 0 until dirsCount) {
      dirs.add(Files.createDirectories(root.resolve("Pool/dir_$d")))
    }

    // Cria 200.000 arquivos sintéticos usando apenas 16 bytes cada (minSize configurado = 16 B)
    // com grupos de tamanho distintos ou cabeçalho/rodapé distintos para exercitar todo o pipeline
    // sem estourar memória nem disco do ambiente de execução.
    var index = 0
    for (d in 0 until dirsCount) {
      val dir = dirs[d]
      for (f in 0 until filesPerDir) {
        val size = 16 + (index % 2_000) // 100 arquivos por tamanho distinto -> exercita Estágio 1 e Estágio 2
        val target = dir.resolve("f_$index.bin")
        val content = ByteArray(size)
        content[0] = (index and 0xFF).toByte()
        content[1] = ((index ushr 8) and 0xFF).toByte()
        content[2] = ((index ushr 16) and 0xFF).toByte()
        Files.write(target, content)
        index += 1
      }
    }

    // Adiciona 2 pares de duplicados reais identificáveis
    val dupContent1 = ByteArray(64) { 0x5A }
    val dupContent2 = ByteArray(96) { 0x3C }
    Files.write(dcimDir.resolve("real_1.bin"), dupContent1)
    Files.write(dlDir.resolve("real_1_dup.bin"), dupContent1)
    Files.write(dcimDir.resolve("real_2.bin"), dupContent2)
    Files.write(dlDir.resolve("real_2_dup.bin"), dupContent2)

    val engine = DuplicateScanEngine(
      minSizeBytesProvider = { 16L },
    )
    val config = ScanConfig(
      sessionId = "s-200k",
      scope = ScanScope.DUPLICATE,
      rootPath = root,
      pathGuard = DefaultPathGuard(root, "io.github.km20197.limpadroid"),
    )

    // 1. Execução completa em 200k+ arquivos sem erro de memória
    val events = engine.scan(config).toList()
    val done = events.filterIsInstance<ScanEvent.Done>().single()
    val found = events.filterIsInstance<ScanEvent.Found>()

    assertTrue(done.scannedFiles >= totalFiles.toLong())
    // Apenas os 2 pares duplicados reais (4 registros: 2 preservados + 2 excedentes) devem ser emitidos
    assertEquals(4, found.size)
    assertEquals(2, found.count { it.isKept })
    assertEquals(2, found.count { !it.isKept && it.candidate.preselected })

    // 2. Cancelamento cooperativo em menos de 2 segundos
    val cancelStartJob = launch(Dispatchers.Default) {
      engine.scan(config).collect {
        // Consome fluxo até cancelamento
      }
    }
    delay(40L)
    val cancelRequestedAt = System.nanoTime()
    cancelStartJob.cancelAndJoin()
    val cancelElapsedMs = (System.nanoTime() - cancelRequestedAt) / 1_000_000L
    assertTrue("Cancelamento excedeu 2s: ${cancelElapsedMs}ms", cancelElapsedMs < 2_000L)
  }
}
