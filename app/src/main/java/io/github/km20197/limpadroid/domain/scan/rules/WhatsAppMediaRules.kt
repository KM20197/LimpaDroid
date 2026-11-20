package io.github.km20197.limpadroid.domain.scan.rules

import java.nio.file.Path
import java.util.Locale

enum class WhatsAppMediaType(
  val ruleId: WhatsAppRuleId,
) {
  IMAGES(WhatsAppRuleId.WHATSAPP_IMAGES),
  VIDEO(WhatsAppRuleId.WHATSAPP_VIDEO),
  AUDIO(WhatsAppRuleId.WHATSAPP_AUDIO),
  VOICE_NOTES(WhatsAppRuleId.WHATSAPP_VOICE_NOTES),
  ANIMATED_GIFS(WhatsAppRuleId.WHATSAPP_ANIMATED_GIFS),
  ;

  companion object {
    val DEFAULT_ENABLED_TYPES: Set<WhatsAppMediaType> = setOf(
      IMAGES,
      VIDEO,
      AUDIO,
      VOICE_NOTES,
    )

    val DEFAULT_ENABLED_TYPE_NAMES: Set<String> =
      DEFAULT_ENABLED_TYPES.map { it.name }.toSet()

    fun parseEnabledNames(rawNames: Set<String>): Set<WhatsAppMediaType> =
      rawNames.mapNotNull { raw ->
        runCatching { valueOf(raw) }.getOrNull()
      }.toSet()
  }
}

enum class WhatsAppRuleId(val ruleId: String) {
  WHATSAPP_IMAGES("WHATSAPP_IMAGES"),
  WHATSAPP_VIDEO("WHATSAPP_VIDEO"),
  WHATSAPP_AUDIO("WHATSAPP_AUDIO"),
  WHATSAPP_VOICE_NOTES("WHATSAPP_VOICE_NOTES"),
  WHATSAPP_ANIMATED_GIFS("WHATSAPP_ANIMATED_GIFS"),
}

data class WhatsAppScanSettings(
  val minAgeDays: Int = WhatsAppMediaRules.DEFAULT_AGE_DAYS,
  val enabledMediaTypes: Set<WhatsAppMediaType> = WhatsAppMediaType.DEFAULT_ENABLED_TYPES,
  val autoPreselect: Boolean = false,
) {
  val sanitizedMinAgeDays: Int
    get() = WhatsAppMediaRules.clampAgeDays(minAgeDays)
}

data class WhatsAppRootLocation(
  val variantLabel: String,
  val isLegacy: Boolean,
  val relativeSegments: List<String>,
) {
  fun resolve(storageRoot: Path): Path =
    relativeSegments.fold(storageRoot) { acc, part -> acc.resolve(part) }

  val displayOrigin: String
    get() = if (isLegacy) "$variantLabel (legado)" else variantLabel
}

data class WhatsAppMediaMatch(
  val mediaType: WhatsAppMediaType,
  val ruleId: WhatsAppRuleId,
  val isBusiness: Boolean,
)

object WhatsAppMediaRules {
  const val MIN_AGE_DAYS = 7
  const val MAX_AGE_DAYS = 730
  const val DEFAULT_AGE_DAYS = 90
  const val MILLIS_PER_DAY = 86_400_000L

  val CORE_MEDIA_SUBFOLDER_REGEX =
    Regex("^WhatsApp( Business)? (Images|Video|Audio|Voice Notes)$")

  val ANIMATED_GIFS_SUBFOLDER_REGEX =
    Regex("^WhatsApp( Business)? Animated Gifs$")

  val FORBIDDEN_FOLDER_NAMES: Set<String> = setOf(
    ".statuses",
    "backups",
    "databases",
    "whatsapp documents",
    "whatsapp stickers",
    "whatsapp profile photos",
    "whatsapp bug reports",
    ".shared",
    ".links",
    ".trash",
  )

