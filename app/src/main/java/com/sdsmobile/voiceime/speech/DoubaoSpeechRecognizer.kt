package com.sdsmobile.voiceime.speech

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.sdsmobile.voiceime.model.AppSettings
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.json.JSONObject

class DoubaoSpeechRecognizer(
    context: Context,
) {
    interface Callback {
        fun onReady()
        fun onPartialText(text: String)
        fun onFinalText(text: String)
        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private var engine: Any? = null
    private var engineHandle: Long? = null
    private var listenerProxy: Any? = null
    private val mainThreadExecutor = android.os.Handler(appContext.mainLooper)
    private val definesClass = Class.forName("com.bytedance.speech.speechengine.SpeechEngineDefines")
    private val generatorClass = Class.forName("com.bytedance.speech.speechengine.SpeechEngineGenerator")

    fun startListening(settings: AppSettings, callback: Callback) {
        prepareEnvironment()
        destroy()

        val engineInstance = generatorClass.getMethod("getInstance").invoke(null)
        engine = engineInstance
        engineHandle = createEngine(engineInstance)

        setContextIfSupported(engineInstance)
        configureEngine(engineInstance, settings)
        val initResult = callEngineInt(engineInstance, "initEngine")
        if (initResult != readIntConstant("ERR_NO_ERROR", 0)) {
            throw IllegalStateException("Init engine failed: $initResult")
        }

        listenerProxy = createListenerProxy(engineInstance, callback)
        invokeSetListener(engineInstance, listenerProxy!!)

        sendDirective("DIRECTIVE_SYNC_STOP_ENGINE")
        sendDirective("DIRECTIVE_START_ENGINE")
    }

    fun finishTalking() {
        sendDirective("DIRECTIVE_FINISH_TALKING")
    }

    fun stopNow() {
        sendDirective("DIRECTIVE_STOP_ENGINE")
    }

    fun destroy() {
        val targetEngine = engine ?: return
        try {
            invokeEngineMethod(targetEngine, "destroyEngine")
        } catch (_: Throwable) {
        } finally {
            engine = null
            engineHandle = null
            listenerProxy = null
        }
    }

    private fun prepareEnvironment() {
        val application = appContext as? Application
            ?: throw IllegalStateException("Application context required")

        runCatching {
            val method = generatorClass.methods.firstOrNull {
                it.name == "PrepareEnvironment" && it.parameterTypes.size == 2
            }
            if (method != null) {
                method.invoke(null, appContext, application)
                return
            }
        }

        runCatching {
            val method = generatorClass.methods.firstOrNull {
                it.name == "prepareEnvironment" && it.parameterTypes.isEmpty()
            }
            method?.invoke(null)
        }.getOrThrow()
    }

    private fun createEngine(engineInstance: Any): Long? {
        val createMethod = engineInstance.javaClass.methods.firstOrNull {
            it.name == "createEngine" && it.parameterTypes.isEmpty()
        } ?: throw IllegalStateException("SpeechEngine#createEngine not found")

        val result = createMethod.invoke(engineInstance)
        return (result as? Number)?.toLong()
    }

    private fun setContextIfSupported(engineInstance: Any) {
        val method = engineInstance.javaClass.methods.firstOrNull {
            it.name == "setContext" && it.parameterTypes.contentEquals(arrayOf(Context::class.java))
        } ?: return
        method.invoke(engineInstance, appContext)
    }

    private fun configureEngine(engineInstance: Any, settings: AppSettings) {
        setStringOption(engineInstance, "PARAMS_KEY_ENGINE_NAME_STRING", readStringConstant("ASR_ENGINE"))
        setStringOption(engineInstance, "PARAMS_KEY_UID_STRING", resolveUid())
        setStringOption(engineInstance, "PARAMS_KEY_LOG_LEVEL_STRING", readStringConstant("LOG_LEVEL_WARN"))
        setStringOption(engineInstance, "PARAMS_KEY_RECORDER_TYPE_STRING", readStringConstant("RECORDER_TYPE_RECORDER"))
        setBooleanOption(engineInstance, "PARAMS_KEY_ASR_SHOW_NLU_PUNC_BOOL", true)
        setBooleanOption(engineInstance, "PARAMS_KEY_ASR_AUTO_STOP_BOOL", true)
        setStringOption(engineInstance, "PARAMS_KEY_ASR_RESULT_TYPE_STRING", readStringConstant("ASR_RESULT_TYPE_FULL"))
        setStringOption(
            engineInstance,
            "PARAMS_KEY_ASR_REQ_PARAMS_STRING",
            AppSettings.DEFAULT_SPEECH_REQUEST_PARAMS_JSON,
        )
        setBooleanOption(engineInstance, "PARAMS_KEY_ASR_SHOW_UTTER_BOOL", true)
        setStringOption(engineInstance, "PARAMS_KEY_ASR_ADDRESS_STRING", AppSettings.DEFAULT_SPEECH_ADDRESS)
        setStringOption(engineInstance, "PARAMS_KEY_ASR_URI_STRING", AppSettings.DEFAULT_SPEECH_URI)
        setStringOption(engineInstance, "PARAMS_KEY_APP_ID_STRING", settings.speechAppId)
        setStringOption(engineInstance, "PARAMS_KEY_APP_TOKEN_STRING", settings.speechToken)
        setStringOption(engineInstance, "PARAMS_KEY_RESOURCE_ID_STRING", settings.speechResourceId)
        setIntOption(
            engineInstance,
            "PARAMS_KEY_PROTOCOL_TYPE_INT",
            readIntConstant("PROTOCOL_TYPE_SEED", 2),
        )
    }

    private fun resolveUid(): String {
        val androidId = Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        return androidId ?: "voice-ime"
    }

    private fun createListenerProxy(engineInstance: Any, callback: Callback): Any {
        val setListenerMethod = engineInstance.javaClass.methods.firstOrNull {
            it.name == "setListener" && it.parameterTypes.size == 1
        } ?: throw IllegalStateException("SpeechEngine#setListener not found")
        val listenerType = setListenerMethod.parameterTypes[0]
        val handler = InvocationHandler { _, method, args ->
            if (method.name == "onSpeechMessage" && args != null && args.size >= 3) {
                handleSpeechMessage(callback, args)
            }
            defaultValue(method)
        }
        return Proxy.newProxyInstance(
            listenerType.classLoader,
            arrayOf(listenerType),
            handler,
        )
    }

    private fun handleSpeechMessage(callback: Callback, args: Array<Any?>) {
        val type = (args[0] as? Number)?.toInt() ?: return
        val raw = args[1] as? ByteArray ?: ByteArray(0)
        val len = ((args[2] as? Number)?.toInt() ?: raw.size).coerceIn(0, raw.size)
        val payload = raw.copyOfRange(0, len).toString(Charsets.UTF_8)

        when (type) {
            readIntConstant("MESSAGE_TYPE_ENGINE_START", -1) -> postToMain { callback.onReady() }
            readIntConstant("MESSAGE_TYPE_PARTIAL_RESULT", -1) -> {
                val text = extractText(payload)
                if (text.isNotBlank()) {
                    postToMain { callback.onPartialText(text) }
                }
            }
            readIntConstant("MESSAGE_TYPE_FINAL_RESULT", -1) -> {
                val text = extractText(payload)
                postToMain { callback.onFinalText(text) }
            }
            readIntConstant("MESSAGE_TYPE_ENGINE_ERROR", -1) -> {
                val message = parseErrorMessage(payload)
                postToMain { callback.onError(message) }
            }
        }
    }

    private fun parseErrorMessage(payload: String): String {
        return runCatching {
            JSONObject(payload).optString("err_msg").ifBlank { payload }
        }.getOrDefault(payload.ifBlank { "识别失败" })
    }

    private fun extractText(payload: String): String {
        return runCatching {
            val reader = JSONObject(payload)
            if (!reader.has("result")) {
                return@runCatching ""
            }
            val result = reader.getJSONArray("result")
            buildString {
                for (index in 0 until result.length()) {
                    append(result.getJSONObject(index).optString("text"))
                }
            }.trim()
        }.getOrDefault("")
    }

    private fun postToMain(action: () -> Unit) {
        mainThreadExecutor.post(action)
    }

    private fun invokeSetListener(engineInstance: Any, listener: Any) {
        val method = engineInstance.javaClass.methods.firstOrNull {
            it.name == "setListener" && it.parameterTypes.size == 1
        } ?: throw IllegalStateException("SpeechEngine#setListener not found")
        method.invoke(engineInstance, listener)
    }

    private fun sendDirective(constantName: String) {
        val targetEngine = engine ?: return
        val directive = readIntConstant(constantName, -1)
        if (directive == -1) {
            return
        }
        invokeEngineMethod(targetEngine, "sendDirective", directive, "")
    }

    private fun callEngineInt(engineInstance: Any, name: String): Int {
        return (invokeEngineMethod(engineInstance, name) as? Number)?.toInt() ?: 0
    }

    private fun setStringOption(engineInstance: Any, keyConstant: String, value: String) {
        val key = readStringConstant(keyConstant)
        if (key.isEmpty()) {
            return
        }
        invokeEngineMethod(engineInstance, "setOptionString", key, value)
    }

    private fun setBooleanOption(engineInstance: Any, keyConstant: String, value: Boolean) {
        val key = readStringConstant(keyConstant)
        if (key.isEmpty()) {
            return
        }
        invokeEngineMethod(engineInstance, "setOptionBoolean", key, value)
    }

    private fun setIntOption(engineInstance: Any, keyConstant: String, value: Int) {
        val key = readStringConstant(keyConstant)
        if (key.isEmpty()) {
            return
        }
        invokeEngineMethod(engineInstance, "setOptionInt", key, value)
    }

    private fun invokeEngineMethod(engineInstance: Any, name: String, vararg args: Any): Any? {
        val withHandleArgs = engineHandle?.let { arrayOf(it as Any, *args) }
        val candidateMethods = engineInstance.javaClass.methods.filter { it.name == name }
        val method = candidateMethods.firstOrNull { it.parameterTypes.size == withHandleArgs?.size }
            ?: candidateMethods.firstOrNull { it.parameterTypes.size == args.size }
            ?: throw IllegalStateException("SpeechEngine#$name not found")

        return if (method.parameterTypes.size == withHandleArgs?.size) {
            method.invoke(engineInstance, *withHandleArgs!!)
        } else {
            method.invoke(engineInstance, *args)
        }
    }

    private fun readStringConstant(name: String): String {
        return runCatching {
            definesClass.getField(name).get(null) as String
        }.getOrDefault("")
    }

    private fun readIntConstant(name: String, fallback: Int): Int {
        return runCatching {
            (definesClass.getField(name).get(null) as Number).toInt()
        }.getOrDefault(fallback)
    }

    private fun defaultValue(method: Method): Any? {
        return when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }
}
