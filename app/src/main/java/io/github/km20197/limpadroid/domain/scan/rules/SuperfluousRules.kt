package io.github.km20197.limpadroid.domain.scan.rules

import io.github.km20197.limpadroid.domain.model.AppClock
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.FileEntry
import io.github.km20197.limpadroid.domain.scan.RuleMatch
import io.github.km20197.limpadroid.domain.scan.ScanRule
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.Locale

enum class SuperfluousRuleId(
  val ruleId: String,
  val confidence: Confidence,
  val isFilesystemRule: Boolean = true,
) {
  THUMBS("THUMBS", Confidence.HIGH),
  PARTIAL_DL("PARTIAL_DL", Confidence.HIGH),
  LOST_DIR("LOST_DIR", Confidence.MEDIUM),
  EMPTY_DIR("EMPTY_DIR", Confidence.MEDIUM),
  ZERO_BYTE("ZERO_BYTE", Confidence.MEDIUM),
  OLD_APK("OLD_APK", Confidence.LOW),
  STALE_MEDIASTORE("STALE_MEDIASTORE", Confidence.HIGH, isFilesystemRule = false),
  ;

  companion object {
    val DEFAULT_ENABLED_IDS: Set<String> = entries.mapTo(linkedSetOf()) { it.ruleId }

    fun fromId(ruleId: String): SuperfluousRuleId? =
      entries.firstOrNull { it.ruleId == ruleId }
  }
}

interface DocumentedScanRule : ScanRule {
  val description: String
  val positiveExamples: List<String>
  val negativeExamples: List<String>
}

object ThumbsRule : DocumentedScanRule {
  override val ruleId: String = SuperfluousRuleId.THUMBS.ruleId
  override val category: Category = Category.SUPERFLUOUS
  override val confidence: Confidence = Confidence.HIGH
  override val description: String = "Conteúdo de pastas .thumbnails em DCIM e Pictures."
  override val positiveExamples: List<String> = listOf(
    "DCIM/.thumbnails/170000001.jpg",
    "Pictures/.thumbnails/.thumbdata4--123",
    "DCIM/Camera/.thumbnails/cache_01.jpg",
  )
  override val negativeExamples: List<String> = listOf(
    "DCIM/Camera/IMG_20261117.jpg",
    "Download/.thumbnails/preview.jpg",
    "DCIM/.thumbnails/.nomedia",
    "DCIM/.thumbnails",
  )

  override fun evaluate(entry: FileEntry, storageRootPath: String): RuleMatch? {
    if (entry.isDirectory) return null
    val segments = relativeSegments(entry.path, storageRootPath) ?: return null
    if (segments.size < 3) return null
    val top = segments[0]
    if (!top.equals("DCIM", ignoreCase = true) && !top.equals("Pictures", ignoreCase = true)) {
      return null
    }
    val fileName = segments.last()
    if (fileName.equals(".nomedia", ignoreCase = true)) return null
    val parentSegments = segments.subList(1, segments.size - 1)
    if (parentSegments.none { it.equals(".thumbnails", ignoreCase = true) }) {
      return null
    }
    return RuleMatch(
      ruleId = ruleId,
      category = category,
      confidence = confidence,
      reason = "Miniatura em cache de $top/.thumbnails",
    )
  }
}

class PartialDlRule(
  private val clock: AppClock = AppClock.System,
) : DocumentedScanRule {
  override val ruleId: String = SuperfluousRuleId.PARTIAL_DL.ruleId
  override val category: Category = Category.SUPERFLUOUS
  override val confidence: Confidence = Confidence.HIGH
  override val description: String =
    "Downloads parciais ou temporários (.crdownload, .part, .partial, .download, .tmp) em Download modificados há mais de 7 dias."
  override val positiveExamples: List<String> = listOf(
    "Download/instalador.crdownload",
    "Download/sub/arquivo.part",
    "Download/pacote.partial",
    "Download/imagem.download",
    "Download/rascunho.tmp",
  )
  override val negativeExamples: List<String> = listOf(
    "Download/ativo.crdownload",
    "Documents/rascunho.tmp",
    "Download/manual.pdf",
    "Download/pasta.tmp",
  )

  override fun evaluate(entry: FileEntry, storageRootPath: String): RuleMatch? {
    if (entry.isDirectory) return null
    val segments = relativeSegments(entry.path, storageRootPath) ?: return null
    if (segments.size < 2 || !segments[0].equals("Download", ignoreCase = true)) return null
    val extension = segments.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension !in PARTIAL_EXTENSIONS) return null
    val ageMillis = clock.nowMillis() - entry.lastModified
    if (ageMillis <= SEVEN_DAYS_MILLIS) return null
    return RuleMatch(
      ruleId = ruleId,
      category = category,
      confidence = confidence,
      reason = "Download parcial (.$extension) sem alteração há mais de 7 dias",
    )
  }

  companion object {
    const val SEVEN_DAYS_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
    private val PARTIAL_EXTENSIONS = setOf("crdownload", "part", "partial", "download", "tmp")
  }
}

