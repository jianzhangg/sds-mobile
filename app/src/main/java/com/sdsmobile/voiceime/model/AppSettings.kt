package com.sdsmobile.voiceime.model

data class AppSettings(
    val speechToken: String = "",
    val arkApiKey: String = "",
    val arkModel: String = DEFAULT_ARK_MODEL_ID,
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
        const val LEGACY_SPEECH_URI = "/api/v3/sauc/bigmodel"
        const val DEFAULT_SPEECH_URI = "/api/v3/sauc/bigmodel_async"
        const val NOSTREAM_SPEECH_URI = "/api/v3/sauc/bigmodel_nostream"
        const val DEFAULT_ARK_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
        const val DEFAULT_ARK_MODEL_ID = "doubao-seed-2-0-pro-260215"
        const val DEFAULT_CORRECTION_PROMPT =
            "你是中文输入法纠错器。请在不改变原意的前提下修正错别字、标点、语气词、数字格式、单位写法、小数点和语音识别导致的同音错误。只输出修正后的最终文本，不要解释。"
        const val DEFAULT_LLM_TEST_INPUT =
            "今天天器不错 明天下五两点开会 记得带和同原件"
        const val DEFAULT_SPEECH_TEST_REFERENCE_TEXT =
            "今天天气不错，明天下午两点开会，记得带合同原件。"
    }
}
