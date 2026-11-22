package io.github.km20197.limpadroid.domain.fs

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

enum class PathRejectionReason {
  EMPTY_PATH,
  PARENT_TRAVERSAL,
  SYMLINK_FORBIDDEN,
  OUTSIDE_ROOT,
  ROOT_ITSELF,
  FIXED_EXCLUSION,
  USER_EXCLUSION,
  UNRESOLVABLE,
}

sealed interface PathGuardResult {
  data class Allowed(val resolvedPath: Path) : PathGuardResult
  data class Rejected(val reason: PathRejectionReason, val detail: String) : PathGuardResult
}

interface PathGuard {
  val storageRoot: Path
  fun isAllowed(path: Path): Boolean
  fun isTraversalAllowed(path: Path): Boolean
  fun hasProtectedDescendant(dir: Path): Boolean
  fun validate(path: Path, allowRoot: Boolean = false): PathGuardResult
  fun fixedExclusions(): List<Path>
  fun isProtectedOrExcluded(path: Path): Boolean {
    val rejected = validate(path, allowRoot = false) as? PathGuardResult.Rejected ?: return false
    return rejected.reason == PathRejectionReason.FIXED_EXCLUSION ||
      rejected.reason == PathRejectionReason.USER_EXCLUSION ||
      rejected.reason == PathRejectionReason.SYMLINK_FORBIDDEN ||
      rejected.reason == PathRejectionReason.OUTSIDE_ROOT
  }
}

