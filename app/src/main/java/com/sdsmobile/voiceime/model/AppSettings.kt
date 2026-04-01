package com.sdsmobile.voiceime.model

enum class AsrMode {
    BIG_MODEL,
    STANDARD,
}

data class AppSettings(
    val asrMode: AsrMode = AsrMode.BIG_MODEL,
    val speechAppId: String = "",
    val speechToken: String = "",
    val speechCluster: String = "",
    val speechResourceId: String = "",
    val speechAddress: String = "wss://openspeech.bytedance.com",
    val speechUri: String = "/api/v3/sauc/bigmodel",
    val speechRequestParamsJson: String = DEFAULT_ASR_REQUEST_PARAMS_JSON,
    val arkApiKey: String = "",
    val arkBaseUrl: String = DEFAULT_ARK_BASE_URL,
    val arkModel: String = "",
    val correctionPrompt: String = DEFAULT_CORRECTION_PROMPT,
) {
    fun isSpeechConfigured(): Boolean {
        if (speechAppId.isBlank() || speechToken.isBlank()) {
            return false
        }
        return when (asrMode) {
            AsrMode.BIG_MODEL -> speechResourceId.isNotBlank()
            AsrMode.STANDARD -> speechCluster.isNotBlank()
        }
    }

    fun isCorrectionConfigured(): Boolean {
        return arkApiKey.isNotBlank() && arkModel.isNotBlank()
    }

    companion object {
        const val DEFAULT_ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        const val DEFAULT_CORRECTION_PROMPT =
            "你是中文输入法纠错器。请在不改变原意的前提下修正错别字、标点、语气词和语音识别导致的同音错误。只输出修正后的最终文本，不要解释。"
        const val DEFAULT_ASR_REQUEST_PARAMS_JSON =
            """{"end_window_size":800,"force_to_speech_time":0}"""
    }
}
