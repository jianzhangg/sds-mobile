package com.sdsmobile.voiceime.speech

import com.sdsmobile.voiceime.model.AppSettings
import com.sdsmobile.voiceime.model.ScreenContextSnapshot
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ArkTextCorrector {
    private val client = OkHttpClient()
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    @Throws(IOException::class)
    suspend fun runModelTest(text: String, settings: AppSettings): String {
        return withContext(Dispatchers.IO) {
            requestCorrection(
                settings = settings,
                operationPrompt = "这是语音输入法的模型连通性测试。请把下面这段文本整理成适合直接输入的最终版本，只输出结果。",
                inputText = text,
                screenContext = null,
            )
        }
    }

    @Throws(IOException::class)
    suspend fun correctDictation(
        text: String,
        settings: AppSettings,
        screenContext: ScreenContextSnapshot? = null,
    ): String {
        return withContext(Dispatchers.IO) {
            requestCorrection(
                settings = settings,
                operationPrompt = buildDictationPrompt(settings, screenContext),
                inputText = text,
                screenContext = screenContext,
            )
        }
    }

    @Throws(IOException::class)
    suspend fun correctExistingText(text: String, settings: AppSettings): String {
        return withContext(Dispatchers.IO) {
            requestCorrection(
                settings = settings,
                operationPrompt = buildExistingTextPrompt(settings),
                inputText = text,
                screenContext = null,
            )
        }
    }

    private fun requestCorrection(
        settings: AppSettings,
        operationPrompt: String,
        inputText: String,
        screenContext: ScreenContextSnapshot?,
    ): String {
        val payload = JSONObject()
            .put("model", settings.arkModel)
            .put("temperature", 0.1)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", AppSettings.DEFAULT_CORRECTION_PROMPT),
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", buildUserContent(operationPrompt, inputText, screenContext)),
                    ),
            )

        val request = Request.Builder()
            .url("${AppSettings.DEFAULT_ARK_BASE_URL.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${settings.arkApiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(mediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Ark request failed: ${response.code} $responseText")
            }

            val root = JSONObject(responseText)
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw IOException("Ark response missing choices")
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: throw IOException("Ark response missing message")
            return extractContent(message).ifBlank { inputText }
        }
    }

    private fun extractContent(message: JSONObject): String {
        val content = message.opt("content") ?: return ""
        return when (content) {
            is String -> content.trim()
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    if (item.optString("type") == "text") {
                        append(item.optString("text"))
                    }
                }
            }.trim()
            else -> content.toString().trim()
        }
    }

    private fun buildUserContent(
        operationPrompt: String,
        inputText: String,
        screenContext: ScreenContextSnapshot?,
    ): String {
        return buildString {
            append(operationPrompt.trim())
            append("\n\n原始文本：\n")
            append(inputText.trim())
            if (screenContext != null) {
                val contextText = screenContext.toPromptText()
                if (contextText.isNotBlank()) {
                    append("\n\n屏幕上下文（仅供参考，不能凭空补全用户未表达的内容）：\n")
                    append(contextText)
                }
            }
        }.trim()
    }

    private fun buildDictationPrompt(
        settings: AppSettings,
        screenContext: ScreenContextSnapshot?,
    ): String {
        return buildString {
            append("请把下面的语音识别文本整理成适合直接输入的最终版本。")
            append("\n要求：保持原意，不解释，不扩写，不添加用户没有说出的事实。")
            append("\n基础处理：修正明显识别错误、标点、数字格式、单位写法、小数点和同音误识别。")
            if (settings.autoStructureEnabled) {
                append("\n- 自动结构化：如果文本较长，请整理成更清晰、更干净的句子或段落。")
            }
            if (settings.fillerWordFilterEnabled) {
                append("\n- 口语过滤：去掉没有必要的口头填充词和重复词。")
            }
            if (settings.trimTrailingPeriodEnabled) {
                append("\n- 去除结尾句号：输出最后不要带句号。")
            }
            if (settings.personalizationEnabled && settings.personalizationPrompt.isNotBlank()) {
                append("\n- 个性化偏好：")
                append(settings.personalizationPrompt.trim())
            }
            if (screenContext != null && settings.screenContextEnabled) {
                append("\n- 结合屏幕上下文理解当前场景，但上下文只作为参考。")
            }
        }
    }

    private fun buildExistingTextPrompt(settings: AppSettings): String {
        return buildString {
            append("请优化下面输入框里的现有文本。")
            append("\n要求：保持原意，不扩写，只输出修正后的完整文本。")
            append("\n基础处理：修正明显识别错误、标点、数字格式、单位写法、小数点和不自然的句式。")
            if (settings.autoStructureEnabled) {
                append("\n- 自动结构化：必要时整理句子结构，让表达更清晰。")
            }
            if (settings.fillerWordFilterEnabled) {
                append("\n- 口语过滤：去掉明显多余的口头填充词和重复词。")
            }
            if (settings.trimTrailingPeriodEnabled) {
                append("\n- 去除结尾句号：输出最后不要带句号。")
            }
            if (settings.personalizationEnabled && settings.personalizationPrompt.isNotBlank()) {
                append("\n- 个性化偏好：")
                append(settings.personalizationPrompt.trim())
            }
        }
    }
}
