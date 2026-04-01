package com.sdsmobile.voiceime.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sdsmobile.voiceime.model.FocusedInputSnapshot
import kotlin.math.max
import kotlin.math.min

class VoiceInputAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun getFocusedInputSnapshot(): FocusedInputSnapshot? {
        val node = findFocusedEditableNode() ?: return null
        val text = node.text?.toString().orEmpty()
        val start = node.textSelectionStart.takeIf { it >= 0 } ?: text.length
        val end = node.textSelectionEnd.takeIf { it >= 0 } ?: text.length
        return FocusedInputSnapshot(text = text, selectionStart = start, selectionEnd = end)
    }

    fun insertText(text: String): Boolean {
        val node = findFocusedEditableNode() ?: return false
        val snapshot = getFocusedInputSnapshot() ?: return false
        val start = max(0, min(snapshot.selectionStart, snapshot.text.length))
        val end = max(0, min(snapshot.selectionEnd, snapshot.text.length))
        val newText = buildString {
            append(snapshot.text.substring(0, min(start, end)))
            append(text)
            append(snapshot.text.substring(max(start, end)))
        }
        return setNodeText(node, newText)
    }

    fun replaceFocusedText(text: String): Boolean {
        val node = findFocusedEditableNode() ?: return false
        return setNodeText(node, text)
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            return focusedNode
        }
        return findEditableNode(rootInActiveWindow)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) {
            return null
        }
        if (node.isFocused && node.isEditable) {
            return node
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index)
            val match = findEditableNode(child)
            if (match != null) {
                return match
            }
        }
        return null
    }
}
