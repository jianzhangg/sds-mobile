package com.sdsmobile.voiceime.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sdsmobile.voiceime.model.ScreenContextSnapshot
import java.util.LinkedHashSet

class ScreenContextAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 120
            flags =
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        refreshSnapshot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        refreshSnapshot()
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        latestSnapshot = null
        return super.onUnbind(intent)
    }

    private fun refreshSnapshot() {
        val root = rootInActiveWindow ?: return
        val visibleTexts = LinkedHashSet<String>()
        var focusedText = ""
        try {
            collectTexts(root, visibleTexts) { focused ->
                if (focusedText.isBlank()) {
                    focusedText = focused
                }
            }
            latestSnapshot = ScreenContextSnapshot(
                packageName = root.packageName?.toString().orEmpty(),
                focusedText = focusedText,
                visibleTexts = visibleTexts.take(MAX_VISIBLE_LINES),
                capturedAtMillis = System.currentTimeMillis(),
            )
        } finally {
            root.recycle()
        }
    }

    private fun collectTexts(
        node: AccessibilityNodeInfo,
        target: LinkedHashSet<String>,
        onFocusedText: (String) -> Unit,
    ) {
        if (target.size >= MAX_VISIBLE_LINES) {
            return
        }

        extractNodeTexts(node).forEach { text ->
            if (target.size < MAX_VISIBLE_LINES) {
                target += text
            }
            if (node.isFocused) {
                onFocusedText(text)
            }
        }

        val childCount = node.childCount
        for (index in 0 until childCount) {
            val child = node.getChild(index) ?: continue
            try {
                collectTexts(child, target, onFocusedText)
            } finally {
                child.recycle()
            }
        }
    }

    private fun extractNodeTexts(node: AccessibilityNodeInfo): List<String> {
        val results = mutableListOf<String>()
        fun addCandidate(value: CharSequence?) {
            val text = value?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                results += text
            }
        }

        addCandidate(node.text)
        addCandidate(node.contentDescription)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            addCandidate(node.hintText)
        }
        return results.distinct()
    }

    companion object {
        private const val MAX_VISIBLE_LINES = 32

        @Volatile
        private var latestSnapshot: ScreenContextSnapshot? = null

        fun latestSnapshot(): ScreenContextSnapshot? = latestSnapshot
    }
}
