package com.sdsmobile.voiceime.service

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sdsmobile.voiceime.R
import com.sdsmobile.voiceime.VoiceImeApplication
import com.sdsmobile.voiceime.model.AppSettings
import com.sdsmobile.voiceime.speech.DoubaoSpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoiceInputMethodService : InputMethodService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContainer by lazy {
        (application as VoiceImeApplication).appContainer
    }
    private val prefs by lazy {
        getSharedPreferences("voice_ime_ui", MODE_PRIVATE)
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootView: FrameLayout
    private lateinit var bubbleContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var bubbleButton: TextView
    private var recognizer: DoubaoSpeechRecognizer? = null
    private var state: ImePanelState = ImePanelState.Idle()
    private var bubbleTranslationX = 0f
    private var bubbleTranslationY = 0f
    private var touchStartRawX = 0f
    private var touchStartRawY = 0f
    private var touchStartTranslationX = 0f
    private var touchStartTranslationY = 0f
    private var hasMovedDuringTouch = false
    private var hasLongPressed = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private val longPressRunnable = Runnable {
        if (!hasMovedDuringTouch) {
            hasLongPressed = true
            bubbleButton.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onBubbleLongPress()
        }
    }

    override fun onCreate() {
        super.onCreate()
        recognizer = DoubaoSpeechRecognizer(this)
        setBackDisposition(BACK_DISPOSITION_ADJUST_NOTHING)
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.view_voice_ime, null)
        rootView = root.findViewById(android.R.id.content) ?: root as FrameLayout
        bubbleContainer = root.findViewById(R.id.ime_bubble_container)
        statusView = root.findViewById(R.id.ime_status)
        bubbleButton = root.findViewById(R.id.ime_bubble_button)

        bubbleButton.setOnTouchListener { _, event ->
            handleBubbleTouch(event)
        }
        root.post {
            applySavedBubblePosition()
        }

        renderState(ImePanelState.Idle())
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        renderState(ImePanelState.Idle())
        rootView.post {
            applySavedBubblePosition()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        recognizer?.destroy()
        mainHandler.removeCallbacks(longPressRunnable)
        renderState(ImePanelState.Idle())
        super.onFinishInputView(finishingInput)
    }

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartRawX = event.rawX
                touchStartRawY = event.rawY
                touchStartTranslationX = bubbleTranslationX
                touchStartTranslationY = bubbleTranslationY
                hasMovedDuringTouch = false
                hasLongPressed = false
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - touchStartRawX
                val deltaY = event.rawY - touchStartRawY
                if (!hasMovedDuringTouch && (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop)) {
                    hasMovedDuringTouch = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (hasMovedDuringTouch) {
                    updateBubblePosition(
                        touchStartTranslationX + deltaX,
                        touchStartTranslationY + deltaY,
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (hasMovedDuringTouch) {
                    persistBubblePosition()
                } else if (!hasLongPressed) {
                    onPrimaryAction()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                return true
            }
        }
        return false
    }

    private fun onPrimaryAction() {
        when (state) {
            is ImePanelState.Idle, is ImePanelState.Error -> startDictation()
            is ImePanelState.Listening -> stopDictation()
            is ImePanelState.Processing -> renderState(ImePanelState.Error("正在处理，请稍候"))
        }
    }

    private fun onCorrectAction() {
        if (state !is ImePanelState.Idle && state !is ImePanelState.Error) {
            renderState(ImePanelState.Error("请等待当前操作结束"))
            return
        }

        val connection = currentInputConnection
        val snapshot = readCurrentEditorText(connection)
        if (snapshot == null || snapshot.text.isBlank()) {
            renderState(ImePanelState.Error("当前输入框没有可修正文本"))
            return
        }

        serviceScope.launch {
            val settings = appContainer.settingsRepository.settings.first()
            if (!settings.isCorrectionConfigured()) {
                renderState(ImePanelState.Error("请先配置方舟 API Key 和模型"))
                return@launch
            }

            renderState(ImePanelState.Processing("修正全文中"))
            val corrected = runCatching {
                appContainer.arkTextCorrector.correctExistingText(snapshot.text, settings)
            }.getOrElse { error ->
                renderState(ImePanelState.Error(error.message ?: "修正失败"))
                return@launch
            }

            val replaced = replaceAllText(connection, snapshot, corrected)
            if (replaced) {
                renderState(ImePanelState.Idle("已修正全文"))
            } else {
                renderState(ImePanelState.Error("当前输入框不支持整段替换"))
            }
        }
    }

    private fun onBubbleLongPress() {
        when (state) {
            is ImePanelState.Idle, is ImePanelState.Error -> onCorrectAction()
            is ImePanelState.Listening -> renderState(ImePanelState.Error("请先轻点结束录音"))
            is ImePanelState.Processing -> renderState(ImePanelState.Error("正在处理，请稍候"))
        }
    }

    private fun startDictation() {
        if (!hasAudioPermission()) {
            renderState(ImePanelState.Error("请先授权麦克风权限"))
            return
        }

        serviceScope.launch {
            val settings = appContainer.settingsRepository.settings.first()
            if (!settings.isSpeechConfigured()) {
                renderState(ImePanelState.Error("请先补全豆包语音配置"))
                return@launch
            }

            runCatching {
                recognizer?.startListening(settings, recognitionCallback(settings))
            }.onFailure { error ->
                recognizer?.destroy()
                renderState(ImePanelState.Error(error.message ?: "启动识别失败"))
            }
        }
    }

    private fun stopDictation() {
        renderState(ImePanelState.Processing("收尾中"))
        recognizer?.finishTalking()
    }

    private fun recognitionCallback(settings: AppSettings): DoubaoSpeechRecognizer.Callback {
        return object : DoubaoSpeechRecognizer.Callback {
            override fun onReady() {
                renderState(ImePanelState.Listening("录音中"))
            }

            override fun onPartialText(text: String) {
                renderState(ImePanelState.Listening(text.takeLast(14)))
            }

            override fun onFinalText(text: String) {
                serviceScope.launch {
                    handleRecognizedText(text, settings)
                }
            }

            override fun onError(message: String) {
                recognizer?.destroy()
                renderState(ImePanelState.Error(message))
            }
        }
    }

    private suspend fun handleRecognizedText(rawText: String, settings: AppSettings) {
        recognizer?.destroy()
        if (rawText.isBlank()) {
            renderState(ImePanelState.Error("未识别到语音"))
            return
        }

        renderState(ImePanelState.Processing("纠错中"))
        val finalText = if (settings.isCorrectionConfigured()) {
            runCatching {
                appContainer.arkTextCorrector.correctDictation(rawText, settings)
            }.getOrDefault(rawText)
        } else {
            rawText
        }

        val inserted = currentInputConnection?.commitText(finalText, 1) == true
        if (inserted) {
            renderState(ImePanelState.Idle("已输入"))
        } else {
            renderState(ImePanelState.Error("未找到可编辑输入框"))
        }
    }

    private fun readCurrentEditorText(connection: InputConnection?): EditorSnapshot? {
        connection ?: return null
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted?.text != null) {
            val text = extracted.text.toString()
            if (text.isNotBlank()) {
                return EditorSnapshot(
                    text = text,
                    selectionStart = 0,
                    selectionEnd = text.length,
                )
            }
        }

        val before = connection.getTextBeforeCursor(SURROUNDING_TEXT_LIMIT, 0)?.toString().orEmpty()
        val selected = connection.getSelectedText(0)?.toString().orEmpty()
        val after = connection.getTextAfterCursor(SURROUNDING_TEXT_LIMIT, 0)?.toString().orEmpty()
        val merged = before + selected + after
        if (merged.isBlank()) {
            return null
        }

        return EditorSnapshot(
            text = merged,
            selectionStart = before.length,
            selectionEnd = before.length + selected.length,
        )
    }

    private fun replaceAllText(
        connection: InputConnection?,
        snapshot: EditorSnapshot,
        corrected: String,
    ): Boolean {
        connection ?: return false
        connection.beginBatchEdit()
        return try {
            val selectionSet = connection.setSelection(snapshot.selectionStart, snapshot.selectionEnd)
            val selectedAll = if (snapshot.selectionStart == 0 && snapshot.selectionEnd == snapshot.text.length) {
                selectionSet
            } else {
                connection.setSelection(0, snapshot.text.length)
            }
            selectedAll && connection.commitText(corrected, 1)
        } finally {
            connection.endBatchEdit()
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun applySavedBubblePosition() {
        if (!::rootView.isInitialized || !::bubbleContainer.isInitialized) {
            return
        }
        val availableWidth = (rootView.width - rootView.paddingLeft - rootView.paddingRight - bubbleContainer.width).coerceAtLeast(0)
        val availableHeight = (rootView.height - rootView.paddingTop - rootView.paddingBottom - bubbleContainer.height).coerceAtLeast(0)
        val normalizedX = prefs.getFloat(KEY_BUBBLE_X, 1f)
        val normalizedY = prefs.getFloat(KEY_BUBBLE_Y, 1f)
        updateBubblePosition(availableWidth * normalizedX, availableHeight * normalizedY)
    }

    private fun updateBubblePosition(rawX: Float, rawY: Float) {
        val availableWidth = (rootView.width - rootView.paddingLeft - rootView.paddingRight - bubbleContainer.width).coerceAtLeast(0)
        val availableHeight = (rootView.height - rootView.paddingTop - rootView.paddingBottom - bubbleContainer.height).coerceAtLeast(0)
        bubbleTranslationX = rawX.coerceIn(0f, availableWidth.toFloat())
        bubbleTranslationY = rawY.coerceIn(0f, availableHeight.toFloat())
        bubbleContainer.translationX = bubbleTranslationX
        bubbleContainer.translationY = bubbleTranslationY
    }

    private fun persistBubblePosition() {
        val availableWidth = (rootView.width - rootView.paddingLeft - rootView.paddingRight - bubbleContainer.width).coerceAtLeast(1)
        val availableHeight = (rootView.height - rootView.paddingTop - rootView.paddingBottom - bubbleContainer.height).coerceAtLeast(1)
        prefs.edit()
            .putFloat(KEY_BUBBLE_X, bubbleTranslationX / availableWidth)
            .putFloat(KEY_BUBBLE_Y, bubbleTranslationY / availableHeight)
            .apply()
    }

    private fun renderState(newState: ImePanelState) {
        state = newState
        if (!::statusView.isInitialized) {
            return
        }

        when (newState) {
            is ImePanelState.Idle -> {
                statusView.visibility = View.GONE
                statusView.text = newState.message
                bubbleButton.text = ""
                bubbleButton.setBackgroundResource(R.drawable.bg_voice_ime_bubble_idle)
            }

            is ImePanelState.Listening -> {
                statusView.visibility = View.VISIBLE
                statusView.text = "录音中，轻点结束：${newState.label}"
                bubbleButton.text = ""
                bubbleButton.setBackgroundResource(R.drawable.bg_voice_ime_bubble_listening)
            }

            is ImePanelState.Processing -> {
                statusView.visibility = View.VISIBLE
                statusView.text = newState.label
                bubbleButton.text = ""
                bubbleButton.setBackgroundResource(R.drawable.bg_voice_ime_bubble_processing)
            }

            is ImePanelState.Error -> {
                statusView.visibility = View.VISIBLE
                statusView.text = "提示：${newState.message}"
                bubbleButton.text = ""
                bubbleButton.setBackgroundResource(R.drawable.bg_voice_ime_bubble_error)
            }
        }

        val busy = newState is ImePanelState.Processing
        bubbleButton.isEnabled = !busy
    }

    private data class EditorSnapshot(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    )

    private sealed interface ImePanelState {
        data class Idle(
            val message: String = "轻点开始说话，长按修正全文。",
        ) : ImePanelState
        data class Listening(val label: String) : ImePanelState
        data class Processing(val label: String) : ImePanelState
        data class Error(val message: String) : ImePanelState
    }

    companion object {
        private const val SURROUNDING_TEXT_LIMIT = 2000
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
    }
}
