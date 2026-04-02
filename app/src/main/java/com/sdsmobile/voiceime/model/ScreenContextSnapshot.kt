package com.sdsmobile.voiceime.model

data class ScreenContextSnapshot(
    val packageName: String,
    val focusedText: String,
    val visibleTexts: List<String>,
    val capturedAtMillis: Long,
) {
    fun toPromptText(maxChars: Int = 1200): String {
        val body = buildString {
            if (packageName.isNotBlank()) {
                append("当前应用：")
                append(packageName)
                append('\n')
            }
            if (focusedText.isNotBlank()) {
                append("焦点附近文本：")
                append(focusedText)
                append('\n')
            }
            if (visibleTexts.isNotEmpty()) {
                append("当前屏幕可见文本：\n")
                visibleTexts.forEach { line ->
                    append("- ")
                    append(line)
                    append('\n')
                }
            }
        }.trim()
        return body.take(maxChars)
    }
}
