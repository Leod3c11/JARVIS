package com.jarvis.assistant.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class JarvisFaceState { IDLE, LISTENING, THINKING, SPEAKING }

@Composable
fun JarvisFace(state: JarvisFaceState, modifier: Modifier = Modifier, diameter: Dp = 116.dp) {
    val motion = rememberInfiniteTransition(label = "jarvis-face")
    val blink by motion.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 3400
        0f at 0
        0f at 2850
        1f at 2920
        0f at 3000
    }), label = "blink")
    val breathe by motion.animateFloat(0.94f, 1.04f,
        infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val thinking by motion.animateFloat(-1f, 1f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "thinking")
    val speaking by motion.animateFloat(0.15f, 1f,
        infiniteRepeatable(tween(170), RepeatMode.Reverse), label = "speaking")
    val accent = when (state) {
        JarvisFaceState.IDLE -> Color(0xFF55E6D0)
        JarvisFaceState.LISTENING -> Color(0xFF54C8FF)
        JarvisFaceState.THINKING -> Color(0xFF9B8CFF)
        JarvisFaceState.SPEAKING -> Color(0xFF55E6D0)
    }
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val u = size.minDimension / 116f
            val radius = 49f * u
            drawCircle(
                Brush.radialGradient(listOf(accent.copy(alpha = 0.18f), Color(0xFF0B1B28), Color(0xFF050D13)),
                    center = Offset(cx, cy), radius = radius * 1.5f),
                radius, Offset(cx, cy)
            )
            drawCircle(accent.copy(alpha = 0.20f), radius * 0.91f, Offset(cx, cy), style = Stroke(1.5f * u))
            drawCircle(accent.copy(alpha = 0.8f), radius * 0.82f, Offset(cx, cy), style = Stroke(1f * u))
            val eyeY = cy - 5f * u
            val eyeW = 15f * u
            val eyeH = (19f * u * (1f - blink * 0.94f)).coerceAtLeast(1.4f * u)
            val pupilShift = if (state == JarvisFaceState.THINKING) thinking * 3.5f * u else 0f
            listOf(cx - 19f * u, cx + 19f * u).forEach { eyeX ->
                drawRoundRect(
                    Brush.verticalGradient(listOf(accent, accent.copy(alpha = 0.55f))),
                    Offset(eyeX - eyeW / 2f, eyeY - eyeH / 2f),
                    Size(eyeW, eyeH),
                    androidx.compose.ui.geometry.CornerRadius(eyeW / 2f)
                )
                if (blink < 0.8f) drawCircle(Color(0xFF07131C), 3.4f * u, Offset(eyeX + pupilShift, eyeY))
            }
            val mouthY = cy + 23f * u
            when (state) {
                JarvisFaceState.IDLE -> drawArc(accent, 25f, 130f, false,
                    Offset(cx - 10f * u, mouthY - 5f * u), Size(20f * u, 10f * u),
                    style = Stroke(2f * u, cap = StrokeCap.Round))
                JarvisFaceState.LISTENING -> drawRoundRect(accent,
                    Offset(cx - 6f * u, mouthY - 1.5f * u), Size(12f * u, 3f * u),
                    androidx.compose.ui.geometry.CornerRadius(2f * u))
                JarvisFaceState.THINKING -> drawArc(accent, 205f, 130f, false,
                    Offset(cx - 8f * u, mouthY - 1f * u), Size(16f * u, 8f * u),
                    style = Stroke(2f * u, cap = StrokeCap.Round))
                JarvisFaceState.SPEAKING -> {
                    val h = (3f + speaking * 8f) * u
                    drawRoundRect(accent, Offset(cx - 6f * u, mouthY - h / 2f),
                        Size(12f * u, h), androidx.compose.ui.geometry.CornerRadius(6f * u))
                }
            }
            if (state == JarvisFaceState.THINKING) {
                drawCircle(accent.copy(alpha = 0.7f), 2f * u,
                    Offset(cx, cy - 43f * u + (1f - breathe) * 20f * u))
            }
        }
    }
}