object LostDirRule : DocumentedScanRule {
  override val ruleId: String = SuperfluousRuleId.LOST_DIR.ruleId
  override val category: Category = Category.SUPERFLUOUS
  override val confidence: Confidence = Confidence.MEDIUM
  override val description: String = "Conteúdo residual da pasta LOST.DIR."
  override val positiveExamples: List<String> = listOf(
    "LOST.DIR/10421",
    "LOST.DIR/sub/fragmento.bin",
  )
  override val negativeExamples: List<String> = listOf(
    "LOST.DIR",
    "Download/LOST.DIR/arquivo.txt",
    "Documents/lost/10421",
  )

  override fun evaluate(entry: FileEntry, storageRootPath: String): RuleMatch? {
    if (entry.isDirectory) return null
    val segments = relativeSegments(entry.path, storageRootPath) ?: return null
    if (segments.size < 2 || !segments[0].equals("LOST.DIR", ignoreCase = true)) return null
    return RuleMatch(
      ruleId = ruleId,
      category = category,
      confidence = confidence,
      reason = "Arquivo recuperado residual em LOST.DIR",
    )
  }
}

object EmptyDirRule : DocumentedScanRule {
  override val ruleId: String = SuperfluousRuleId.EMPTY_DIR.ruleId
  override val category: Category = Category.SUPERFLUOUS
  override val confidence: Confidence = Confidence.MEDIUM
  override val description: String =
    "Diretórios vazios (sem descendentes), preservando pastas-raiz padrão e pastas com .nomedia."
  override val positiveExamples: List<String> = listOf(
    "Download/pasta_vazia",
    "Documents/rascunho_antigo/vazia",
  )
  override val negativeExamples: List<String> = listOf(
    "DCIM",
    "Download",
    "Documents",
    "Pictures",
    "Movies",
    "Music",
    "Alarms",
    "Notifications",
    "Ringtones",
    "Podcasts",
    "Audiobooks",
    "Recordings",
    "Download/com_nomedia",
    "Download/nao_vazia",
  )

  private val PROTECTED_ROOT_DIRS = setOf(
    "dcim",
    "download",
    "documents",
    "pictures",
    "movies",
    "music",
    "alarms",
    "notifications",
    "ringtones",
    "podcasts",
    "audiobooks",
    "recordings",
    "android",
    "lost.dir",
  )

  override fun evaluate(entry: FileEntry, storageRootPath: String): RuleMatch? {
    if (!entry.isDirectory) return null
    val segments = relativeSegments(entry.path, storageRootPath) ?: return null
    if (segments.isEmpty()) return null
    if (segments.size == 1 && segments[0].lowercase(Locale.ROOT) in PROTECTED_ROOT_DIRS) {
      return null
    }
    if (segments[0].equals("Android", ignoreCase = true)) {
      return null
    }
    if (segments.size >= 2 &&
      segments[0].equals("Documents", ignoreCase = true) &&
      segments[1].equals("LimpaDroid", ignoreCase = true)
    ) {
      return null
    }

    val dirPath = Paths.get(entry.path)
    val isEmpty = when (entry.isEmptyDirectory) {
      false -> false
      true -> !Files.exists(dirPath.resolve(".nomedia"), LinkOption.NOFOLLOW_LINKS)
      null -> isDirectoryActuallyEmpty(dirPath)
    }
    if (!isEmpty) return null

    return RuleMatch(
      ruleId = ruleId,
      category = category,
      confidence = confidence,
      reason = "Diretório sem arquivos ou subpastas",
    )
  }

  private fun isDirectoryActuallyEmpty(dirPath: java.nio.file.Path): Boolean {
    if (!Files.exists(dirPath, LinkOption.NOFOLLOW_LINKS) ||
      !Files.isDirectory(dirPath, LinkOption.NOFOLLOW_LINKS) ||
      Files.isSymbolicLink(dirPath)
    ) {
      return false
    }
    if (Files.exists(dirPath.resolve(".nomedia"), LinkOption.NOFOLLOW_LINKS)) {
      return false
    }
    return try {
      Files.newDirectoryStream(dirPath).use { stream ->
        !stream.iterator().hasNext()
      }
    } catch (_: DirectoryIteratorException) {
      false
    } catch (_: IOException) {
      false
    } catch (_: SecurityException) {
      false
    }
  }
}

