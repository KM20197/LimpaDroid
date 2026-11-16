package io.github.km20197.limpadroid.domain.format

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatterTest {

  @Test
  fun `formats zero and negative bytes as 0 B`() {
    assertEquals("0 B", ByteFormatter.format(0L))
    assertEquals("0 B", ByteFormatter.format(-10L))
  }

  @Test
  fun `formats bytes below 1 KiB`() {
    assertEquals("512 B", ByteFormatter.format(512L))
  }

  @Test
  fun `formats KiB MiB and GiB in pt-BR`() {
    assertEquals("1,5 KiB", ByteFormatter.format(1536L))
    assertEquals("2,0 MiB", ByteFormatter.format(2L * 1024L * 1024L))
    assertEquals("1,00 GiB", ByteFormatter.format(1024L * 1024L * 1024L))
  }
}
