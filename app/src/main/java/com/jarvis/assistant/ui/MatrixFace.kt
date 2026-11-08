package com.jarvis.assistant.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Estado emocional/operacional exibido pelo rosto. */
enum class FaceMode { IDLE, LISTENING, THINKING, SPEAKING }

private const val GLYPHS = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾗﾘﾜ0123456789:=*+<>|"

// Pré-alocado: evita criar uma String por célula a cada quadro.
private val GLYPH_STRINGS: Array<String> = Array(GLYPHS.length) { GLYPHS[it].toString() }
private val SIDES = intArrayOf(-1, 1)

/** Estado de animação que não deve causar recomposição. */
private class FaceAnim {
    var nextBlink = 2.5f
    var blinkStart = -1f
    var lastPulse = 0
    var lastPulseTime = -10f
    var touchX = 0f
    var touchY = 0f
    var touching = false
    var lastFrame = 0L
}

/** Parâmetros geométricos do rosto em um instante. Coordenadas normalizadas (cabeça ≈ ±0.6 x ±0.88). */
private class FaceParams {
    var eyeOpen = 1f
    var gazeX = 0f
    var gazeY = 0f
    var mouthOpen = 0f
    var smile = 0.4f
    var browLift = 0f
    var browTilt = 0f
    var scan = -1f // < 0 desativa a faixa de varredura
}

private fun sq(v: Float) = v * v

/** Intensidade (0..1) do rosto no ponto (x, y). */
private fun faceField(x: Float, y: Float, p: FaceParams): Float {
    var v = 0f

    // Cabeça: preenchimento tênue + contorno forte.
    val head = sq(x / 0.60f) + sq(y / 0.88f)
    if (head <= 1f) v = 0.10f
    if (abs(head - 1f) < 0.10f) v = max(v, 0.70f)

    for (side in SIDES) {
        val ex = side * 0.27f
        val ey = -0.20f

        // Olho (altura depende do piscar).
        val eyeRy = 0.014f + 0.075f * p.eyeOpen
        if (sq((x - ex) / 0.15f) + sq((y - ey) / eyeRy) <= 1f) v = max(v, 0.55f)

        // Pupila segue o olhar.
        if (p.eyeOpen > 0.3f) {
            val px = ex + p.gazeX * 0.06f
            val py = ey + p.gazeY * 0.035f
            if (sq((x - px) / 0.05f) + sq((y - py) / 0.05f) <= 1f) v = 1f
        }

        // Sobrancelha: inclinação "franzida" aproxima a ponta interna do olho.
        if (abs(x - ex) < 0.16f) {
            val browY = ey - 0.17f - p.browLift * 0.06f + p.browTilt * (0.27f - abs(x)) * 0.35f
            if (abs(y - browY) < 0.016f) v = max(v, 0.60f)
        }
    }

    // Nariz.
    if (abs(x) < 0.012f && y > 0.0f && y < 0.16f) v = max(v, 0.35f)

    // Boca: curva de sorriso + abertura proporcional à fala.
    val dx = x / 0.22f
    if (abs(dx) <= 1f) {
        val mouthY = 0.45f - p.smile * 0.03f * dx * dx
        val thickness = 0.014f + 0.075f * p.mouthOpen * sqrt(max(0f, 1f - dx * dx))
        if (abs(y - mouthY) <= thickness) v = max(v, 0.85f)
    }

    // Faixa de varredura (modo pensando).
    if (p.scan >= 0f && head <= 1.15f) {
        val scanY = -0.9f + 1.8f * p.scan
        if (abs(y - scanY) < 0.05f) v = max(v, 0.55f)
    }
    return v
}

private fun cellHash(a: Int, b: Int, c: Int): Int {
    var h = a * 73856093 xor b * 19349663 xor c * 83492791
    h = h xor (h ushr 13)
    h *= 0x5bd1e995
    return h xor (h ushr 15)
}

private fun mix(a: Int, b: Int, t: Float): Int {
    val r = (((a shr 16) and 0xFF) * (1 - t) + ((b shr 16) and 0xFF) * t).toInt()
    val g = (((a shr 8) and 0xFF) * (1 - t) + ((b shr 8) and 0xFF) * t).toInt()
    val bl = ((a and 0xFF) * (1 - t) + (b and 0xFF) * t).toInt()
    return (r shl 16) or (g shl 8) or bl
}

/**
 * Rosto formado por "chuva de código" estilo Matrix.
 *
 * - Pisca em intervalos aleatórios e o olhar acompanha o toque/arraste.
 * - A boca se move em sincronia com os tokens (`speechPulse` incrementa a cada token).
 * - [mode] muda cor, sobrancelhas, varredura e intensidade.
 * - Toque simples dispara [onTap].
 */
