package io.github.km20197.limpadroid.data.log

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalScanLoggerTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `rotates log files cleanly without exceeding maxFiles`() {
    val logDir = tempFolder.newFolder("logs")
    val logger = LocalScanLogger(
      logDirectory = logDir,
      maxFileBytes = 250L,
      maxFiles = 3,
    )

    repeat(20) { idx ->
      logger.logEvent("SCAN_TEST", "entry=$idx path=/storage/emulated/0/Download/item_$idx.tmp")
    }

    assertTrue(File(logDir, "scan.log").exists())
    assertTrue(File(logDir, "scan.log.1").exists())
    assertTrue(File(logDir, "scan.log.2").exists())
    assertFalse(File(logDir, "scan.log.3").exists())
    assertTrue(logger.readRecentLines(10).isNotEmpty())
  }
}
