package io.github.km20197.limpadroid.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.km20197.limpadroid.domain.scan.rules.DuplicateKeepPolicy
import io.github.km20197.limpadroid.domain.scan.rules.SuperfluousRuleId
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaRules
import io.github.km20197.limpadroid.domain.scan.rules.WhatsAppMediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "limpadroid_prefs")

interface AppPreferencesRepository {
  val notificationPromptHandled: Flow<Boolean>
  val enabledSuperfluousRuleIds: Flow<Set<String>>
  val userExcludedPaths: Flow<Set<String>>
  val duplicateMinSizeBytes: Flow<Long>
    get() = flowOf(DuplicateKeepPolicy.DEFAULT_MIN_SIZE_BYTES)
  val duplicatePriorityFolders: Flow<List<String>>
    get() = flowOf(DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS)
  val whatsappMinAgeDays: Flow<Int>
    get() = flowOf(WhatsAppMediaRules.DEFAULT_AGE_DAYS)
  val whatsappEnabledMediaTypes: Flow<Set<String>>
    get() = flowOf(WhatsAppMediaType.DEFAULT_ENABLED_TYPE_NAMES)
  val whatsappAutoPreselect: Flow<Boolean>
    get() = flowOf(false)

  suspend fun setNotificationPromptHandled(handled: Boolean)
  suspend fun setSuperfluousRuleEnabled(ruleId: String, enabled: Boolean)
  suspend fun addUserExcludedPath(path: String)
  suspend fun removeUserExcludedPath(path: String)
  suspend fun setDuplicateMinSizeBytes(bytes: Long) = Unit
  suspend fun setDuplicatePriorityFolders(folders: List<String>) = Unit
  suspend fun setWhatsAppMinAgeDays(days: Int) = Unit
  suspend fun setWhatsAppMediaTypeEnabled(typeName: String, enabled: Boolean) = Unit
  suspend fun setWhatsAppAutoPreselect(enabled: Boolean) = Unit
}

