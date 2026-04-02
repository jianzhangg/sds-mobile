package com.sdsmobile.voiceime.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
            prefs[Keys.SPEECH_TOKEN] = settings.speechToken
            prefs[Keys.ARK_API_KEY] = settings.arkApiKey
            prefs[Keys.ARK_MODEL] = settings.arkModel
            prefs[Keys.TEXT_OPTIMIZATION_ENABLED] = settings.textOptimizationEnabled
            prefs[Keys.PERSONALIZATION_ENABLED] = settings.personalizationEnabled
            prefs[Keys.PERSONALIZATION_PROMPT] = settings.personalizationPrompt
            prefs[Keys.AUTO_STRUCTURE_ENABLED] = settings.autoStructureEnabled
            prefs[Keys.FILLER_WORD_FILTER_ENABLED] = settings.fillerWordFilterEnabled
            prefs[Keys.TRIM_TRAILING_PERIOD_ENABLED] = settings.trimTrailingPeriodEnabled
            prefs[Keys.SCREEN_CONTEXT_ENABLED] = settings.screenContextEnabled
        }
    }

    private fun toSettings(prefs: Preferences): AppSettings {
        return AppSettings(
            speechToken = prefs[Keys.SPEECH_TOKEN].orEmpty(),
            arkApiKey = prefs[Keys.ARK_API_KEY].orEmpty(),
            arkModel = prefs[Keys.ARK_MODEL] ?: AppSettings.DEFAULT_ARK_MODEL_ID,
            textOptimizationEnabled = prefs[Keys.TEXT_OPTIMIZATION_ENABLED] ?: false,
            personalizationEnabled = prefs[Keys.PERSONALIZATION_ENABLED] ?: false,
            personalizationPrompt = prefs[Keys.PERSONALIZATION_PROMPT].orEmpty(),
            autoStructureEnabled = prefs[Keys.AUTO_STRUCTURE_ENABLED] ?: true,
            fillerWordFilterEnabled = prefs[Keys.FILLER_WORD_FILTER_ENABLED] ?: true,
            trimTrailingPeriodEnabled = prefs[Keys.TRIM_TRAILING_PERIOD_ENABLED] ?: false,
            screenContextEnabled = prefs[Keys.SCREEN_CONTEXT_ENABLED] ?: false,
        )
    }

    private object Keys {
        val SPEECH_TOKEN = stringPreferencesKey("speech_token")
        val ARK_API_KEY = stringPreferencesKey("ark_api_key")
        val ARK_MODEL = stringPreferencesKey("ark_model")
        val TEXT_OPTIMIZATION_ENABLED = booleanPreferencesKey("text_optimization_enabled")
        val PERSONALIZATION_ENABLED = booleanPreferencesKey("personalization_enabled")
        val PERSONALIZATION_PROMPT = stringPreferencesKey("personalization_prompt")
        val AUTO_STRUCTURE_ENABLED = booleanPreferencesKey("auto_structure_enabled")
        val FILLER_WORD_FILTER_ENABLED = booleanPreferencesKey("filler_word_filter_enabled")
        val TRIM_TRAILING_PERIOD_ENABLED = booleanPreferencesKey("trim_trailing_period_enabled")
        val SCREEN_CONTEXT_ENABLED = booleanPreferencesKey("screen_context_enabled")
    }
}
