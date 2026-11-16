package io.github.km20197.limpadroid.domain.scan

import io.github.km20197.limpadroid.domain.model.Candidate
import io.github.km20197.limpadroid.domain.model.Category
import io.github.km20197.limpadroid.domain.model.Confidence
import io.github.km20197.limpadroid.domain.model.FileEntry

data class RuleMatch(
  val ruleId: String,
  val category: Category,
  val confidence: Confidence,
  val groupId: String? = null,
  val preselectedOverride: Boolean? = null,
  val reason: String? = null,
)

interface ScanRule {
  val ruleId: String
  val category: Category
  val confidence: Confidence
  fun evaluate(entry: FileEntry, storageRootPath: String): RuleMatch?
}

object ClassificationPolicy {
  fun defaultPreselected(confidence: Confidence): Boolean =
    when (confidence) {
      Confidence.HIGH -> true
      Confidence.MEDIUM -> false
      Confidence.LOW -> false
    }

  fun toCandidate(
    entry: FileEntry,
    match: RuleMatch,
  ): Candidate {
    val preselected = when (match.confidence) {
      Confidence.LOW -> false
      Confidence.MEDIUM -> false
      Confidence.HIGH -> match.preselectedOverride ?: true
    }
    return Candidate(
      path = entry.path,
      sizeBytes = entry.sizeBytes,
      lastModified = entry.lastModified,
      category = match.category,
      ruleId = match.ruleId,
      confidence = match.confidence,
      groupId = match.groupId,
      preselected = preselected,
    )
  }
}

class CandidateClassifier(
  private val rules: List<ScanRule> = emptyList(),
) {
  fun classify(
    entry: FileEntry,
    storageRootPath: String,
  ): Pair<Candidate, String?>? {
    for (rule in rules) {
      val match = rule.evaluate(entry, storageRootPath) ?: continue
      return ClassificationPolicy.toCandidate(entry, match) to match.reason
    }
    return null
  }
}
