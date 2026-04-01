package com.sdsmobile.voiceime.data

import android.content.Context
import com.sdsmobile.voiceime.speech.ArkTextCorrector

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: AppSettingsRepository by lazy {
        AppSettingsRepository(appContext)
    }

    val arkTextCorrector: ArkTextCorrector by lazy {
        ArkTextCorrector()
    }
}
