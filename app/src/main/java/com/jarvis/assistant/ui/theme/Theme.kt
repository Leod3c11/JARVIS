package com.jarvis.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val JarvisColors = darkColorScheme(
    primary = Color(0xFF54C8FF),
    secondary = Color(0xFF75E6D1),
    background = Color(0xFF07111C),
    surface = Color(0xFF101F2D),
    onPrimary = Color(0xFF00131F),
    onBackground = Color(0xFFE6F4FF),
    onSurface = Color(0xFFE6F4FF)
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColors,
        content = content
    )
}
