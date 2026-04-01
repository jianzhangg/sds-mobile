package com.sdsmobile.voiceime.speech

import com.sdsmobile.voiceime.model.AppSettings
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
                operationPrompt = "这是豆包模型连通性测试。请把下面这段文本修正成适合直接输入的最终版本，只输出结果。",
                inputText = text,
            )
        }
    }

    @Throws(IOException::class)
    suspend fun correctDictation(text: String, settings: AppSettings): String {
        return withContext(Dispatchers.IO) {
            requestCorrection(
                settings = settings,
                operationPrompt = "请把下面的语音识别文本修正成适合直接输入的最终版本。不要解释。",
                inputText = text,
            )
        }
    }

    @Throws(IOException::class)
    suspend fun correctExistingText(text: String, settings: AppSettings): String {
        return withContext(Dispatchers.IO) {
            requestCorrection(
                settings = settings,
                operationPrompt = "请修正下面输入框里的现有文本。保持原意，不扩写，只输出修正后的完整文本。",
                inputText = text,
            )
        }
    }

    private fun requestCorrection(
        settings: AppSettings,
        operationPrompt: String,
        inputText: String,
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
                            .put("content", "$operationPrompt\n\n$inputText"),
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
}
