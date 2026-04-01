package com.sdsmobile.voiceime.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.sdsmobile.voiceime.model.AppSettings
import com.sdsmobile.voiceime.model.AsrMode
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
            prefs[Keys.ASR_MODE] = settings.asrMode.name
            prefs[Keys.SPEECH_APP_ID] = settings.speechAppId
            prefs[Keys.SPEECH_TOKEN] = settings.speechToken
            prefs[Keys.SPEECH_CLUSTER] = settings.speechCluster
            prefs[Keys.SPEECH_RESOURCE_ID] = settings.speechResourceId
            prefs[Keys.SPEECH_ADDRESS] = settings.speechAddress
            prefs[Keys.SPEECH_URI] = settings.speechUri
            prefs[Keys.SPEECH_REQ_PARAMS] = settings.speechRequestParamsJson
            prefs[Keys.ARK_API_KEY] = settings.arkApiKey
            prefs[Keys.ARK_BASE_URL] = settings.arkBaseUrl
            prefs[Keys.ARK_MODEL] = settings.arkModel
            prefs[Keys.CORRECTION_PROMPT] = settings.correctionPrompt
        }
    }

    private fun toSettings(prefs: Preferences): AppSettings {
        val asrMode = prefs[Keys.ASR_MODE]
            ?.let { runCatching { AsrMode.valueOf(it) }.getOrNull() }
            ?: AsrMode.BIG_MODEL

        return AppSettings(
            asrMode = asrMode,
            speechAppId = prefs[Keys.SPEECH_APP_ID].orEmpty(),
            speechToken = prefs[Keys.SPEECH_TOKEN].orEmpty(),
            speechCluster = prefs[Keys.SPEECH_CLUSTER].orEmpty(),
            speechResourceId = prefs[Keys.SPEECH_RESOURCE_ID].orEmpty(),
            speechAddress = prefs[Keys.SPEECH_ADDRESS] ?: "wss://openspeech.bytedance.com",
            speechUri = prefs[Keys.SPEECH_URI] ?: "/api/v3/sauc/bigmodel",
            speechRequestParamsJson = prefs[Keys.SPEECH_REQ_PARAMS]
                ?: AppSettings.DEFAULT_ASR_REQUEST_PARAMS_JSON,
            arkApiKey = prefs[Keys.ARK_API_KEY].orEmpty(),
            arkBaseUrl = prefs[Keys.ARK_BASE_URL] ?: AppSettings.DEFAULT_ARK_BASE_URL,
            arkModel = prefs[Keys.ARK_MODEL].orEmpty(),
            correctionPrompt = prefs[Keys.CORRECTION_PROMPT]
                ?: AppSettings.DEFAULT_CORRECTION_PROMPT,
        )
    }

    private object Keys {
        val ASR_MODE = stringPreferencesKey("asr_mode")
        val SPEECH_APP_ID = stringPreferencesKey("speech_app_id")
        val SPEECH_TOKEN = stringPreferencesKey("speech_token")
        val SPEECH_CLUSTER = stringPreferencesKey("speech_cluster")
        val SPEECH_RESOURCE_ID = stringPreferencesKey("speech_resource_id")
        val SPEECH_ADDRESS = stringPreferencesKey("speech_address")
        val SPEECH_URI = stringPreferencesKey("speech_uri")
        val SPEECH_REQ_PARAMS = stringPreferencesKey("speech_req_params")
        val ARK_API_KEY = stringPreferencesKey("ark_api_key")
        val ARK_BASE_URL = stringPreferencesKey("ark_base_url")
        val ARK_MODEL = stringPreferencesKey("ark_model")
        val CORRECTION_PROMPT = stringPreferencesKey("correction_prompt")
    }
}
