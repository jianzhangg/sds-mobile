package com.sdsmobile.voiceime.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdsmobile.voiceime.data.AppSettingsRepository
import com.sdsmobile.voiceime.model.AppSettings
import com.sdsmobile.voiceime.speech.ArkTextCorrector
import com.sdsmobile.voiceime.speech.DoubaoSpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpeechTestUiState(
    val isRunning: Boolean = false,
    val status: String = "未开始",
    val partialText: String = "",
    val finalText: String = "",
    val error: String? = null,
)

data class LlmTestUiState(
    val inputText: String = AppSettings.DEFAULT_LLM_TEST_INPUT,
    val isRunning: Boolean = false,
    val status: String = "未开始",
    val outputText: String = "",
    val error: String? = null,
)

data class MainUiState(
    val draft: AppSettings = AppSettings(),
    val speechTest: SpeechTestUiState = SpeechTestUiState(),
    val llmTest: LlmTestUiState = LlmTestUiState(),
)

class MainViewModel(
    appContext: Context,
    private val repository: AppSettingsRepository,
    private val arkTextCorrector: ArkTextCorrector,
) : ViewModel() {
    private val draft = MutableStateFlow(AppSettings())
    private val speechTest = MutableStateFlow(SpeechTestUiState())
    private val llmTest = MutableStateFlow(LlmTestUiState())
    private val recognizer = DoubaoSpeechRecognizer(appContext.applicationContext)

    val uiState: StateFlow<MainUiState> = combine(
        draft,
        speechTest,
        llmTest,
    ) { settings, speech, llm ->
        MainUiState(
            draft = settings,
            speechTest = speech,
            llmTest = llm,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState(),
    )

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                draft.value = settings
            }
        }
    }

    suspend fun persistDraft() {
        repository.save(draft.value)
    }

    fun updateDraft(transform: (AppSettings) -> AppSettings) {
        draft.update(transform)
    }

    fun updateLlmTestInput(text: String) {
        llmTest.update {
            it.copy(
                inputText = text,
                error = null,
            )
        }
    }

    fun toggleSpeechTest(hasAudioPermission: Boolean) {
        if (speechTest.value.isRunning) {
            speechTest.update {
                it.copy(
                    status = "收尾中",
                    error = null,
                )
            }
            recognizer.finishTalking()
            return
        }

        val settings = draft.value
        when {
            !hasAudioPermission -> {
                speechTest.value = SpeechTestUiState(
                    status = "缺少权限",
                    error = "请先授权麦克风权限",
                )
            }

            !settings.isSpeechConfigured() -> {
                speechTest.value = SpeechTestUiState(
                    status = "配置不完整",
                    error = "请先补全豆包语音的 App ID、Access Token 和 Resource ID",
                )
            }

            else -> startSpeechTest(settings)
        }
    }

    fun runLlmTest() {
        val settings = draft.value
        val inputText = llmTest.value.inputText.trim()
        when {
            inputText.isBlank() -> {
                llmTest.update {
                    it.copy(
                        status = "缺少测试文本",
                        error = "请先输入一段需要纠正的测试文本",
                    )
                }
            }

            !settings.isCorrectionConfigured() -> {
                llmTest.update {
                    it.copy(
                        status = "配置不完整",
                        error = "请先填写方舟 API Key 和豆包模型的 Endpoint ID",
                    )
                }
            }

            llmTest.value.isRunning -> Unit
            else -> {
                llmTest.update {
                    it.copy(
                        isRunning = true,
                        status = "调用中",
                        outputText = "",
                        error = null,
                    )
                }
                viewModelScope.launch {
                    runCatching {
                        arkTextCorrector.runModelTest(inputText, settings)
                    }.onSuccess { output ->
                        llmTest.update {
                            it.copy(
                                isRunning = false,
                                status = "调用成功",
                                outputText = output,
                                error = null,
                            )
                        }
                    }.onFailure { error ->
                        llmTest.update {
                            it.copy(
                                isRunning = false,
                                status = "调用失败",
                                error = error.message ?: "LLM 测试失败",
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        recognizer.destroy()
        super.onCleared()
    }

    private fun startSpeechTest(settings: AppSettings) {
        speechTest.value = SpeechTestUiState(
            isRunning = true,
            status = "连接中",
        )
        runCatching {
            recognizer.startListening(settings, speechTestCallback())
        }.onFailure { error ->
            recognizer.destroy()
            speechTest.value = SpeechTestUiState(
                status = "启动失败",
                error = error.message ?: "无法启动语音识别",
            )
        }
    }

    private fun speechTestCallback(): DoubaoSpeechRecognizer.Callback {
        return object : DoubaoSpeechRecognizer.Callback {
            override fun onReady() {
                speechTest.update {
                    it.copy(
                        isRunning = true,
                        status = "录音中",
                        error = null,
                    )
                }
            }

            override fun onPartialText(text: String) {
                speechTest.update {
                    it.copy(
                        isRunning = true,
                        status = "录音中",
                        partialText = text,
                        error = null,
                    )
                }
            }

            override fun onFinalText(text: String) {
                recognizer.destroy()
                speechTest.value = if (text.isBlank()) {
                    SpeechTestUiState(
                        status = "未识别到内容",
                        error = "这次测试没有拿到最终文本",
                    )
                } else {
                    SpeechTestUiState(
                        status = "识别完成",
                        finalText = text,
                    )
                }
            }

            override fun onError(message: String) {
                recognizer.destroy()
                speechTest.update {
                    it.copy(
                        isRunning = false,
                        status = "识别失败",
                        error = message,
                    )
                }
            }
        }
    }
}
