package com.sdsmobile.voiceime.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdsmobile.voiceime.R
import com.sdsmobile.voiceime.data.AppSettingsRepository
import com.sdsmobile.voiceime.model.AppSettings
import com.sdsmobile.voiceime.speech.ArkTextCorrector
import com.sdsmobile.voiceime.speech.DoubaoSpeechRecognizer
import java.io.File
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
    val logs: List<String> = emptyList(),
    val debugReport: String = "",
    val errorDialog: String? = null,
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
    private val appContext = appContext.applicationContext
    private val draft = MutableStateFlow(AppSettings())
    private val speechTest = MutableStateFlow(SpeechTestUiState())
    private val llmTest = MutableStateFlow(LlmTestUiState())
    private val speechRecognizer = DoubaoSpeechRecognizer(this.appContext)

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

    fun runSpeechTest() {
        if (speechTest.value.isRunning) {
            return
        }

        val settings = draft.value
        if (!settings.isSpeechConfigured()) {
            speechTest.value = SpeechTestUiState(
                status = "配置不完整",
                error = "请先填写豆包语音的 Speech Access Token",
                errorDialog = "请先填写豆包语音的 Speech Access Token",
            )
            return
        }
        startSpeechTest(settings)
    }

    fun dismissSpeechErrorDialog() {
        speechTest.update { it.copy(errorDialog = null) }
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
                        error = "请先填写方舟 API Key 和豆包模型的 Model ID",
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

    private fun startSpeechTest(settings: AppSettings) {
        val sampleFile = ensureSpeechTestAudioFile()
        val initialLogs = listOf(
            "测试模式: SDK 内置音频测试",
            "音频文件: ${sampleFile.absolutePath}",
            "参考文本: ${AppSettings.DEFAULT_SPEECH_TEST_REFERENCE_TEXT}",
            "识别接口: ${AppSettings.DEFAULT_SPEECH_URI}",
        )
        speechTest.value = SpeechTestUiState(
            isRunning = true,
            status = "准备测试",
            logs = initialLogs,
        )
        viewModelScope.launch {
            runCatching {
                speechRecognizer.destroy()
                speechRecognizer.startListeningFromFile(
                    settings = settings,
                    audioFilePath = sampleFile.absolutePath,
                    callback = object : DoubaoSpeechRecognizer.Callback {
                        override fun onReady() {
                            if (!speechTest.value.isRunning) return
                            speechTest.update {
                                it.copy(
                                    status = "识别中",
                                    error = null,
                                    errorDialog = null,
                                )
                            }
                        }

                        override fun onPartialText(text: String) {
                            if (!speechTest.value.isRunning) return
                            speechTest.update {
                                it.copy(
                                    status = "识别中",
                                    partialText = text,
                                )
                            }
                        }

                        override fun onFinalText(text: String) {
                            if (!speechTest.value.isRunning) return
                            speechRecognizer.destroy()
                            val debugReport = buildSpeechDebugReport(
                                logs = speechTest.value.logs,
                                sdkLogTail = speechRecognizer.readDebugLogTail(),
                            )
                            speechTest.update {
                                it.copy(
                                    isRunning = false,
                                    status = "识别成功",
                                    partialText = "",
                                    finalText = text,
                                    error = null,
                                    debugReport = debugReport,
                                    errorDialog = null,
                                )
                            }
                        }

                        override fun onError(message: String) {
                            if (!speechTest.value.isRunning) return
                            speechRecognizer.destroy()
                            val currentLogs = speechTest.value.logs
                            val debugReport = buildSpeechDebugReport(
                                logs = currentLogs,
                                sdkLogTail = speechRecognizer.readDebugLogTail(),
                            )
                            speechTest.update {
                                it.copy(
                                    isRunning = false,
                                    status = "识别失败",
                                    partialText = "",
                                    error = message,
                                    debugReport = debugReport,
                                    errorDialog = buildSpeechErrorDialog(
                                        error = message,
                                        logs = currentLogs,
                                        debugReport = debugReport,
                                    ),
                                )
                            }
                        }

                        override fun onLog(message: String) {
                            appendSpeechLog(message)
                        }
                    },
                )
            }.onFailure { error ->
                speechTest.update {
                    it.copy(
                        isRunning = false,
                        status = "测试启动失败",
                        error = error.message ?: "无法启动 SDK 语音测试",
                        errorDialog = buildSpeechErrorDialog(
                            error = error.message ?: "无法启动 SDK 语音测试",
                            logs = speechTest.value.logs,
                            debugReport = speechTest.value.debugReport,
                        ),
                    )
                }
            }
        }
    }

    private fun appendSpeechLog(message: String) {
        speechTest.update {
            val nextLogs = (it.logs + message).takeLast(120)
            it.copy(logs = nextLogs)
        }
    }

    private fun buildSpeechDebugReport(logs: List<String>, sdkLogTail: String): String {
        return buildString {
            append("豆包语音 SDK 测试报告\n\n")
            append("参考文本：")
            append(AppSettings.DEFAULT_SPEECH_TEST_REFERENCE_TEXT)
            append("\n\n")
            if (logs.isNotEmpty()) {
                append("进度日志：\n")
                append(logs.joinToString("\n"))
            }
            if (sdkLogTail.isNotBlank()) {
                if (logs.isNotEmpty()) {
                    append("\n\n")
                }
                append("SDK 日志：\n")
                append(sdkLogTail)
            }
        }.trim()
    }

    private fun ensureSpeechTestAudioFile(): File {
        val target = File(appContext.cacheDir, "speech_test_sample.pcm")
        if (target.exists() && target.length() > 0) {
            return target
        }
        appContext.resources.openRawResource(R.raw.speech_test_sample).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
    }

    private fun buildSpeechErrorDialog(error: String, logs: List<String>, debugReport: String): String {
        return buildString {
            append("错误：")
            append(error)
            append("\n\n")
            append("参考文本：")
            append(AppSettings.DEFAULT_SPEECH_TEST_REFERENCE_TEXT)
            append("\n\n")
            append("日志：\n")
            append(logs.joinToString("\n"))
            if (debugReport.isNotBlank()) {
                append("\n\n完整调试报告：\n")
                append(debugReport)
            }
        }
    }

    override fun onCleared() {
        speechRecognizer.destroy()
        super.onCleared()
    }
}
