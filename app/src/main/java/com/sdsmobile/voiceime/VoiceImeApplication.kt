package com.sdsmobile.voiceime

import android.app.Application
import com.sdsmobile.voiceime.data.AppContainer

class VoiceImeApplication : Application() {
    val appContainer: AppContainer by lazy { AppContainer(this) }
}