  val KNOWN_MEDIA_ROOTS: List<WhatsAppRootLocation> = listOf(
    WhatsAppRootLocation(
      variantLabel = "WhatsApp",
      isLegacy = false,
      relativeSegments = listOf("Android", "media", "com.whatsapp", "WhatsApp", "Media"),
    ),
    WhatsAppRootLocation(
      variantLabel = "WhatsApp Business",
      isLegacy = false,
      relativeSegments = listOf("Android", "media", "com.whatsapp.w4b", "WhatsApp Business", "Media"),
    ),
    WhatsAppRootLocation(
      variantLabel = "WhatsApp",
      isLegacy = true,
      relativeSegments = listOf("WhatsApp", "Media"),
    ),
    WhatsAppRootLocation(
      variantLabel = "WhatsApp Business",
      isLegacy = true,
      relativeSegments = listOf("WhatsApp Business", "Media"),
    ),
  )

  fun clampAgeDays(days: Int): Int = days.coerceIn(MIN_AGE_DAYS, MAX_AGE_DAYS)

  fun isForbiddenFolderName(folderName: String): Boolean {
    if (folderName.startsWith(".")) return true
    return folderName.lowercase(Locale.ROOT) in FORBIDDEN_FOLDER_NAMES
  }

  fun classifyMediaSubfolder(
    folderName: String,
    enabledTypes: Set<WhatsAppMediaType>,
  ): WhatsAppMediaMatch? {
    if (isForbiddenFolderName(folderName)) return null

    val coreMatch = CORE_MEDIA_SUBFOLDER_REGEX.matchEntire(folderName)
    if (coreMatch != null) {
      val isBusiness = coreMatch.groupValues[1].isNotEmpty()
      val mediaType = when (coreMatch.groupValues[2]) {
        "Images" -> WhatsAppMediaType.IMAGES
        "Video" -> WhatsAppMediaType.VIDEO
        "Audio" -> WhatsAppMediaType.AUDIO
        "Voice Notes" -> WhatsAppMediaType.VOICE_NOTES
        else -> return null
      }
      if (mediaType !in enabledTypes) return null
      return WhatsAppMediaMatch(
        mediaType = mediaType,
        ruleId = mediaType.ruleId,
        isBusiness = isBusiness,
      )
    }

    val gifMatch = ANIMATED_GIFS_SUBFOLDER_REGEX.matchEntire(folderName)
    if (gifMatch != null && WhatsAppMediaType.ANIMATED_GIFS in enabledTypes) {
      return WhatsAppMediaMatch(
        mediaType = WhatsAppMediaType.ANIMATED_GIFS,
        ruleId = WhatsAppRuleId.WHATSAPP_ANIMATED_GIFS,
        isBusiness = gifMatch.groupValues[1].isNotEmpty(),
      )
    }

    return null
  }

  fun matchesMinAge(
    lastModifiedMillis: Long,
    nowMillis: Long,
    minAgeDays: Int,
  ): Boolean {
    val safeDays = clampAgeDays(minAgeDays).toLong()
    val ageMillis = nowMillis - lastModifiedMillis
    return ageMillis >= safeDays * MILLIS_PER_DAY
  }

  fun formatReason(
    root: WhatsAppRootLocation,
    mediaFolder: Path,
    filePath: Path,
    lastModifiedMillis: Long,
    nowMillis: Long,
  ): String {
    val relParts = runCatching {
      val rel = mediaFolder.relativize(filePath)
      (0 until rel.nameCount - 1).map { idx -> rel.getName(idx).toString() }
    }.getOrDefault(emptyList())

    val scopeDetail = when {
      relParts.any { it.equals("Sent", ignoreCase = true) } -> "Enviado (Sent)"
      relParts.any { it.equals("Private", ignoreCase = true) } -> "Privado (Private)"
      relParts.isNotEmpty() -> "Subpasta ${relParts.joinToString("/")}"
      else -> "Recebido"
    }

    val ageDays = ((nowMillis - lastModifiedMillis) / MILLIS_PER_DAY).coerceAtLeast(0L)
    return "${root.displayOrigin} · $scopeDetail · $ageDays dias"
  }
}