class DataStoreAppPreferencesRepository(
  private val context: Context,
) : AppPreferencesRepository {

  private val json = Json { ignoreUnknownKeys = true }

  override val notificationPromptHandled: Flow<Boolean> =
    context.dataStore.data.map { prefs -> prefs[KEY_NOTIFICATION_PROMPT_HANDLED] ?: false }

  override val enabledSuperfluousRuleIds: Flow<Set<String>> =
    context.dataStore.data.map { prefs ->
      prefs[KEY_ENABLED_SUPERFLUOUS_RULES] ?: SuperfluousRuleId.DEFAULT_ENABLED_IDS
    }

  override val userExcludedPaths: Flow<Set<String>> =
    context.dataStore.data.map { prefs ->
      prefs[KEY_USER_EXCLUDED_PATHS] ?: emptySet()
    }

  override val duplicateMinSizeBytes: Flow<Long> =
    context.dataStore.data.map { prefs ->
      prefs[KEY_DUPLICATE_MIN_SIZE_BYTES] ?: DuplicateKeepPolicy.DEFAULT_MIN_SIZE_BYTES
    }

  override val duplicatePriorityFolders: Flow<List<String>> =
    context.dataStore.data.map { prefs ->
      val raw = prefs[KEY_DUPLICATE_PRIORITY_FOLDERS_JSON] ?: return@map DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS
      runCatching { json.decodeFromString<List<String>>(raw) }
        .getOrDefault(DuplicateKeepPolicy.DEFAULT_PRIORITY_FOLDERS)
    }

  override val whatsappMinAgeDays: Flow<Int> =
    context.dataStore.data.map { prefs ->
      WhatsAppMediaRules.clampAgeDays(
        prefs[KEY_WHATSAPP_MIN_AGE_DAYS] ?: WhatsAppMediaRules.DEFAULT_AGE_DAYS,
      )
    }

  override val whatsappEnabledMediaTypes: Flow<Set<String>> =
    context.dataStore.data.map { prefs ->
      prefs[KEY_WHATSAPP_MEDIA_TYPES] ?: WhatsAppMediaType.DEFAULT_ENABLED_TYPE_NAMES
    }

  override val whatsappAutoPreselect: Flow<Boolean> =
    context.dataStore.data.map { prefs ->
      prefs[KEY_WHATSAPP_AUTO_PRESELECT] ?: false
    }

  override suspend fun setNotificationPromptHandled(handled: Boolean) {
    context.dataStore.edit { prefs ->
      prefs[KEY_NOTIFICATION_PROMPT_HANDLED] = handled
    }
  }

  override suspend fun setSuperfluousRuleEnabled(ruleId: String, enabled: Boolean) {
    context.dataStore.edit { prefs ->
      val current = (prefs[KEY_ENABLED_SUPERFLUOUS_RULES] ?: SuperfluousRuleId.DEFAULT_ENABLED_IDS)
        .toMutableSet()
      if (enabled) {
        current.add(ruleId)
      } else {
        current.remove(ruleId)
      }
      prefs[KEY_ENABLED_SUPERFLUOUS_RULES] = current
    }
  }

  override suspend fun addUserExcludedPath(path: String) {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return
    context.dataStore.edit { prefs ->
      val current = (prefs[KEY_USER_EXCLUDED_PATHS] ?: emptySet()).toMutableSet()
      current.add(trimmed)
      prefs[KEY_USER_EXCLUDED_PATHS] = current
    }
  }

  override suspend fun removeUserExcludedPath(path: String) {
    context.dataStore.edit { prefs ->
      val current = (prefs[KEY_USER_EXCLUDED_PATHS] ?: emptySet()).toMutableSet()
      current.remove(path)
      prefs[KEY_USER_EXCLUDED_PATHS] = current
    }
  }

  override suspend fun setDuplicateMinSizeBytes(bytes: Long) {
    val safeBytes = bytes.coerceAtLeast(1L)
    context.dataStore.edit { prefs ->
      prefs[KEY_DUPLICATE_MIN_SIZE_BYTES] = safeBytes
    }
  }

  override suspend fun setDuplicatePriorityFolders(folders: List<String>) {
    val normalized = folders
      .map { DuplicateKeepPolicy.normalizeFolderSegments(it).joinToString("/") }
      .filter { it.isNotEmpty() }
      .distinctBy { it.lowercase() }
    context.dataStore.edit { prefs ->
      prefs[KEY_DUPLICATE_PRIORITY_FOLDERS_JSON] = json.encodeToString(normalized)
    }
  }

  override suspend fun setWhatsAppMinAgeDays(days: Int) {
    val clamped = WhatsAppMediaRules.clampAgeDays(days)
    context.dataStore.edit { prefs ->
      prefs[KEY_WHATSAPP_MIN_AGE_DAYS] = clamped
    }
  }

  override suspend fun setWhatsAppMediaTypeEnabled(typeName: String, enabled: Boolean) {
    val validType = runCatching { WhatsAppMediaType.valueOf(typeName) }.getOrNull() ?: return
    context.dataStore.edit { prefs ->
      val current = (prefs[KEY_WHATSAPP_MEDIA_TYPES] ?: WhatsAppMediaType.DEFAULT_ENABLED_TYPE_NAMES)
        .toMutableSet()
      if (enabled) {
        current.add(validType.name)
      } else {
        current.remove(validType.name)
      }
      prefs[KEY_WHATSAPP_MEDIA_TYPES] = current
    }
  }

  override suspend fun setWhatsAppAutoPreselect(enabled: Boolean) {
    context.dataStore.edit { prefs ->
      prefs[KEY_WHATSAPP_AUTO_PRESELECT] = enabled
    }
  }

  private companion object {
    val KEY_NOTIFICATION_PROMPT_HANDLED = booleanPreferencesKey("notification_prompt_handled")
    val KEY_ENABLED_SUPERFLUOUS_RULES = stringSetPreferencesKey("enabled_superfluous_rules")
    val KEY_USER_EXCLUDED_PATHS = stringSetPreferencesKey("user_excluded_paths")
    val KEY_DUPLICATE_MIN_SIZE_BYTES = longPreferencesKey("duplicate_min_size_bytes")
    val KEY_DUPLICATE_PRIORITY_FOLDERS_JSON = stringPreferencesKey("duplicate_priority_folders_json")
    val KEY_WHATSAPP_MIN_AGE_DAYS = intPreferencesKey("whatsapp_min_age_days")
    val KEY_WHATSAPP_MEDIA_TYPES = stringSetPreferencesKey("whatsapp_media_types")
    val KEY_WHATSAPP_AUTO_PRESELECT = booleanPreferencesKey("whatsapp_auto_preselect")
  }
}
