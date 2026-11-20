package io.github.km20197.limpadroid.domain.fs

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PathGuardTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private lateinit var root: Path
  private lateinit var outsideDir: Path
  private lateinit var guard: DefaultPathGuard
  private val appId = "io.github.km20197.limpadroid"

  @Before
  fun setUp() {
    root = tempFolder.newFolder("emulated_0").toPath().toRealPath()
    outsideDir = tempFolder.newFolder("outside").toPath().toRealPath()
    Files.createDirectories(root.resolve("DCIM/Camera"))
    Files.createDirectories(root.resolve("Android/data/com.other"))
    Files.createDirectories(root.resolve("Android/obb/com.other"))
    Files.createDirectories(root.resolve("Android/media/$appId"))
    Files.createDirectories(root.resolve("Android/media/com.whatsapp/WhatsApp/Media"))
    Files.createDirectories(root.resolve("Documents/LimpaDroid/.lixeira"))
    Files.createDirectories(root.resolve("CustomExcluded/Keep"))
    guard = DefaultPathGuard(
      rawStorageRoot = root,
      applicationId = appId,
      userExcludedPaths = setOf(root.resolve("CustomExcluded")),
    )
  }

  @Test
  fun `allows regular paths inside shared storage and WhatsApp media`() {
    val photo = Files.writeString(root.resolve("DCIM/Camera/img.jpg"), "data")
    val waMedia = Files.writeString(
      root.resolve("Android/media/com.whatsapp/WhatsApp/Media/img.jpg"),
      "wa",
    )

    assertTrue(guard.isAllowed(photo))
    assertTrue(guard.isAllowed(waMedia))
    assertTrue(guard.isTraversalAllowed(root))
    assertFalse(guard.isAllowed(root))
  }

  @Test
  fun `rejects fixed exclusions case-insensitively`() {
    val androidDataFile = Files.writeString(root.resolve("Android/data/com.other/cache.bin"), "x")
    val androidObbFile = Files.writeString(root.resolve("Android/obb/com.other/main.obb"), "x")
    val ownMediaFile = Files.writeString(root.resolve("Android/media/$appId/temp.dat"), "x")
    val trashFile = Files.writeString(root.resolve("Documents/LimpaDroid/.lixeira/item"), "x")
    val userExcluded = Files.writeString(root.resolve("CustomExcluded/Keep/a.txt"), "x")

    assertFalse(guard.isAllowed(androidDataFile))
    assertFalse(guard.isAllowed(androidObbFile))
    assertFalse(guard.isAllowed(ownMediaFile))
    assertFalse(guard.isAllowed(trashFile))
    assertFalse(guard.isAllowed(userExcluded))
    assertFalse(guard.isTraversalAllowed(root.resolve("android/DATA")))
  }

  @Test
  fun `rejects parent traversal and paths outside root`() {
    val traversal = root.resolve("DCIM/../../outside/secret.txt")
    val outsideFile = Files.writeString(outsideDir.resolve("secret.txt"), "x")

    val traversalResult = guard.validate(traversal)
    val outsideResult = guard.validate(outsideFile)

    assertTrue(traversalResult is PathGuardResult.Rejected)
    assertEquals(PathRejectionReason.PARENT_TRAVERSAL, (traversalResult as PathGuardResult.Rejected).reason)
    assertTrue(outsideResult is PathGuardResult.Rejected)
    assertEquals(PathRejectionReason.OUTSIDE_ROOT, (outsideResult as PathGuardResult.Rejected).reason)
  }

  @Test
  fun `rejects symbolic links pointing inside or outside root`() {
    val outsideTarget = Files.writeString(outsideDir.resolve("target.txt"), "secret")
    val insideTargetDir = Files.createDirectories(root.resolve("Documents/RealDir"))
    Files.writeString(insideTargetDir.resolve("doc.pdf"), "pdf")

    val symlinkCreated = runCatching {
      Files.createSymbolicLink(root.resolve("DCIM/escape_link.txt"), outsideTarget)
      Files.createSymbolicLink(root.resolve("DCIM/internal_link_dir"), insideTargetDir)
      true
    }.getOrDefault(false)
    assumeTrue("Symlinks require OS support", symlinkCreated)

    val escapeResult = guard.validate(root.resolve("DCIM/escape_link.txt"))
    val internalDirResult = guard.validate(root.resolve("DCIM/internal_link_dir"))
    val nestedThroughSymlink = guard.validate(root.resolve("DCIM/internal_link_dir/doc.pdf"))

    assertTrue(escapeResult is PathGuardResult.Rejected)
    assertEquals(PathRejectionReason.SYMLINK_FORBIDDEN, (escapeResult as PathGuardResult.Rejected).reason)
    assertTrue(internalDirResult is PathGuardResult.Rejected)
    assertEquals(PathRejectionReason.SYMLINK_FORBIDDEN, (internalDirResult as PathGuardResult.Rejected).reason)
    assertTrue(nestedThroughSymlink is PathGuardResult.Rejected)
  }

  @Test
  fun `hasProtectedDescendant detects fixed and nested user exclusions`() {
    val docsDir = Files.createDirectories(root.resolve("Documents/LimpaDroid"))
    val normalDir = Files.createDirectories(root.resolve("Documents/NormalFolder"))

    assertTrue(guard.hasProtectedDescendant(root.resolve("Documents")))
    assertTrue(guard.hasProtectedDescendant(docsDir))
    assertTrue(guard.hasProtectedDescendant(root.resolve("Android")))
    assertTrue(guard.hasProtectedDescendant(root.resolve("CustomExcluded")))
    assertFalse(guard.hasProtectedDescendant(normalDir))
  }
}
