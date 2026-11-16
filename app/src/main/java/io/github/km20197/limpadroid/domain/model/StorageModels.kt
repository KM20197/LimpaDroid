package io.github.km20197.limpadroid.domain.model

enum class Category {
  SUPERFLUOUS,
  ORPHAN,
  DUPLICATE,
  WHATSAPP,
}

enum class Confidence {
  HIGH,
  MEDIUM,
  LOW,
}

data class Candidate(
  val path: String,
  val sizeBytes: Long,
  val lastModified: Long,
  val category: Category,
  val ruleId: String,
  val confidence: Confidence,
  val groupId: String? = null,
  val preselected: Boolean = confidence == Confidence.HIGH,
)

data class DiagnosticEntry(
  val name: String,
  val path: String,
  val isDirectory: Boolean,
  val sizeBytes: Long,
  val children: List<DiagnosticEntry> = emptyList(),
  val error: String? = null,
)

data class WhatsAppRootStatus(
  val label: String,
  val path: String,
  val exists: Boolean,
  val readable: Boolean,
  val subFolders: List<String> = emptyList(),
)

data class DiagnosticReport(
  val storageRoot: String,
  val hasAllFilesAccess: Boolean,
  val androidMediaAccessible: Boolean,
  val rootEntries: List<DiagnosticEntry>,
  val androidMediaEntries: List<DiagnosticEntry>,
  val whatsappKnownRoots: List<WhatsAppRootStatus>,
  val generatedAtIso: String,
  val appVersion: String,
  val sdkInt: Int,
)
