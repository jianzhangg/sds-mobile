package com.sdsmobile.voiceime.speech

import android.content.Context
import android.provider.Settings
import com.sdsmobile.voiceime.model.AppSettings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

data class SpeechDebugMatrixReport(
    val status: String,
    val summaryLines: List<String>,
    val fullReport: String,
    val bestText: String,
    val error: String?,
    val progressLines: List<String>,
)

private data class SpeechProbeCase(
    val name: String,
    val includeCodec: Boolean,
    val requestOptions: JSONObject,
)

private data class SpeechProbeCaseResult(
    val name: String,
    val success: Boolean,
    val error: String?,
    val finalText: String,
    val reqId: String?,
    val logId: String?,
    val requestHeaders: Map<String, String>,
    val requestJson: String,
    val events: List<String>,
    val durationMs: Long,
)

private data class ParsedServerPacket(
    val messageType: Int,
    val flags: Int,
    val sequence: Int?,
    val payloadText: String,
    val payloadPreview: String,
)

class DoubaoSpeechDebugProbe(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun runMatrix(
        settings: AppSettings,
        audioFile: File,
        onProgress: (String) -> Unit,
    ): SpeechDebugMatrixReport = withContext(Dispatchers.IO) {
        val audioBytes = audioFile.readBytes()
        val progressLines = mutableListOf(
            "测试模式: 原生 WebSocket 参数矩阵",
            "音频文件: ${audioFile.absolutePath}",
            "音频字节数: ${audioBytes.size}",
            "参考文本: ${AppSettings.DEFAULT_SPEECH_TEST_REFERENCE_TEXT}",
        )
        val cases = buildCases()
        val results = mutableListOf<SpeechProbeCaseResult>()
        val uid = resolveUid()

        cases.forEachIndexed { index, probeCase ->
            val progress = "CASE ${index + 1}/${cases.size}: ${probeCase.name}"
            progressLines += progress
            onProgress(progress)
            val result = runCase(
                settings = settings,
                caseConfig = probeCase,
                uid = uid,
                audioBytes = audioBytes,
            ) { event ->
                val line = "${probeCase.name}: $event"
                progressLines += line
                onProgress(line)
            }
            results += result
            val caseSummary = buildString {
                append(probeCase.name)
                append(" -> ")
                append(if (result.success) "成功" else "失败")
                result.reqId?.let { append(" | req_id=$it") }
                result.logId?.let { append(" | logid=$it") }
                if (result.finalText.isNotBlank()) {
                    append(" | text=")
                    append(result.finalText)
                }
                result.error?.let {
                    append(" | error=")
                    append(it)
                }
            }
            progressLines += caseSummary
            onProgress(caseSummary)
        }

        val summaryLines = results.map { result ->
            buildString {
                append(if (result.success) "[成功] " else "[失败] ")
                append(result.name)
                result.reqId?.let { append(" | req_id=$it") }
                result.logId?.let { append(" | logid=$it") }
                if (result.finalText.isNotBlank()) {
                    append(" | text=")
                    append(result.finalText)
                }
                if (!result.success && !result.error.isNullOrBlank()) {
                    append(" | ")
                    append(result.error)
                }
            }
        }
        val bestResult = results.firstOrNull { it.success && it.finalText.isNotBlank() }
        val error = if (bestResult == null) {
            results.firstNotNullOfOrNull { it.error } ?: "所有参数组合都失败了"
        } else {
            null
        }
        SpeechDebugMatrixReport(
            status = when {
                bestResult != null -> "矩阵完成，已找到可用组合"
                else -> "矩阵完成，全部失败"
            },
            summaryLines = summaryLines,
            fullReport = buildFullReport(
                audioFile = audioFile,
                audioBytes = audioBytes,
                results = results,
            ),
            bestText = bestResult?.finalText.orEmpty(),
            error = error,
            progressLines = progressLines.takeLast(MAX_PROGRESS_LINES),
        )
    }

    private suspend fun runCase(
        settings: AppSettings,
        caseConfig: SpeechProbeCase,
        uid: String,
        audioBytes: ByteArray,
        onEvent: (String) -> Unit,
    ): SpeechProbeCaseResult {
        val startTime = System.currentTimeMillis()
        val requestJson = buildRequestJson(
            uid = uid,
            includeCodec = caseConfig.includeCodec,
            requestOptions = caseConfig.requestOptions,
        )
        val prettyJson = requestJson.toString(2)
        val connectId = UUID.randomUUID().toString()
        val headers = linkedMapOf(
            "X-Api-App-Key" to AppSettings.DEFAULT_SPEECH_APP_ID,
            "X-Api-Access-Key" to maskToken(settings.speechToken),
            "X-Api-Resource-Id" to AppSettings.DEFAULT_SPEECH_RESOURCE_ID,
            "X-Api-Connect-Id" to connectId,
        )
        val events = mutableListOf<String>()
        val done = AtomicBoolean(false)
        var socket: WebSocket? = null
        var audioThread: Thread? = null
        var logId: String? = null
        var reqId: String? = null
        var finalText = ""
        var terminalError: String? = null

        fun log(message: String) {
            events += message
            onEvent(message)
        }

        fun finish(success: Boolean, error: String?): SpeechProbeCaseResult {
            done.set(true)
            audioThread?.interrupt()
            socket?.cancel()
            return SpeechProbeCaseResult(
                name = caseConfig.name,
                success = success,
                error = error,
                finalText = finalText,
                reqId = reqId,
                logId = logId,
                requestHeaders = headers,
                requestJson = prettyJson,
                events = events.takeLast(MAX_CASE_EVENTS),
                durationMs = System.currentTimeMillis() - startTime,
            )
        }

        return try {
            withTimeout(CASE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder()
                        .url("${AppSettings.DEFAULT_SPEECH_ADDRESS.trimEnd('/')}${AppSettings.DEFAULT_SPEECH_URI}")
                        .header("X-Api-App-Key", AppSettings.DEFAULT_SPEECH_APP_ID)
                        .header("X-Api-Access-Key", normalizeToken(settings.speechToken))
                        .header("X-Api-Resource-Id", AppSettings.DEFAULT_SPEECH_RESOURCE_ID)
                        .header("X-Api-Connect-Id", connectId)
                        .build()
                    log("connect_id=$connectId")
                    log("headers=${headers.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
                    log("request_json=$prettyJson")

                    fun complete(success: Boolean, error: String?) {
                        if (!done.compareAndSet(false, true)) {
                            return
                        }
                        audioThread?.interrupt()
                        socket?.cancel()
                        if (continuation.isActive) {
                            continuation.resume(finish(success, error))
                        }
                    }

                    socket = client.newWebSocket(
                        request,
                        object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                logId = response.header("X-Tt-Logid")
                                log("ws_open logid=${logId.orEmpty()} code=${response.code}")
                                val fullRequestPacket = buildFullRequestPacket(requestJson.toString())
                                val fullRequestSent = webSocket.send(fullRequestPacket.toByteString())
                                log("send_full_request bytes=${fullRequestPacket.size} ok=$fullRequestSent")
                                if (!fullRequestSent) {
                                    complete(false, "完整请求发送失败")
                                    return
                                }
                                audioThread = Thread({
                                    streamAudio(
                                        webSocket = webSocket,
                                        audioBytes = audioBytes,
                                        done = done,
                                        onEvent = ::log,
                                    )
                                }, "speech-probe-${caseConfig.name}")
                                audioThread?.start()
                            }

                            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                                val packet = parseServerPacket(bytes.toByteArray())
                                reqId = reqId ?: extractReqId(packet.payloadText)
                                when (packet.messageType) {
                                    SERVER_FULL_RESPONSE -> {
                                        log(
                                            "recv_response flags=${packet.flags} seq=${packet.sequence ?: "-"} payload=${packet.payloadPreview}",
                                        )
                                        val text = extractText(packet.payloadText)
                                        if (text.isNotBlank()) {
                                            finalText = text
                                        }
                                        if (packet.flags == SERVER_EVENT_FINAL_FLAG) {
                                            complete(finalText.isNotBlank(), if (finalText.isBlank()) "服务端返回最终包但没有文本" else null)
                                        }
                                    }

                                    SERVER_ERROR_RESPONSE -> {
                                        terminalError = parseErrorMessage(packet.payloadText)
                                        log("recv_error payload=${packet.payloadPreview}")
                                        complete(false, terminalError)
                                    }

                                    else -> {
                                        log("recv_type=${packet.messageType} flags=${packet.flags} payload=${packet.payloadPreview}")
                                    }
                                }
                            }

                            override fun onMessage(webSocket: WebSocket, text: String) {
                                reqId = reqId ?: extractReqId(text)
                                log("recv_text=$text")
                            }

                            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                                log("ws_failure=${t.message.orEmpty()}")
                                complete(false, t.message ?: "WebSocket 失败")
                            }

                            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                                log("ws_closing code=$code reason=$reason")
                                webSocket.close(code, reason)
                            }

                            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                                if (done.get()) {
                                    return
                                }
                                log("ws_closed code=$code reason=$reason")
                                complete(finalText.isNotBlank(), terminalError ?: if (finalText.isBlank()) "连接已关闭，未返回可用文本" else null)
                            }
                        },
                    )
                    continuation.invokeOnCancellation {
                        done.set(true)
                        audioThread?.interrupt()
                        socket?.cancel()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            finish(false, "等待服务端响应超时")
        }
    }

    private fun streamAudio(
        webSocket: WebSocket,
        audioBytes: ByteArray,
        done: AtomicBoolean,
        onEvent: (String) -> Unit,
    ) {
        val chunkSize = PCM_BYTES_PER_200MS
        val chunkCount = (audioBytes.size + chunkSize - 1) / chunkSize
        onEvent("audio.total_bytes=${audioBytes.size} chunk_size=$chunkSize chunk_count=$chunkCount")
        for (index in 0 until chunkCount) {
            if (done.get()) {
                return
            }
            val start = index * chunkSize
            val end = minOf(audioBytes.size, start + chunkSize)
            val isLast = index == chunkCount - 1
            val packet = buildAudioPacket(audioBytes.copyOfRange(start, end), isLast)
            val sent = webSocket.send(packet.toByteString())
            if (index < 3 || isLast) {
                onEvent("audio.chunk=${index + 1}/$chunkCount bytes=${end - start} last=$isLast ok=$sent")
            }
            if (!sent) {
                return
            }
            if (!isLast) {
                Thread.sleep(200L)
            }
        }
    }

    private fun buildCases(): List<SpeechProbeCase> {
        fun request(
            includePunc: Boolean,
            includeUtterances: Boolean,
            includeVad: Boolean,
        ): JSONObject {
            return JSONObject()
                .put("model_name", "bigmodel")
                .apply {
                    if (includePunc) {
                        put("enable_itn", true)
                        put("enable_punc", true)
                    }
                    if (includeUtterances) {
                        put("show_utterances", true)
                    }
                    if (includeVad) {
                        put("end_window_size", 800)
                        put("force_to_speech_time", 0)
                    }
                }
        }

        return listOf(
            SpeechProbeCase("A. 最小请求 + codec", true, request(false, false, false)),
            SpeechProbeCase("B. 最小请求 - codec", false, request(false, false, false)),
            SpeechProbeCase("C. ITN/Punc + codec", true, request(true, false, false)),
            SpeechProbeCase("D. ITN/Punc - codec", false, request(true, false, false)),
            SpeechProbeCase("E. 完整听写参数 + codec", true, request(true, true, true)),
            SpeechProbeCase("F. 完整听写参数 - codec", false, request(true, true, true)),
        )
    }

    private fun buildRequestJson(
        uid: String,
        includeCodec: Boolean,
        requestOptions: JSONObject,
    ): JSONObject {
        return JSONObject()
            .put("user", JSONObject().put("uid", uid))
            .put(
                "audio",
                JSONObject()
                    .put("format", "pcm")
                    .put("rate", 16000)
                    .put("bits", 16)
                    .put("channel", 1)
                    .apply {
                        if (includeCodec) {
                            put("codec", "raw")
                        }
                    },
            )
            .put("request", requestOptions)
    }

    private fun buildFullReport(
        audioFile: File,
        audioBytes: ByteArray,
        results: List<SpeechProbeCaseResult>,
    ): String {
        return buildString {
            appendLine("豆包语音参数矩阵调试报告")
            appendLine()
            appendLine("音频文件: ${audioFile.absolutePath}")
            appendLine("音频字节数: ${audioBytes.size}")
            appendLine("参考文本: ${AppSettings.DEFAULT_SPEECH_TEST_REFERENCE_TEXT}")
            appendLine()
            appendLine("总览:")
            results.forEach { result ->
                append("- ")
                append(if (result.success) "[成功] " else "[失败] ")
                append(result.name)
                result.reqId?.let { append(" | req_id=$it") }
                result.logId?.let { append(" | logid=$it") }
                if (result.finalText.isNotBlank()) {
                    append(" | text=${result.finalText}")
                }
                if (!result.success && !result.error.isNullOrBlank()) {
                    append(" | error=${result.error}")
                }
                appendLine()
            }
            results.forEach { result ->
                appendLine()
                appendLine("==== ${result.name} ====")
                appendLine("状态: ${if (result.success) "成功" else "失败"}")
                appendLine("耗时: ${result.durationMs} ms")
                appendLine("req_id: ${result.reqId.orEmpty()}")
                appendLine("logid: ${result.logId.orEmpty()}")
                appendLine("请求头:")
                result.requestHeaders.forEach { (key, value) ->
                    appendLine("$key: $value")
                }
                appendLine("请求 JSON:")
                appendLine(result.requestJson)
                if (result.finalText.isNotBlank()) {
                    appendLine("最终文本: ${result.finalText}")
                }
                result.error?.let {
                    appendLine("错误: $it")
                }
                appendLine("事件:")
                result.events.forEach { event ->
                    appendLine(event)
                }
            }
        }.trim()
    }

    private fun buildFullRequestPacket(json: String): ByteArray {
        val payload = gzip(json.toByteArray(Charsets.UTF_8))
        return buildPacket(
            messageType = CLIENT_FULL_REQUEST,
            flags = 0,
            serialization = SERIALIZATION_JSON,
            compression = COMPRESSION_GZIP,
            payload = payload,
        )
    }

    private fun buildAudioPacket(audio: ByteArray, isLast: Boolean): ByteArray {
        val payload = gzip(audio)
        return buildPacket(
            messageType = CLIENT_AUDIO_ONLY_REQUEST,
            flags = if (isLast) CLIENT_AUDIO_FINAL_FLAG else 0,
            serialization = SERIALIZATION_NONE,
            compression = COMPRESSION_GZIP,
            payload = payload,
        )
    }

    private fun buildPacket(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        payload: ByteArray,
    ): ByteArray {
        val header = byteArrayOf(
            (((PROTOCOL_VERSION shl 4) or HEADER_WORD_SIZE).and(0xFF)).toByte(),
            (((messageType shl 4) or flags).and(0xFF)).toByte(),
            (((serialization shl 4) or compression).and(0xFF)).toByte(),
            0,
        )
        val payloadSize = intToBytes(payload.size)
        return header + payloadSize + payload
    }

    private fun parseServerPacket(bytes: ByteArray): ParsedServerPacket {
        if (bytes.size < 8) {
            return ParsedServerPacket(
                messageType = -1,
                flags = -1,
                sequence = null,
                payloadText = bytes.decodeToString(),
                payloadPreview = bytes.decodeToString().take(400),
            )
        }
        val headerWords = bytes[0].toInt() and 0x0F
        val headerSize = headerWords * 4
        val messageType = (bytes[1].toInt() and 0xF0) shr 4
        val flags = bytes[1].toInt() and 0x0F
        val compression = bytes[2].toInt() and 0x0F
        var offset = headerSize
        var sequence: Int? = null

        if (messageType == SERVER_FULL_RESPONSE && (flags == SERVER_EVENT_ACK_FLAG || flags == SERVER_EVENT_FINAL_FLAG)) {
            sequence = bytes.readInt(offset)
            offset += 4
        }

        val payloadText = when (messageType) {
            SERVER_ERROR_RESPONSE -> {
                offset += 4 // error code
                val payloadSize = bytes.readInt(offset)
                offset += 4
                decodePayload(bytes, offset, payloadSize, compression)
            }

            else -> {
                val payloadSize = bytes.readInt(offset)
                offset += 4
                decodePayload(bytes, offset, payloadSize, compression)
            }
        }

        return ParsedServerPacket(
            messageType = messageType,
            flags = flags,
            sequence = sequence,
            payloadText = payloadText,
            payloadPreview = payloadText.take(MAX_PAYLOAD_PREVIEW),
        )
    }

    private fun decodePayload(
        bytes: ByteArray,
        offset: Int,
        payloadSize: Int,
        compression: Int,
    ): String {
        val end = (offset + payloadSize).coerceAtMost(bytes.size)
        if (offset >= end) {
            return ""
        }
        val payload = bytes.copyOfRange(offset, end)
        val decoded = when (compression) {
            COMPRESSION_GZIP -> gunzip(payload)
            else -> payload
        }
        return decoded.toString(Charsets.UTF_8)
    }

    private fun extractText(payload: String): String {
        return runCatching {
            val reader = JSONObject(payload)
            val result = reader.opt("result") ?: return@runCatching reader.optString("text").trim()
            when (result) {
                is JSONObject -> result.optString("text").trim()
                else -> result.toString().trim()
            }
        }.getOrDefault("")
    }

    private fun parseErrorMessage(payload: String): String {
        return runCatching {
            val root = JSONObject(payload)
            val message = root.optString("err_msg")
                .ifBlank { root.optString("message") }
                .ifBlank { root.optString("error") }
                .ifBlank { root.optString("status_message") }
            val code = root.opt("err_code")?.toString().orEmpty()
                .ifBlank { root.opt("code")?.toString().orEmpty() }
            val requestId = extractReqId(payload).orEmpty()
            when {
                message.isBlank() -> payload
                code.isBlank() && requestId.isBlank() -> message
                requestId.isBlank() -> "$message (code=$code)"
                code.isBlank() -> "$message (req_id=$requestId)"
                else -> "$message (code=$code, req_id=$requestId)"
            }
        }.getOrDefault(payload)
    }

    private fun extractReqId(payload: String): String? {
        return runCatching {
            val root = JSONObject(payload)
            root.optString("req_id")
                .ifBlank { root.optString("request_id") }
                .ifBlank { root.optString("reqid") }
                .ifBlank { null }
        }.getOrNull()
    }

    private fun resolveUid(): String {
        return Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID) ?: "voice-ime"
    }

    private fun normalizeToken(token: String): String {
        return token.trim()
            .removePrefix("Bearer;")
            .removePrefix("Bearer ")
            .trim()
    }

    private fun maskToken(token: String): String {
        val normalized = normalizeToken(token)
        if (normalized.length <= 8) {
            return "*".repeat(normalized.length)
        }
        return normalized.take(4) + "***" + normalized.takeLast(4)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { output ->
            output.write(bytes)
        }
        return buffer.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readBytes()
        }
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    private fun ByteArray.readInt(offset: Int): Int {
        if (offset + 4 > size) {
            return 0
        }
        return ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)
    }

    companion object {
        private const val PROTOCOL_VERSION = 1
        private const val HEADER_WORD_SIZE = 1
        private const val CLIENT_FULL_REQUEST = 1
        private const val CLIENT_AUDIO_ONLY_REQUEST = 2
        private const val CLIENT_AUDIO_FINAL_FLAG = 2
        private const val SERVER_FULL_RESPONSE = 9
        private const val SERVER_ERROR_RESPONSE = 15
        private const val SERVER_EVENT_ACK_FLAG = 1
        private const val SERVER_EVENT_FINAL_FLAG = 3
        private const val SERIALIZATION_NONE = 0
        private const val SERIALIZATION_JSON = 1
        private const val COMPRESSION_GZIP = 1
        private const val PCM_BYTES_PER_200MS = 6_400
        private const val CASE_TIMEOUT_MS = 20_000L
        private const val MAX_CASE_EVENTS = 80
        private const val MAX_PROGRESS_LINES = 120
        private const val MAX_PAYLOAD_PREVIEW = 500
    }
}