@Composable
fun MatrixFace(
    modifier: Modifier = Modifier,
    mode: FaceMode,
    speechPulse: Int,
    onTap: () -> Unit = {}
) {
    val params = remember { FaceParams() }
    var time by remember { mutableFloatStateOf(0f) }
    val currentMode by rememberUpdatedState(mode)
    val currentPulse by rememberUpdatedState(speechPulse)
    val density = LocalDensity.current
    val cellPx = with(density) { 11.dp.toPx() }

    val anim = remember { FaceAnim() }

    val paint = remember {
        android.graphics.Paint().apply {
            typeface = android.graphics.Typeface.MONOSPACE
            isAntiAlias = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                if (anim.lastFrame != 0L) time += ((now - anim.lastFrame) / 1_000_000_000f).coerceAtMost(0.1f)
                anim.lastFrame = now
            }
        }
    }

    Canvas(
        modifier
            // Rastreia o dedo (toque e arraste) para o olhar acompanhar.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull()
                        if (change != null && change.pressed) {
                            anim.touching = true
                            anim.touchX = ((change.position.x / size.width) - 0.5f) * 2f
                            anim.touchY = ((change.position.y / size.height) - 0.5f) * 2f
                        } else {
                            anim.touching = false
                        }
                    }
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
    ) {
        val t = time
        val w = size.width
        val h = size.height
        val cols = max(1, (w / cellPx).toInt())
        val rows = max(1, (h / cellPx).toInt())
        val m = currentMode

        // --- Atualiza parâmetros do rosto ---
        if (currentPulse != anim.lastPulse) {
            anim.lastPulse = currentPulse
            anim.lastPulseTime = t
        }
        val sinceToken = t - anim.lastPulseTime
        val talking = m == FaceMode.SPEAKING && sinceToken < 0.6f
        val energy = if (talking) 1f - sinceToken / 0.6f else 0f
        params.mouthOpen = if (talking) energy * (0.5f + 0.5f * sin(t * 16f)) else 0f

        if (anim.blinkStart < 0f && t >= anim.nextBlink) anim.blinkStart = t
        var blink = 1f
        if (anim.blinkStart >= 0f) {
            val k = (t - anim.blinkStart) / 0.18f
            if (k >= 1f) {
                anim.blinkStart = -1f
                anim.nextBlink = t + 2.5f + (cellHash((t * 100).toInt(), 7, 3) and 0xFF) / 255f * 3.5f
            } else blink = abs(cos(k * PI.toFloat() / 2f)).coerceIn(0.05f, 1f)
        }
        val wide = if (m == FaceMode.LISTENING) 1.0f else 0.85f
        params.eyeOpen = blink * wide

        // Olhar: segue o toque; sem toque, vagueia suavemente.
        val targetX = if (anim.touching) anim.touchX.coerceIn(-1f, 1f) else sin(t * 0.7f) * 0.5f
        val targetY = if (anim.touching) anim.touchY.coerceIn(-1f, 1f) else sin(t * 0.43f + 1f) * 0.25f
        params.gazeX += (targetX - params.gazeX) * 0.15f
        params.gazeY += (targetY - params.gazeY) * 0.15f

        params.browLift = when (m) { FaceMode.LISTENING -> 0.9f; FaceMode.SPEAKING -> 0.3f; else -> 0f }
        params.browTilt = if (m == FaceMode.THINKING) 1f else 0f
        params.smile = if (m == FaceMode.THINKING) 0f else 0.45f
        params.scan = if (m == FaceMode.THINKING) (t * 0.8f) % 1f else -1f

        val accent = when (m) {
            FaceMode.IDLE -> 0x55E6D0
            FaceMode.LISTENING -> 0x54C8FF
            FaceMode.THINKING -> 0xFFD36B
            FaceMode.SPEAKING -> 0xB8FFF2
        }
        val rainColor = 0x1FA77F
        val faceBoost = if (m == FaceMode.IDLE) 0.85f else 1f

        // --- Desenha a grade de glifos ---
        paint.textSize = cellPx * 0.95f
        val faceScale = min(w * 0.5f / 0.78f, h * 0.5f / 1.0f)
        val cx = w / 2f
        val cy = h / 2f
        val tail = 9f
        val tick = (t * 5f).toInt()

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (c in 0 until cols) {
                val speed = 4f + (cellHash(c, 1, 1) and 0xFF) / 255f * 6f
                val seed = (cellHash(c, 2, 2) and 0xFFFF) / 65535f * (rows + tail)
                val head = (t * speed + seed) % (rows + tail)
                for (r in 0 until rows) {
                    val px = c * cellPx + cellPx / 2f
                    val py = r * cellPx + cellPx
                    val fx = (px - cx) / faceScale
                    val fy = (py - cellPx / 2f - cy) / faceScale

                    val d = head - r
                    val rain = if (d in 0f..tail) (1f - d / tail) * 0.45f else 0f
                    val face = faceField(fx, fy, params) * faceBoost
                    val intensity = max(rain, face)
                    if (intensity < 0.05f) continue

                    val flicker = 0.85f + 0.15f * ((cellHash(c, r, tick) and 0xFF) / 255f)
                    val glyph = GLYPH_STRINGS[(cellHash(c, r, (t * 3f + c * 0.37f).toInt()) and 0x7FFFFFFF) % GLYPH_STRINGS.size]
                    val color = if (face > rain) mix(rainColor, accent, ((face - 0.1f) / 0.9f).coerceIn(0f, 1f)) else rainColor
                    paint.color = (0xFF shl 24) or color
                    paint.alpha = (intensity * flicker * 255f).toInt().coerceIn(0, 255)
                    native.drawText(glyph, px - cellPx * 0.4f, py, paint)
                }
            }
        }
    }
}