object ZeroByteRule : DocumentedScanRule {
  override val ruleId: String = SuperfluousRuleId.ZERO_BYTE.ruleId
  override val category: Category = Category.SUPERFLUOUS
  override val confidence: Confidence = Confidence.MEDIUM
  override val description: String = "Arquivos de 0 byte, exceto .nomedia e nomes iniciados por ponto."
  override val positiveExamples: List<String> = listOf(
    "Download/vazio.txt",
    "Documents/relatorio_corrompido.pdf",
  )
  override val negativeExamples: List<String> = listOf(
    "Pictures/.nomedia",
    "Documents/.hidden_lock",
    "Download/com_conteudo.txt",
    "Download/pasta_vazia",
  )

  override fun evaluate(entry: FileEntry, storageRootPath: String): RuleMatch? {
    if (entry.isDirectory || entry.sizeBytes != 0L) return null
    val segments = relativeSegments(entry.path, storageRootPath) ?: return null
    val fileName = segments.lastOrNull() ?: return null
    if (fileName.equals(".nomedia", ignoreCase = true)) return null
    if (fileName.startsWith(".")) return null
    return RuleMatch(
      ruleId = ruleId,
      category = category,
      confidence = confidence,
      reason = "Arquivo regular com tamanho igual a 0 B",
    )
  }
}

object OldApkRule : DocumentedScanRule {
  override val ruleId: String = SuperfluousRuleId.OLD_APK.ruleId
  override val category: Category = Category.SUPERFLUOUS
  override val confidence: Confidence = Confidence.LOW
  override val description: String = "Pacotes instaladores (.apk, .xapk, .apks) em Download."
  override val positiveExamples: List<String> = listOf(
    "Download/aplicativo.apk",
    "Download/instaladores/jogo.xapk",
    "Download/pacote.apks",
  )
  override val negativeExamples: List<String> = listOf(
    "Documents/backup.apk",
    "Download/manual.pdf",
    "Download/instalador.apk/",
  )

  override fun evaluate(entry: FileEntry, storageRootPath: String): RuleMatch? {
    if (entry.isDirectory) return null
    val segments = relativeSegments(entry.path, storageRootPath) ?: return null
    if (segments.size < 2 || !segments[0].equals("Download", ignoreCase = true)) return null
    val extension = segments.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension !in APK_EXTENSIONS) return null
    return RuleMatch(
      ruleId = ruleId,
      category = category,
      confidence = confidence,
      reason = "Pacote de instalação (.$extension) mantido em Download",
    )
  }

  private val APK_EXTENSIONS = setOf("apk", "xapk", "apks")
}

object StaleMediaStoreDescriptor {
  val ruleId: String = SuperfluousRuleId.STALE_MEDIASTORE.ruleId
  val category: Category = Category.SUPERFLUOUS
  val confidence: Confidence = Confidence.HIGH
  const val description: String =
    "Registros do MediaStore cujo arquivo não existe mais; corrigido sem passar pela lixeira."
  val positiveExamples: List<String> = listOf(
    "Pictures/apagada_externamente.jpg (ausente no disco)",
  )
  val negativeExamples: List<String> = listOf(
    "Pictures/existente.jpg (arquivo presente no disco)",
    "Android/data/com.other/cache/a.jpg (escopo protegido)",
  )
}

object SuperfluousRuleRegistry {
  fun buildFilesystemRules(
    enabledRuleIds: Set<String> = SuperfluousRuleId.DEFAULT_ENABLED_IDS,
    clock: AppClock = AppClock.System,
  ): List<DocumentedScanRule> {
    val all = listOf(
      ThumbsRule,
      PartialDlRule(clock),
      LostDirRule,
      EmptyDirRule,
      ZeroByteRule,
      OldApkRule,
    )
    return all.filter { it.ruleId in enabledRuleIds }
  }
}

internal fun relativeSegments(entryPath: String, storageRootPath: String): List<String>? {
  val root = Paths.get(storageRootPath).normalize()
  val target = Paths.get(entryPath).normalize()
  if (!target.startsWith(root) || target == root) return null
  return root.relativize(target)
    .map { it.toString() }
    .filter { it.isNotEmpty() }
}
