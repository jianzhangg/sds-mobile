package com.sdsmobile.voiceime.service

import com.sdsmobile.voiceime.model.FocusedInputSnapshot
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessibilityBridge {
    private var serviceRef: WeakReference<VoiceInputAccessibilityService>? = null
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun attach(service: VoiceInputAccessibilityService) {
        serviceRef = WeakReference(service)
        _connected.value = true
    }

    fun detach(service: VoiceInputAccessibilityService) {
        if (serviceRef?.get() === service) {
            serviceRef?.clear()
            serviceRef = null
            _connected.value = false
        }
    }

    fun snapshot(): FocusedInputSnapshot? {
        return serviceRef?.get()?.getFocusedInputSnapshot()
    }

    fun insertText(text: String): Boolean {
        return serviceRef?.get()?.insertText(text) ?: false
    }

    fun replaceFocusedText(text: String): Boolean {
        return serviceRef?.get()?.replaceFocusedText(text) ?: false
    }
}
