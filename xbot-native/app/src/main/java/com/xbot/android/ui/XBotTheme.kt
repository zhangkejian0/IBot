package com.xbot.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 应用主题：暗色（与虚拟形象前端 #0A0A0A 暗背景一致）。
 */
private val XBotColorScheme = darkColorScheme(
    primary = Color(0xFF80C0FF),
    onPrimary = Color.Black,
    background = Color(0xFF0A0A0A),
    surface = Color(0xFF15151A),
    onSurface = Color(0xFFE0E0E0),
)

@Composable
fun XBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XBotColorScheme,
        content = content,
    )
}
