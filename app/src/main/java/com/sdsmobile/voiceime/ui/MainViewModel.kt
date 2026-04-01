package com.sdsmobile.voiceime.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdsmobile.voiceime.data.AppSettingsRepository
import com.sdsmobile.voiceime.model.AppSettings
import com.sdsmobile.voiceime.model.AsrMode
import com.sdsmobile.voiceime.service.AccessibilityBridge
import com.sdsmobile.voiceime.service.BubbleOverlayService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val draft: AppSettings = AppSettings(),
    val bubbleRunning: Boolean = false,
    val accessibilityConnected: Boolean = false,
)

class MainViewModel(
    private val repository: AppSettingsRepository,
) : ViewModel() {
    private val draft = MutableStateFlow(AppSettings())

    val uiState: StateFlow<MainUiState> = combine(
        draft,
        BubbleOverlayService.running,
        AccessibilityBridge.connected,
    ) { settings, running, accessibility ->
        MainUiState(
            draft = settings,
            bubbleRunning = running,
            accessibilityConnected = accessibility,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        viewModelScope.launch {
            repository.settings.collectLatest {
                draft.value = it
            }
        }
    }

    suspend fun persistDraft() {
        repository.save(draft.value)
    }

    fun updateMode(mode: AsrMode) {
        draft.update {
            when (mode) {
                AsrMode.BIG_MODEL -> it.copy(
                    asrMode = mode,
                    speechUri = if (it.speechUri == "/api/v2/asr") "/api/v3/sauc/bigmodel" else it.speechUri,
                )
                AsrMode.STANDARD -> it.copy(
                    asrMode = mode,
                    speechUri = if (it.speechUri == "/api/v3/sauc/bigmodel") "/api/v2/asr" else it.speechUri,
                )
            }
        }
    }

    fun updateDraft(transform: (AppSettings) -> AppSettings) {
        draft.update(transform)
    }
}
