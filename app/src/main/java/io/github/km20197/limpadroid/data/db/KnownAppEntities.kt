package io.github.km20197.limpadroid.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "known_apps",
  indices = [
    Index(value = ["removedAt"]),
  ],
)
data class KnownAppEntity(
  @PrimaryKey val packageName: String,
  val label: String,
  val firstSeen: Long,
  val lastSeen: Long,
  val removedAt: Long? = null,
)
