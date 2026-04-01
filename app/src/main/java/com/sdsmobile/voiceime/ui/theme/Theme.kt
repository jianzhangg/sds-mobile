package com.sdsmobile.voiceime.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Cream,
    secondary = Slate,
    background = Cream,
    surface = Cream,
    onSurface = Ink,
)

@Composable
fun SdsVoiceImeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
