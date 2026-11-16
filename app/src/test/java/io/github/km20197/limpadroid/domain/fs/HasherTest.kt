package io.github.km20197.limpadroid.domain.fs

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HasherTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `sha256 computes accurate digest across multi-megabyte buffer boundary`() = runTest {
    val payload = ByteArray(2 * 1024 * 1024 + 321) { index -> (index % 251).toByte() }
    val file = tempFolder.newFile("large.bin").toPath()
    Files.write(file, payload)

    val expected = MessageDigest.getInstance("SHA-256")
      .digest(payload)
      .joinToString("") { "%02x".format(it) }

    val actual = Hasher.sha256(file)
    assertEquals(expected, actual)
  }

  @Test
  fun `partialSha256 detects differences in tail window for large files`() = runTest {
    val size = 200 * 1024 // > 128 KiB (2 * 64 KiB)
    val base = ByteArray(size) { 0x2A }
    val modifiedTail = base.copyOf().apply { this[size - 10] = 0x55 }

    val fileA = tempFolder.newFile("a.bin").toPath()
    val fileB = tempFolder.newFile("b.bin").toPath()
    Files.write(fileA, base)
    Files.write(fileB, modifiedTail)

    assertNotEquals(Hasher.partialSha256(fileA), Hasher.partialSha256(fileB))
  }

  @Test
  fun `partialSha256 hashes small files deterministically`() = runTest {
    val content = "limpadroid-hash-check".toByteArray()
    val file1 = tempFolder.newFile("s1.txt").toPath()
    val file2 = tempFolder.newFile("s2.txt").toPath()
    Files.write(file1, content)
    Files.write(file2, content)

    assertEquals(Hasher.partialSha256(file1), Hasher.partialSha256(file2))
    assertEquals(Hasher.sha256(file1), Hasher.sha256(file2))
  }
}
