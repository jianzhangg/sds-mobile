package com.sdsmobile.voiceime.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdsmobile.voiceime.R
import com.sdsmobile.voiceime.data.AppSettingsRepository
import com.sdsmobile.voiceime.model.AppSettings
import com.sdsmobile.voiceime.speech.ArkTextCorrector
import com.sdsmobile.voiceime.speech.DoubaoSpeechDebugProbe
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
    val activeCase: String = "",
    val partialText: String = "",
    val finalText: String = "",
    val error: String? = null,
    val logs: List<String> = emptyList(),
    val summaryLines: List<String> = emptyList(),
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
    private val speechDebugProbe = DoubaoSpeechDebugProbe(this.appContext)

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
            "测试模式: 原生 WebSocket 参数矩阵",
            "音频文件: ${sampleFile.absolutePath}",
            "音频字节数: ${sampleFile.length()}",
            "参考文本: ${AppSettings.DEFAULT_SPEECH_TEST_REFERENCE_TEXT}",
        )
        speechTest.value = SpeechTestUiState(
            isRunning = true,
            status = "准备矩阵测试",
            logs = initialLogs,
        )
        viewModelScope.launch {
            runCatching {
                speechDebugProbe.runMatrix(settings, sampleFile) { progress ->
                    appendSpeechLog(progress)
                    speechTest.update {
                        it.copy(
                            activeCase = progress.substringBefore(":").takeIf { prefix -> prefix.startsWith("A.") || prefix.startsWith("B.") || prefix.startsWith("C.") || prefix.startsWith("D.") || prefix.startsWith("E.") || prefix.startsWith("F.") }
                                ?: it.activeCase,
                            status = if (progress.startsWith("CASE ")) progress else it.status,
                        )
                    }
                }
            }.onSuccess { report ->
                speechTest.update {
                    it.copy(
                        isRunning = false,
                        status = report.status,
                        activeCase = "",
                        finalText = report.bestText,
                        error = report.error,
                        logs = report.progressLines,
                        summaryLines = report.summaryLines,
                        debugReport = report.fullReport,
                        errorDialog = report.error?.let { error ->
                            buildSpeechErrorDialog(
                                error = error,
                                logs = report.progressLines,
                                debugReport = report.fullReport,
                            )
                        },
                    )
                }
            }.onFailure { error ->
                speechTest.update {
                    it.copy(
                        isRunning = false,
                        status = "矩阵启动失败",
                        activeCase = "",
                        error = error.message ?: "无法启动参数矩阵测试",
                        errorDialog = buildSpeechErrorDialog(
                            error = error.message ?: "无法启动参数矩阵测试",
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
}
