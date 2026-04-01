package com.sdsmobile.voiceime.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sdsmobile.voiceime.R
import com.sdsmobile.voiceime.VoiceImeApplication
import com.sdsmobile.voiceime.model.AppSettings
import com.sdsmobile.voiceime.speech.DoubaoSpeechRecognizer
import com.sdsmobile.voiceime.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BubbleOverlayService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: BubbleView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var recognizer: DoubaoSpeechRecognizer? = null
    private var resetJob: Job? = null
    private var state: OverlayState = OverlayState.Idle

    private val appContainer by lazy {
        (application as VoiceImeApplication).appContainer
    }

    override fun onCreate() {
        super.onCreate()
        running.value = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        recognizer = DoubaoSpeechRecognizer(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        val shown = runCatching {
            showBubble()
        }.isSuccess
        if (!shown) {
            toast("请先开启悬浮窗权限")
            stopSelf()
            return
        }
        renderState(OverlayState.Idle)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        recognizer?.destroy()
        recognizer = null
        runCatching { windowManager.removeView(bubbleView) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        bubbleView = BubbleView(this).apply {
            setTapListener { onBubbleTap() }
            setLongPressListener { onBubbleLongPress() }
        }
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 48
            y = 240
        }
        attachDragBehavior()
        windowManager.addView(bubbleView, layoutParams)
    }

    private fun attachDragBehavior() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        bubbleView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = layoutParams.x
                    startY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - touchX).toInt()
                    val deltaY = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(deltaX) > touchSlop || kotlin.math.abs(deltaY) > touchSlop) {
                        layoutParams.x = startX + deltaX
                        layoutParams.y = startY + deltaY
                        windowManager.updateViewLayout(bubbleView, layoutParams)
                    }
                }
            }
            false
        }
    }

    private fun onBubbleTap() {
        when (state) {
            OverlayState.Idle, is OverlayState.Error -> startDictation()
            is OverlayState.Listening -> stopDictation()
            is OverlayState.Correcting -> toast("正在处理文本")
        }
    }

    private fun onBubbleLongPress() {
        if (state != OverlayState.Idle) {
            toast("请等待当前操作结束")
            return
        }
        if (!AccessibilityBridge.connected.value) {
            toast("请先开启无障碍服务")
            return
        }

        serviceScope.launch {
            renderState(OverlayState.Correcting("读取中"))
            val snapshot = AccessibilityBridge.snapshot()
            if (snapshot == null || snapshot.text.isBlank()) {
                renderTemporaryError("当前输入框没有可修正文本")
                return@launch
            }

            val settings = appContainer.settingsRepository.settings.first()
            if (!settings.isCorrectionConfigured()) {
                renderTemporaryError("请先配置火山方舟 API Key 和模型")
                return@launch
            }

            renderState(OverlayState.Correcting("修正中"))
            val corrected = runCatching {
                appContainer.arkTextCorrector.correctExistingText(snapshot.text, settings)
            }.getOrElse { error ->
                renderTemporaryError(error.message ?: "修正失败")
                return@launch
            }

            val replaced = AccessibilityBridge.replaceFocusedText(corrected)
            if (replaced) {
                renderTemporarySuccess("已修正")
            } else {
                renderTemporaryError("替换失败，请确认输入框支持无障碍编辑")
            }
        }
    }

    private fun startDictation() {
        if (!hasAudioPermission()) {
            renderTemporaryError("请先授权麦克风权限")
            return
        }

        serviceScope.launch {
            val settings = appContainer.settingsRepository.settings.first()
            if (!settings.isSpeechConfigured()) {
                renderTemporaryError("请先补全豆包语音配置")
                return@launch
            }

            runCatching {
                recognizer?.startListening(settings, recognitionCallback(settings))
            }.onFailure { error ->
                renderTemporaryError(error.message ?: "启动识别失败")
            }
        }
    }

    private fun stopDictation() {
        renderState(OverlayState.Correcting("收尾中"))
        recognizer?.finishTalking()
    }

    private fun recognitionCallback(settings: AppSettings): DoubaoSpeechRecognizer.Callback {
        return object : DoubaoSpeechRecognizer.Callback {
            override fun onReady() {
                renderState(OverlayState.Listening("录音中"))
            }

            override fun onPartialText(text: String) {
                renderState(OverlayState.Listening(text.takeLast(8)))
            }

            override fun onFinalText(text: String) {
                serviceScope.launch {
                    handleRecognizedText(text, settings)
                }
            }

            override fun onError(message: String) {
                recognizer?.destroy()
                renderTemporaryError(message)
            }
        }
    }

    private suspend fun handleRecognizedText(rawText: String, settings: AppSettings) {
        recognizer?.destroy()
        if (rawText.isBlank()) {
            renderTemporaryError("未识别到语音")
            return
        }

        renderState(OverlayState.Correcting("纠错中"))
        val finalText = if (settings.isCorrectionConfigured()) {
            runCatching {
                appContainer.arkTextCorrector.correctDictation(rawText, settings)
            }.getOrDefault(rawText)
        } else {
            rawText
        }

        val inserted = AccessibilityBridge.insertText(finalText)
        if (inserted) {
            renderTemporarySuccess("已输入")
        } else {
            copyToClipboard(finalText)
            renderTemporaryError("未找到可编辑输入框，结果已复制")
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("voice-ime", text))
    }

    private fun renderTemporarySuccess(label: String) {
        renderState(OverlayState.Correcting(label))
        scheduleReset()
    }

    private fun renderTemporaryError(label: String) {
        renderState(OverlayState.Error(label))
        scheduleReset()
    }

    private fun scheduleReset() {
        resetJob?.cancel()
        resetJob = serviceScope.launch {
            kotlinx.coroutines.delay(1200)
            renderState(OverlayState.Idle)
        }
    }

    private fun renderState(newState: OverlayState) {
        state = newState
        bubbleView.render(newState)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_voice_ime)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private sealed interface OverlayState {
        data object Idle : OverlayState
        data class Listening(val text: String) : OverlayState
        data class Correcting(val text: String) : OverlayState
        data class Error(val text: String) : OverlayState
    }

    private class BubbleView(context: Context) : LinearLayout(context) {
        private val bubble = TextView(context).apply {
            text = "语"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LayoutParams(dp(68), dp(68))
        }
        private val status = TextView(context).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

        private var tapListener: (() -> Unit)? = null
        private var longPressListener: (() -> Unit)? = null
        private val pressHandler = android.os.Handler(context.mainLooper)
        private var longPressed = false
        private var moved = false
        private var downX = 0f
        private var downY = 0f

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bubble)
            addView(status)
            clipChildren = false
            clipToPadding = false

            bubble.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressed = false
                        moved = false
                        downX = event.rawX
                        downY = event.rawY
                        pressHandler.postDelayed({
                            if (!moved) {
                                longPressed = true
                                longPressListener?.invoke()
                            }
                        }, ViewConfiguration.getLongPressTimeout().toLong())
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = kotlin.math.abs(event.rawX - downX)
                        val deltaY = kotlin.math.abs(event.rawY - downY)
                        if (deltaX > dp(6) || deltaY > dp(6)) {
                            moved = true
                            pressHandler.removeCallbacksAndMessages(null)
                        }
                    }

                    MotionEvent.ACTION_UP -> {
                        pressHandler.removeCallbacksAndMessages(null)
                        if (!moved && !longPressed) {
                            tapListener?.invoke()
                        }
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        pressHandler.removeCallbacksAndMessages(null)
                    }
                }
                false
            }
        }

        fun setTapListener(listener: () -> Unit) {
            tapListener = listener
        }

        fun setLongPressListener(listener: () -> Unit) {
            longPressListener = listener
        }

        fun render(state: OverlayState) {
            when (state) {
                OverlayState.Idle -> {
                    bubble.background = circleDrawable("#F97316")
                    status.background = pillDrawable("#1F2937")
                    status.text = "点按输入"
                }

                is OverlayState.Listening -> {
                    bubble.background = circleDrawable("#DC2626")
                    status.background = pillDrawable("#991B1B")
                    status.text = state.text.ifBlank { "录音中" }
                }

                is OverlayState.Correcting -> {
                    bubble.background = circleDrawable("#0F766E")
                    status.background = pillDrawable("#115E59")
                    status.text = state.text
                }

                is OverlayState.Error -> {
                    bubble.background = circleDrawable("#475569")
                    status.background = pillDrawable("#334155")
                    status.text = state.text
                }
            }
        }

        private fun circleDrawable(colorHex: String): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(colorHex))
            }
        }

        private fun pillDrawable(colorHex: String): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor(colorHex))
            }
        }

        private fun dp(value: Int): Int {
            return (value * resources.displayMetrics.density).toInt()
        }
    }

    companion object {
        private const val CHANNEL_ID = "voice_ime_overlay"
        private const val NOTIFICATION_ID = 42
        val running = MutableStateFlow(false)

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BubbleOverlayService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BubbleOverlayService::class.java))
        }
    }
}
