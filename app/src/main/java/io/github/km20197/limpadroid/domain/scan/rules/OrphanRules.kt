package io.github.km20197.limpadroid.domain.scan.rules

import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.pkg.AppIdentifierNormalizer
import io.github.km20197.limpadroid.domain.pkg.InstalledAppInfo
import io.github.km20197.limpadroid.domain.pkg.KnownAppRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class OrphanRuleId(
  val ruleId: String,
  val confidence: Confidence,
) {
  ORPHAN_MEDIA_PKG("ORPHAN_MEDIA_PKG", Confidence.HIGH),
  ORPHAN_ROOT_KNOWN("ORPHAN_ROOT_KNOWN", Confidence.MEDIUM),
  ORPHAN_ROOT_PATTERN("ORPHAN_ROOT_PATTERN", Confidence.LOW),
}

data class OrphanMatch(
  val ruleId: OrphanRuleId,
  val confidence: Confidence,
  val matchedApp: KnownAppRecord? = null,
) {
  val preselected: Boolean
    get() = confidence == Confidence.HIGH
}

class OrphanDirectoryClassifier(
  presentApps: List<InstalledAppInfo>,
  knownApps: List<KnownAppRecord>,
  private val applicationId: String,
) {
  private val presentPackagesLower: Set<String> = buildSet {
    presentApps.forEach { add(it.packageName.lowercase(Locale.ROOT)) }
    add(applicationId.lowercase(Locale.ROOT))
  }

  private val presentNormalizedIdentifiers: Set<String> = buildSet {
    presentApps.forEach { app ->
      AppIdentifierNormalizer.normalize(app.packageName)
        .takeIf { it.isNotEmpty() }
        ?.let(::add)
      AppIdentifierNormalizer.normalize(app.label)
        .takeIf { it.isNotEmpty() }
        ?.let(::add)
    }
    AppIdentifierNormalizer.normalize(applicationId)
      .takeIf { it.isNotEmpty() }
      ?.let(::add)
  }

  private val removedByPackageLower: Map<String, KnownAppRecord> = knownApps
    .asSequence()
    .filter { it.removedAt != null && it.packageName.lowercase(Locale.ROOT) !in presentPackagesLower }
    .associateBy { it.packageName.lowercase(Locale.ROOT) }

  private val removedByNormalizedIdentifier: Map<String, KnownAppRecord> = buildMap {
    val removedSorted = knownApps
      .asSequence()
      .filter { it.removedAt != null && it.packageName.lowercase(Locale.ROOT) !in presentPackagesLower }
      .sortedByDescending { it.removedAt ?: 0L }
    for (record in removedSorted) {
      val normPkg = AppIdentifierNormalizer.normalize(record.packageName)
      if (normPkg.isNotEmpty() && normPkg !in presentNormalizedIdentifiers) {
        putIfAbsent(normPkg, record)
      }
      val normLabel = AppIdentifierNormalizer.normalize(record.label)
      if (normLabel.isNotEmpty() && normLabel !in presentNormalizedIdentifiers) {
        putIfAbsent(normLabel, record)
      }
    }
  }

  fun evaluateDirectory(relativeSegments: List<String>): OrphanMatch? {
    if (relativeSegments.isEmpty()) return null
    val folderName = relativeSegments.last()
    if (folderName.isBlank() || folderName.startsWith(".")) return null

    if (isWhatsAppProtectedFolder(relativeSegments, folderName)) return null
    if (isLimpaDroidOwnFolder(relativeSegments)) return null

    // Regra 7.3.a: Android/media/<packageName>
    // Classifica como HIGH somente quando há aplicativo removido confirmado no histórico; caso contrário, MEDIUM desmarcado.
    if (relativeSegments.size == 3 &&
      relativeSegments[0].equals("Android", ignoreCase = true) &&
      relativeSegments[1].equals("media", ignoreCase = true)
    ) {
      if (!AppIdentifierNormalizer.isPackageFormat(folderName)) return null
      val lower = folderName.lowercase(Locale.ROOT)
      if (lower in presentPackagesLower) return null
      val matchedRemoved = removedByPackageLower[lower]
      return OrphanMatch(
        ruleId = OrphanRuleId.ORPHAN_MEDIA_PKG,
        confidence = if (matchedRemoved != null) Confidence.HIGH else Confidence.MEDIUM,
        matchedApp = matchedRemoved,
      )
    }

    // M6: restringe ORPHAN_ROOT_KNOWN e ORPHAN_ROOT_PATTERN apenas a filhos diretos da raiz,
    // preservando pastas de conteúdo pessoal do usuário (Documents, Pictures, Movies, Music, Download).
    val isEligibleRootChild = relativeSegments.size == 1 &&
      folderName.lowercase(Locale.ROOT) !in PROTECTED_ROOT_DIRS

    if (!isEligibleRootChild) {
      return null
    }

    val normalizedFolder = AppIdentifierNormalizer.normalize(folderName)
    if (normalizedFolder.isEmpty()) return null

    // Salvaguarda: nunca sugerir pastas cujo nome/rótulo normalizado pertença a um app presente ou arquivado
    if (folderName.lowercase(Locale.ROOT) in presentPackagesLower ||
      normalizedFolder in presentNormalizedIdentifiers
    ) {
      return null
    }

    // Regra 7.3.b: Pasta na raiz conhecida em known_apps removidos
    val matchedKnown = removedByNormalizedIdentifier[normalizedFolder]
    if (matchedKnown != null) {
      return OrphanMatch(
        ruleId = OrphanRuleId.ORPHAN_ROOT_KNOWN,
        confidence = Confidence.MEDIUM,
        matchedApp = matchedKnown,
      )
    }

    // Regra 7.3.c: Pasta na raiz com formato de pacote e ausente dos pacotes presentes
    if (AppIdentifierNormalizer.isPackageFormat(folderName)) {
      return OrphanMatch(
        ruleId = OrphanRuleId.ORPHAN_ROOT_PATTERN,
        confidence = Confidence.LOW,
        matchedApp = null,
      )
    }

    return null
  }

  private fun isLimpaDroidOwnFolder(segments: List<String>): Boolean =
    segments.size >= 2 &&
      segments[0].equals("Documents", ignoreCase = true) &&
      segments[1].equals("LimpaDroid", ignoreCase = true)

  private fun isWhatsAppProtectedFolder(segments: List<String>, folderName: String): Boolean {
    val lower = folderName.lowercase(Locale.ROOT)
    val norm = AppIdentifierNormalizer.normalize(folderName)
    if (lower in WHATSAPP_PACKAGE_OR_FOLDER_NAMES || norm in WHATSAPP_NORMALIZED_NAMES) {
      return true
    }
    return segments.any { segment ->
      val segLower = segment.lowercase(Locale.ROOT)
      val segNorm = AppIdentifierNormalizer.normalize(segment)
      segLower in WHATSAPP_PACKAGE_OR_FOLDER_NAMES || segNorm in WHATSAPP_NORMALIZED_NAMES
    }
  }

  companion object {

    val PROTECTED_ROOT_DIRS: Set<String> = setOf(
      "dcim",
      "download",
      "downloads",
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

    private val WHATSAPP_PACKAGE_OR_FOLDER_NAMES: Set<String> = setOf(
      "com.whatsapp",
      "com.whatsapp.w4b",
      "whatsapp",
      "whatsapp business",
    )

    private val WHATSAPP_NORMALIZED_NAMES: Set<String> = setOf(
      "com.whatsapp",
      "com.whatsapp.w4b",
      "whatsapp",
      "whatsappbusiness",
    )

    private val DATE_FORMATTER: DateTimeFormatter =
      DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("pt-BR"))
        .withZone(ZoneId.systemDefault())

    fun formatReason(match: OrphanMatch, folderName: String): String {
      val known = match.matchedApp
      if (known != null && known.removedAt != null) {
        val dateText = DATE_FORMATTER.format(Instant.ofEpochMilli(known.removedAt))
        return "Aplicativo removido: ${known.label} (${known.packageName}) · Removido em $dateText"
      }
      return when (match.ruleId) {
        OrphanRuleId.ORPHAN_MEDIA_PKG ->
          "Subpasta de Android/media para pacote ausente: $folderName"
        OrphanRuleId.ORPHAN_ROOT_KNOWN ->
          "Pasta correspondente a aplicativo removido: $folderName"
        OrphanRuleId.ORPHAN_ROOT_PATTERN ->
          "Pasta na raiz com formato de pacote ausente: $folderName"
      }
    }
  }
}
