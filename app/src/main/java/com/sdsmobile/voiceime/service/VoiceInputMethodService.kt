package com.sdsmobile.voiceime.service

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
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
import kotlin.math.abs

class VoiceInputMethodService : InputMethodService() {
    private val windowManager by lazy {
        getSystemService(WindowManager::class.java)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val appContainer by lazy {
        (application as VoiceImeApplication).appContainer
    }
    private val prefs by lazy {
        getSharedPreferences("voice_ime_ui", MODE_PRIVATE)
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var rootView: FrameLayout
    private lateinit var bubbleButton: TextView
    private var popupContentView: View? = null
    private var bubblePopup: PopupWindow? = null
    private var recognizer: DoubaoSpeechRecognizer? = null
    private var state: ImePanelState = ImePanelState.Idle()
    private var bubblePositionX = 0f
    private var bubblePositionY = 0f
    private var touchStartRawX = 0f
    private var touchStartRawY = 0f
    private var touchStartBubbleX = 0f
    private var touchStartBubbleY = 0f
    private var hasMovedDuringTouch = false
    private var hasLongPressed = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private val dragStartSlop by lazy { touchSlop * 2.5f }
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
        dismissBubblePopup()
        recognizer?.destroy()
        recognizer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        rootView = layoutInflater.inflate(R.layout.view_voice_ime, null) as FrameLayout
        return rootView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        renderState(ImePanelState.Idle())
        rootView.post {
            showBubblePopupIfNeeded()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        recognizer?.destroy()
        mainHandler.removeCallbacks(longPressRunnable)
        dismissBubblePopup()
        renderState(ImePanelState.Idle())
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        dismissBubblePopup()
        super.onWindowHidden()
    }

    private fun handleBubbleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartRawX = event.rawX
                touchStartRawY = event.rawY
                touchStartBubbleX = bubblePositionX
                touchStartBubbleY = bubblePositionY
                hasMovedDuringTouch = false
                hasLongPressed = false
                mainHandler.removeCallbacks(longPressRunnable)
                mainHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - touchStartRawX
                val deltaY = event.rawY - touchStartRawY
                if (!hasMovedDuringTouch && (abs(deltaX) > dragStartSlop || abs(deltaY) > dragStartSlop)) {
                    hasMovedDuringTouch = true
                    mainHandler.removeCallbacks(longPressRunnable)
                }
                if (hasMovedDuringTouch) {
                    updateBubblePosition(
                        touchStartBubbleX + deltaX,
                        touchStartBubbleY + deltaY,
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
            renderState(ImePanelState.Error("当前输入框没有可修正文案"))
            return
        }

        serviceScope.launch {
            val settings = appContainer.settingsRepository.settings.first()
            if (!settings.isCorrectionConfigured()) {
                renderState(ImePanelState.Error("请先配置方舟 API Key 和模型"))
                return@launch
            }

            renderState(ImePanelState.Processing("优化中"))
            val corrected = runCatching {
                appContainer.arkTextCorrector.correctExistingText(snapshot.text, settings)
            }.getOrElse { error ->
                renderState(ImePanelState.Error(error.message ?: "优化失败"))
                return@launch
            }

            val replaced = replaceAllText(connection, snapshot, corrected)
            if (replaced) {
                renderState(ImePanelState.Idle("已优化全文"))
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
        renderState(ImePanelState.Processing("识别中"))
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

        renderState(ImePanelState.Processing("优化中"))
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
                val selectionStart = extracted.selectionStart.coerceIn(0, text.length)
                val selectionEnd = extracted.selectionEnd.coerceIn(0, text.length)
                return EditorSnapshot(
                    text = text,
                    selectionStart = minOf(selectionStart, selectionEnd),
                    selectionEnd = maxOf(selectionStart, selectionEnd),
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
            connection.finishComposingText()

            if (tryReplaceViaSelectAll(connection, corrected)) {
                return true
            }

            if (tryReplaceViaExplicitSelection(connection, snapshot.text.length, corrected)) {
                return true
            }

            if (tryReplaceViaDeleteFromEnd(connection, snapshot.text.length, corrected)) {
                return true
            }

            if (tryReplaceViaDeleteFromStart(connection, snapshot.text.length, corrected)) {
                return true
            }

            false
        } finally {
            connection.endBatchEdit()
        }
    }

    private fun tryReplaceViaSelectAll(
        connection: InputConnection,
        corrected: String,
    ): Boolean {
        return connection.performContextMenuAction(android.R.id.selectAll) &&
            connection.commitText(corrected, 1)
    }

    private fun tryReplaceViaExplicitSelection(
        connection: InputConnection,
        textLength: Int,
        corrected: String,
    ): Boolean {
        if (textLength <= 0) {
            return connection.commitText(corrected, 1)
        }
        return connection.setSelection(0, textLength) &&
            connection.commitText(corrected, 1)
    }

    private fun tryReplaceViaDeleteFromEnd(
        connection: InputConnection,
        textLength: Int,
        corrected: String,
    ): Boolean {
        if (textLength <= 0) {
            return connection.commitText(corrected, 1)
        }
        return connection.setSelection(textLength, textLength) &&
            connection.deleteSurroundingText(textLength, 0) &&
            connection.commitText(corrected, 1)
    }

    private fun tryReplaceViaDeleteFromStart(
        connection: InputConnection,
        textLength: Int,
        corrected: String,
    ): Boolean {
        if (textLength <= 0) {
            return connection.commitText(corrected, 1)
        }
        return connection.setSelection(0, 0) &&
            connection.deleteSurroundingText(0, textLength) &&
            connection.commitText(corrected, 1)
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun showBubblePopupIfNeeded() {
        if (!::rootView.isInitialized) {
            return
        }
        val parentView = window?.window?.decorView ?: rootView
        if (parentView.windowToken == null) {
            return
        }
        ensureBubblePopup()
        maybeMigrateBubblePosition()
        measurePopupContent()
        val (savedX, savedY) = computeSavedBubblePosition()
        bubblePositionX = savedX
        bubblePositionY = savedY
        val popup = bubblePopup ?: return
        if (popup.isShowing) {
            popup.update(savedX.toInt(), savedY.toInt(), -1, -1)
        } else {
            popup.showAtLocation(parentView, Gravity.TOP or Gravity.START, savedX.toInt(), savedY.toInt())
        }
        renderState(state)
    }

    private fun dismissBubblePopup() {
        bubblePopup?.dismiss()
    }

    private fun ensureBubblePopup() {
        if (bubblePopup != null && popupContentView != null) {
            return
        }

        val contentView = layoutInflater.inflate(R.layout.view_voice_ime_overlay, null)
        popupContentView = contentView
        bubbleButton = contentView.findViewById(R.id.ime_bubble_button)
        bubbleButton.setOnTouchListener { _, event ->
            handleBubbleTouch(event)
        }

        bubblePopup = PopupWindow(
            contentView,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isTouchable = true
            isFocusable = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            setAnimationStyle(0)
            setSplitTouchEnabled(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                setAttachedInDecor(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setIsLaidOutInScreen(true)
                setTouchModal(false)
            }
        }
    }

    private fun measurePopupContent(): Pair<Int, Int> {
        val contentView = popupContentView ?: return dpToPx(BUBBLE_SIZE_DP) to dpToPx(BUBBLE_SIZE_DP)
        contentView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val width = contentView.measuredWidth.takeIf { it > 0 } ?: dpToPx(BUBBLE_SIZE_DP)
        val height = contentView.measuredHeight.takeIf { it > 0 } ?: dpToPx(BUBBLE_SIZE_DP)
        return width to height
    }

    private fun computeSavedBubblePosition(): Pair<Float, Float> {
        val screenBounds = getAvailableScreenBounds()
        val (contentWidth, contentHeight) = measurePopupContent()
        val minX = screenBounds.left.toFloat()
        val minY = screenBounds.top.toFloat()
        val maxX = (screenBounds.right - contentWidth).coerceAtLeast(screenBounds.left).toFloat()
        val maxY = (screenBounds.bottom - contentHeight).coerceAtLeast(screenBounds.top).toFloat()
        val normalizedX = prefs.getFloat(KEY_BUBBLE_X, 1f)
        val normalizedY = prefs.getFloat(KEY_BUBBLE_Y, 1f)
        val savedX = minX + (maxX - minX) * normalizedX
        val savedY = minY + (maxY - minY) * normalizedY
        return savedX to savedY
    }

    private fun getAvailableScreenBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            Rect(
                metrics.bounds.left + insets.left + dpToPx(BUBBLE_MARGIN_DP),
                metrics.bounds.top + insets.top + dpToPx(BUBBLE_MARGIN_DP),
                metrics.bounds.right - insets.right - dpToPx(BUBBLE_MARGIN_DP),
                metrics.bounds.bottom - insets.bottom - dpToPx(BUBBLE_MARGIN_DP),
            )
        } else {
            val metrics = resources.displayMetrics
            Rect(
                dpToPx(BUBBLE_MARGIN_DP),
                dpToPx(BUBBLE_MARGIN_DP),
                metrics.widthPixels - dpToPx(BUBBLE_MARGIN_DP),
                metrics.heightPixels - dpToPx(BUBBLE_MARGIN_DP),
            )
        }
    }

    private fun updateBubblePosition(rawX: Float, rawY: Float) {
        val popup = bubblePopup ?: return
        val screenBounds = getAvailableScreenBounds()
        val (contentWidth, contentHeight) = measurePopupContent()
        val minX = screenBounds.left.toFloat()
        val minY = screenBounds.top.toFloat()
        val maxX = (screenBounds.right - contentWidth).coerceAtLeast(screenBounds.left).toFloat()
        val maxY = (screenBounds.bottom - contentHeight).coerceAtLeast(screenBounds.top).toFloat()
        bubblePositionX = rawX.coerceIn(minX, maxX)
        bubblePositionY = rawY.coerceIn(minY, maxY)
        if (popup.isShowing) {
            popup.update(bubblePositionX.toInt(), bubblePositionY.toInt(), -1, -1)
        }
    }

    private fun persistBubblePosition() {
        val screenBounds = getAvailableScreenBounds()
        val (contentWidth, contentHeight) = measurePopupContent()
        val minX = screenBounds.left.toFloat()
        val minY = screenBounds.top.toFloat()
        val maxX = (screenBounds.right - contentWidth).coerceAtLeast(screenBounds.left).toFloat()
        val maxY = (screenBounds.bottom - contentHeight).coerceAtLeast(screenBounds.top).toFloat()
        val normalizedX = if (maxX == minX) {
            1f
        } else {
            (bubblePositionX - minX) / (maxX - minX)
        }
        val normalizedY = if (maxY == minY) {
            1f
        } else {
            (bubblePositionY - minY) / (maxY - minY)
        }
        prefs.edit()
            .putFloat(KEY_BUBBLE_X, normalizedX.coerceIn(0f, 1f))
            .putFloat(KEY_BUBBLE_Y, normalizedY.coerceIn(0f, 1f))
            .putInt(KEY_BUBBLE_LAYOUT_VERSION, BUBBLE_LAYOUT_VERSION)
            .apply()
    }

    private fun maybeMigrateBubblePosition() {
        val currentVersion = prefs.getInt(KEY_BUBBLE_LAYOUT_VERSION, 0)
        if (currentVersion == BUBBLE_LAYOUT_VERSION) {
            return
        }
        prefs.edit()
            .putFloat(KEY_BUBBLE_X, 1f)
            .putFloat(KEY_BUBBLE_Y, 1f)
            .putInt(KEY_BUBBLE_LAYOUT_VERSION, BUBBLE_LAYOUT_VERSION)
            .apply()
    }

    private fun showBubbleMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun renderState(newState: ImePanelState) {
        val previousState = state
        state = newState
        if (!::bubbleButton.isInitialized) {
            return
        }

        when (newState) {
            is ImePanelState.Idle -> {
                bubbleButton.text = ""
                bubbleButton.setBackgroundResource(R.drawable.bg_voice_ime_bubble_idle)
                if (newState.message != DEFAULT_IDLE_MESSAGE && previousState != newState) {
                    showBubbleMessage(newState.message)
                }
            }

            is ImePanelState.Listening -> {
                bubbleButton.text = "录音中"
                bubbleButton.setBackgroundResource(R.drawable.bg_voice_ime_bubble_idle)
            }

            is ImePanelState.Processing -> {
                bubbleButton.text = newState.label
                bubbleButton.setBackgroundResource(R.drawable.bg_voice_ime_bubble_idle)
            }

            is ImePanelState.Error -> {
                bubbleButton.text = ""
                bubbleButton.setBackgroundResource(R.drawable.bg_voice_ime_bubble_idle)
                if (previousState != newState) {
                    showBubbleMessage(newState.message)
                }
            }
        }

        bubbleButton.isEnabled = true
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private data class EditorSnapshot(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    )

    private sealed interface ImePanelState {
        data class Idle(
            val message: String = DEFAULT_IDLE_MESSAGE,
        ) : ImePanelState
        data class Listening(val label: String) : ImePanelState
        data class Processing(val label: String) : ImePanelState
        data class Error(val message: String) : ImePanelState
    }

    companion object {
        private const val SURROUNDING_TEXT_LIMIT = 2000
        private const val KEY_BUBBLE_X = "bubble_x"
        private const val KEY_BUBBLE_Y = "bubble_y"
        private const val KEY_BUBBLE_LAYOUT_VERSION = "bubble_layout_version"
        private const val BUBBLE_LAYOUT_VERSION = 3
        private const val BUBBLE_SIZE_DP = 72
        private const val BUBBLE_MARGIN_DP = 16
        private const val DEFAULT_IDLE_MESSAGE = "轻点开始说话，长按修正全文。"
    }
}
