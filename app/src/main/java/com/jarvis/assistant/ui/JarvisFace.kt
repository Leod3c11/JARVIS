package com.jarvis.assistant.ui

import android.graphics.Paint
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sin

enum class JarvisFaceState { IDLE, LISTENING, THINKING, SPEAKING }

@Composable
fun JarvisFace(state: JarvisFaceState, modifier: Modifier = Modifier, diameter: Dp = 220.dp) {
    val motion = rememberInfiniteTransition(label = "glyph-face")
    val blink by motion.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 3600
        0f at 0
        0f at 3050
        1f at 3120
        0f at 3200
    }), label = "blink")
    val drift by motion.animateFloat(-1f, 1f,
        infiniteRepeatable(tween(if (state == JarvisFaceState.THINKING) 700 else 2600), RepeatMode.Reverse),
        label = "drift")
    val pulse by motion.animateFloat(0.72f, 1f,
        infiniteRepeatable(tween(if (state == JarvisFaceState.SPEAKING) 180 else 950), RepeatMode.Reverse),
        label = "pulse")

    val color = when (state) {
        JarvisFaceState.IDLE -> android.graphics.Color.rgb(78, 235, 218)
        JarvisFaceState.LISTENING -> android.graphics.Color.rgb(80, 190, 255)
        JarvisFaceState.THINKING -> android.graphics.Color.rgb(172, 145, 255)
        JarvisFaceState.SPEAKING -> android.graphics.Color.rgb(95, 255, 222)
    }

    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val w = size.width
            val h = size.height
            val unit = size.minDimension / 220f
            val cx = w / 2f
            val cy = h / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = 8.2f * unit
                color = color
            }
            val glyphs = "01アイ#%@$*+=<>/\\[]{}:;ZXCV"
            val cols = 25
            val rows = 23
            val cellW = w / (cols + 1f)
            val cellH = h / (rows + 1f)
            val eyeY = cy - 17f * unit
            val eyeSpacing = 29f * unit
            val eyeOpen = (1f - blink * 0.92f).coerceAtLeast(0.08f)
            val phase = drift * 0.7f
            val headRx = 82f * unit
            val headRy = 96f * unit

            // Sparse character silhouette: the density follows a human-like head contour.
            drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            for (row in 0 until rows) {
                val y = (row + 1) * cellH
                for (col in 0 until cols) {
                    val x = (col + 1) * cellW
                    val nx = (x - cx) / headRx
                    val ny = (y - (cy + 1f * unit)) / headRy
                    val d = nx * nx + ny * ny
                    if (d > 1.12f) continue

                    val eyeRegion = y in (eyeY - 8f * unit)..(eyeY + 8f * unit) &&
                        abs(x - cx) in (eyeSpacing - 12f * unit)..(eyeSpacing + 12f * unit)
                    val mouthRegion = y in (cy + 37f * unit)..(cy + 58f * unit) &&
                        abs(x - cx) < 25f * unit
                    val browRegion = y in (cy - 42f * unit)..(cy - 31f * unit) &&
                        abs(x - cx) in (eyeSpacing - 15f * unit)..(eyeSpacing + 15f * unit)

                    val wave = (sin((row * 0.62f + col * 0.31f + phase).toDouble()) * 0.5 + 0.5).toFloat()
                    val edgeFade = ((1.12f - d) / 0.42f).coerceIn(0.15f, 1f)
                    val alpha = when {
                        eyeRegion -> if (eyeOpen < 0.2f) 0.25f else 0.98f
                        mouthRegion && state == JarvisFaceState.SPEAKING -> 0.95f * pulse
                        browRegion && state == JarvisFaceState.THINKING -> 0.9f
                        else -> (0.16f + wave * 0.46f) * edgeFade
                    }
                    paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
                    val glyphIndex = (row * 7 + col * 11 + (if (state == JarvisFaceState.THINKING) (drift * 4).toInt() else 0))
                        .let { abs(it) % glyphs.length }
                    val glyph = when {
                        eyeRegion -> if (eyeOpen < 0.2f) "—" else "◉"
                        mouthRegion && state == JarvisFaceState.SPEAKING -> if (pulse > 0.85f) "口" else "▄"
                        mouthRegion -> "·"
                        else -> glyphs[glyphIndex].toString()
                    }
                    val xOffset = if (state == JarvisFaceState.THINKING && !eyeRegion) drift * 1.3f * unit else 0f
                    native.drawText(glyph, x + xOffset, y, paint)
                }
            }

            // Bright eye cores and a changing mouth make the expression readable at small sizes.
            paint.textSize = 13f * unit
            paint.alpha = 245
            paint.textAlign = Paint.Align.CENTER
            val eyeGlyph = if (blink > 0.5f) "—" else if (state == JarvisFaceState.THINKING) "◉" else "●"
            native.drawText(eyeGlyph, cx - eyeSpacing, eyeY + 4f * unit, paint)
            native.drawText(eyeGlyph, cx + eyeSpacing, eyeY + 4f * unit, paint)
            paint.textSize = 12f * unit
            val mouth = when (state) {
                JarvisFaceState.IDLE -> "⌣"
                JarvisFaceState.LISTENING -> "◇"
                JarvisFaceState.THINKING -> "﹏"
                JarvisFaceState.SPEAKING -> if (pulse > 0.85f) "口" else "﹀"
            }
            native.drawText(mouth, cx, cy + 57f * unit, paint)
            native.restore()
            }
        }
    }
}
