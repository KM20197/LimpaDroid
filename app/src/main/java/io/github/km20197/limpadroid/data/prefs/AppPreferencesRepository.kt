package io.github.km20197.limpadroid.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "limpadroid_prefs")

interface AppPreferencesRepository {
  val notificationPromptHandled: Flow<Boolean>
  suspend fun setNotificationPromptHandled(handled: Boolean)
}

class DataStoreAppPreferencesRepository(
  private val context: Context,
) : AppPreferencesRepository {

  override val notificationPromptHandled: Flow<Boolean> =
    context.dataStore.data.map { prefs -> prefs[KEY_NOTIFICATION_PROMPT_HANDLED] ?: false }

  override suspend fun setNotificationPromptHandled(handled: Boolean) {
    context.dataStore.edit { prefs ->
      prefs[KEY_NOTIFICATION_PROMPT_HANDLED] = handled
    }
  }

  private companion object {
    val KEY_NOTIFICATION_PROMPT_HANDLED = booleanPreferencesKey("notification_prompt_handled")
  }
}