class DefaultPathGuard(
  rawStorageRoot: Path,
  applicationId: String,
  userExcludedPaths: Set<Path> = emptySet(),
) : PathGuard {

  override val storageRoot: Path = resolveRoot(rawStorageRoot)

  private val fixedExclusionRelatives: List<List<String>> = listOf(
    listOf("Android", "data"),
    listOf("Android", "obb"),
    listOf("Android", "media", applicationId),
    listOf("Documents", "LimpaDroid", ".lixeira"),
  )

  private val normalizedUserExclusions: List<List<String>> = userExcludedPaths.mapNotNull { excluded ->
    if (excluded.toString().isBlank() || containsParentTraversal(excluded)) return@mapNotNull null
    val candidate = if (excluded.isAbsolute) excluded else storageRoot.resolve(excluded)
    val resolved = resolveNonSymlinkForConfig(candidate) ?: return@mapNotNull null
    if (!resolved.startsWith(storageRoot) || resolved == storageRoot) return@mapNotNull null
    storageRoot.relativize(resolved).map { it.toString() }.takeIf { it.isNotEmpty() }
  }

  override fun fixedExclusions(): List<Path> = fixedExclusionRelatives.map { segments ->
    segments.fold(storageRoot) { acc, part -> acc.resolve(part) }
  }

  override fun isAllowed(path: Path): Boolean =
    validate(path, allowRoot = false) is PathGuardResult.Allowed

  override fun isTraversalAllowed(path: Path): Boolean =
    validate(path, allowRoot = true) is PathGuardResult.Allowed

  override fun hasProtectedDescendant(dir: Path): Boolean {
    val allowed = validate(dir, allowRoot = true) as? PathGuardResult.Allowed ?: return true
    val resolved = allowed.resolvedPath
    val dirSegments = if (resolved == storageRoot) {
      emptyList()
    } else {
      storageRoot.relativize(resolved).map { it.toString() }
    }
    if (fixedExclusionRelatives.any { prefix ->
        prefix.size >= dirSegments.size && startsWithSegmentsIgnoreCase(prefix, dirSegments)
      }
    ) {
      return true
    }
    return normalizedUserExclusions.any { prefix ->
      prefix.size >= dirSegments.size && startsWithSegmentsIgnoreCase(prefix, dirSegments)
    }
  }

  override fun validate(path: Path, allowRoot: Boolean): PathGuardResult {
    val rawString = path.toString()
    if (rawString.isBlank()) {
      return PathGuardResult.Rejected(PathRejectionReason.EMPTY_PATH, "Caminho vazio")
    }

    if (containsParentTraversal(path)) {
      return PathGuardResult.Rejected(
        PathRejectionReason.PARENT_TRAVERSAL,
        "Segmento '..' não permitido: $rawString",
      )
    }

    val absolute = path.toAbsolutePath()
    if (Files.isSymbolicLink(absolute)) {
      return PathGuardResult.Rejected(
        PathRejectionReason.SYMLINK_FORBIDDEN,
        "Link simbólico direto não permitido: $rawString",
      )
    }

    val resolved = resolveSafely(absolute)
      ?: return PathGuardResult.Rejected(
        PathRejectionReason.UNRESOLVABLE,
        "Não foi possível normalizar o caminho: $rawString",
      )

    if (!resolved.startsWith(storageRoot)) {
      return PathGuardResult.Rejected(
        PathRejectionReason.OUTSIDE_ROOT,
        "Caminho fora do armazenamento primário: $resolved",
      )
    }

    if (resolved == storageRoot) {
      return if (allowRoot) {
        PathGuardResult.Allowed(resolved)
      } else {
        PathGuardResult.Rejected(
          PathRejectionReason.ROOT_ITSELF,
          "A raiz de armazenamento não pode ser candidata nem movida",
        )
      }
    }

    if (hasSymbolicLinkUnderRoot(absolute)) {
      return PathGuardResult.Rejected(
        PathRejectionReason.SYMLINK_FORBIDDEN,
        "Link simbólico detectado no caminho: $rawString",
      )
    }

    val relativeSegments = storageRoot.relativize(resolved).map { it.toString() }

    if (fixedExclusionRelatives.any { prefix -> startsWithSegmentsIgnoreCase(relativeSegments, prefix) }) {
      return PathGuardResult.Rejected(
        PathRejectionReason.FIXED_EXCLUSION,
        "Diretório protegido por exclusão fixa: $resolved",
      )
    }

    if (normalizedUserExclusions.any { prefix -> startsWithSegmentsIgnoreCase(relativeSegments, prefix) }) {
      return PathGuardResult.Rejected(
        PathRejectionReason.USER_EXCLUSION,
        "Diretório protegido por exclusão do usuário: $resolved",
      )
    }

    return PathGuardResult.Allowed(resolved)
  }

  private fun containsParentTraversal(path: Path): Boolean {
    for (segment in path) {
      if (segment.toString() == "..") return true
    }
    return false
  }

  private fun hasSymbolicLinkUnderRoot(absoluteCandidate: Path): Boolean {
    val normalizedCandidate = absoluteCandidate.normalize()
    if (normalizedCandidate.startsWith(storageRoot)) {
      var cursor = storageRoot
      val relative = storageRoot.relativize(normalizedCandidate)
      for (part in relative) {
        cursor = cursor.resolve(part)
        if (Files.isSymbolicLink(cursor)) {
          return true
        }
      }
    } else {
      var cursor: Path? = normalizedCandidate
      while (cursor != null) {
        if (Files.isSymbolicLink(cursor)) return true
        cursor = cursor.parent
      }
    }
    return false
  }

  private fun resolveSafely(absolutePath: Path): Path? = runCatching {
    val normalized = absolutePath.normalize()
    if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
      runCatching { normalized.toRealPath() }.getOrNull()?.let { return@runCatching it }
    }
    val missingParts = ArrayDeque<String>()
    var current: Path? = normalized
    while (current != null) {
      val resolvedCurrent = if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        runCatching { current.toRealPath() }.getOrNull()
      } else {
        null
      }
      if (resolvedCurrent != null) {
        return@runCatching missingParts.fold(resolvedCurrent) { acc, name -> acc.resolve(name) }.normalize()
      }
      val fileName = current.fileName?.toString()
      if (fileName != null) {
        missingParts.addFirst(fileName)
      }
      current = current.parent
    }
    null
  }.getOrNull()

  private fun resolveNonSymlinkForConfig(path: Path): Path? {
    if (containsParentTraversal(path)) return null
    val abs = path.toAbsolutePath()
    if (Files.isSymbolicLink(abs)) return null
    return resolveSafely(abs)
  }

  private fun startsWithSegmentsIgnoreCase(pathSegments: List<String>, prefix: List<String>): Boolean {
    if (pathSegments.size < prefix.size) return false
    for (i in prefix.indices) {
      if (!pathSegments[i].equals(prefix[i], ignoreCase = true)) {
        return false
      }
    }
    return true
  }

  private companion object {
    fun resolveRoot(rawRoot: Path): Path = runCatching {
      if (Files.exists(rawRoot, LinkOption.NOFOLLOW_LINKS)) {
        rawRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
      } else {
        rawRoot.toAbsolutePath().normalize()
      }
    }.getOrElse { rawRoot.toAbsolutePath().normalize() }
  }
}
