package com.sdsmobile.voiceime.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sdsmobile.voiceime.model.AppSettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class AppSettingsRepository(context: Context) {
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { context.preferencesDataStoreFile("voice_ime.preferences_pb") },
    )

    val settings: Flow<AppSettings> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map(::toSettings)

    suspend fun save(settings: AppSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.SPEECH_APP_ID] = settings.speechAppId
            prefs[Keys.SPEECH_TOKEN] = settings.speechToken
            prefs[Keys.SPEECH_RESOURCE_ID] = settings.speechResourceId
            prefs[Keys.ARK_API_KEY] = settings.arkApiKey
            prefs[Keys.ARK_MODEL] = settings.arkModel
        }
    }

    private fun toSettings(prefs: Preferences): AppSettings {
        return AppSettings(
            speechAppId = prefs[Keys.SPEECH_APP_ID] ?: AppSettings.DEFAULT_SPEECH_APP_ID,
            speechToken = prefs[Keys.SPEECH_TOKEN].orEmpty(),
            speechResourceId = prefs[Keys.SPEECH_RESOURCE_ID].orEmpty(),
            arkApiKey = prefs[Keys.ARK_API_KEY].orEmpty(),
            arkModel = prefs[Keys.ARK_MODEL].orEmpty(),
        )
    }

    private object Keys {
        val SPEECH_APP_ID = stringPreferencesKey("speech_app_id")
        val SPEECH_TOKEN = stringPreferencesKey("speech_token")
        val SPEECH_RESOURCE_ID = stringPreferencesKey("speech_resource_id")
        val ARK_API_KEY = stringPreferencesKey("ark_api_key")
        val ARK_MODEL = stringPreferencesKey("ark_model")
    }
}
