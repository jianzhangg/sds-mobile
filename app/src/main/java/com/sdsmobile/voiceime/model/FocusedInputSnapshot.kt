package com.sdsmobile.voiceime.model

data class FocusedInputSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
)
