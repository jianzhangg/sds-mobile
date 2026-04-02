package com.sdsmobile.voiceime.model

data class AppSettings(
    val speechToken: String = "",
    val arkApiKey: String = "",
    val arkModel: String = DEFAULT_ARK_MODEL_ID,
    val textOptimizationEnabled: Boolean = false,
    val personalizationEnabled: Boolean = false,
    val personalizationPrompt: String = "",
    val autoStructureEnabled: Boolean = true,
    val fillerWordFilterEnabled: Boolean = true,
    val trimTrailingPeriodEnabled: Boolean = false,
    val screenContextEnabled: Boolean = false,
) {
    fun isSpeechConfigured(): Boolean {
        return speechToken.isNotBlank()
    }

    fun isCorrectionConfigured(): Boolean {
        return arkApiKey.isNotBlank() && arkModel.isNotBlank()
    }

    companion object {
        const val DEFAULT_SPEECH_APP_ID = "2586725503"
        const val DEFAULT_SPEECH_RESOURCE_ID = "volc.seedasr.sauc.duration"
        const val DEFAULT_SPEECH_ADDRESS = "wss://openspeech.bytedance.com"
        const val DEFAULT_SPEECH_URI = "/api/v3/sauc/bigmodel_async"
        const val DEFAULT_ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        const val DEFAULT_ARK_MODEL_ID = "doubao-seed-2-0-pro-260215"
        const val DEFAULT_CORRECTION_PROMPT =
            "你是语音输入法的中文文本优化器。请在不改变原意的前提下输出适合直接输入的最终文本。可以修正明显的识别错误、标点、数字格式、单位写法、小数点和同音误识别，但不能编造用户没有说过或没有写过的事实。只输出最终文本，不要解释。"
        const val DEFAULT_LLM_TEST_INPUT =
            "今天天器不错 明天下五两点开会 记得带和同原件"
        const val DEFAULT_SPEECH_TEST_REFERENCE_TEXT =
            "今天天气不错，明天下午两点开会，记得带合同原件。"
    }
}
