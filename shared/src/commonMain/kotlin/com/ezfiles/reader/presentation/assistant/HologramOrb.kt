package com.ezfiles.reader.presentation.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/**
 * Fily's holographic orb: three gradient ribbons, a dotted orbit, sparks, a waveform in the middle
 * and a glowing horizon below. One frame clock drives everything and only the drawing is redone each
 * frame (no recomposition). Still when the system asks for reduced motion.
 */
@Composable
fun HologramOrb(
    state: AssistantState,
    /** Voice loudness 0..1 while listening. */
    level: State<Float>,
    reducedMotion: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val time = remember { mutableFloatStateOf(0f) }
    val smoothLevel = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                time.floatValue = (now - start) / 1_000_000_000f
                // Rises fast, falls slower: a bounce that follows the voice without flicker.
                val target = level.value
                val current = smoothLevel.floatValue
                smoothLevel.floatValue = current + (target - current) * (if (target > current) 0.35f else 0.12f)
            }
        }
    }
    Box(modifier.fillMaxWidth().height(270.dp)) {
        Canvas(Modifier.fillMaxWidth().height(270.dp)) {
            val t = time.floatValue
            drawBackdrop(t)
            drawHorizon(t)
            val orbSize = 190.dp.toPx()
            val voice = if (state == AssistantState.Listening) smoothLevel.floatValue else 0f
            val bounce = when (state) {
                AssistantState.Listening -> 1f + 0.12f * voice
                AssistantState.Processing -> 0.94f + 0.02f * wave(t, 1.2f)
                AssistantState.Speaking -> 1f + 0.03f * wave(t, 0.8f)
                AssistantState.Idle -> 0.92f
            }
            translate(left = size.width / 2 - orbSize / 2, top = 32.dp.toPx()) {
                scale(bounce, pivot = Offset(orbSize / 2, orbSize / 2)) {
                    drawOrb(t, orbSize / 200f, state, voice)
                }
            }
        }
        // Only the orb takes taps; the screen around it stays usable.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = 32.dp)
                .size(190.dp)
                .clip(CircleShape)
                .semantics { contentDescription = "Fily voice assistant" }
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onTap),
        )
    }
}

/** 0→1→0 over [period] seconds, eased like CSS ease-in-out. */
private fun wave(t: Float, period: Float, delay: Float = 0f): Float = (1f - cos(2f * PI.toFloat() * (t + delay) / period)) / 2f

/** CSS "alternate" ease-in-out: 0→1 over [half] seconds, then back. */
private fun swing(t: Float, half: Float): Float = (1f - cos(PI.toFloat() * t / half)) / 2f

private val PinkBlue = listOf(
    0f to Color(0xFFFF6DCF), 0.38f to Color(0xFFED48CD), 0.57f to Color(0xFFAB67FF), 0.85f to Color(0xFF4B9CFF), 1f to Color(0xFFBDF3FF),
)
private val BluePink = listOf(0f to Color(0xFFFF6DD9), 0.5f to Color(0xFFB160FF), 1f to Color(0xFF4DC0FF))

private fun ribbon(vararg points: Float): Path = Path().apply {
    moveTo(points[0], points[1])
    var i = 2
    while (i + 5 < points.size) {
        cubicTo(points[i], points[i + 1], points[i + 2], points[i + 3], points[i + 4], points[i + 5])
        i += 6
    }
    close()
}

// The design's 200×200 SVG paths.
private val RibbonOne = ribbon(100f, 19f, 149f, 8f, 180f, 58f, 161f, 111f, 142f, 165f, 112f, 196f, 76f, 176f, 31f, 150f, 37f, 76f, 74f, 36f, 85f, 24f, 94f, 20f, 100f, 19f)
private val RibbonTwo = ribbon(98f, 20f, 137f, 18f, 183f, 60f, 183f, 102f, 183f, 147f, 133f, 182f, 94f, 183f, 51f, 184f, 22f, 148f, 32f, 108f, 43f, 59f, 65f, 26f, 98f, 20f)
private val RibbonThree = ribbon(80f, 23f, 125f, 2f, 170f, 52f, 177f, 93f, 189f, 150f, 119f, 194f, 79f, 171f, 29f, 142f, 39f, 69f, 80f, 23f)
private val BarHeights = floatArrayOf(5f, 9f, 15f, 25f, 37f, 47f, 30f, 20f, 12f, 7f, 4f)
private val BarDelays = floatArrayOf(-.5f, -.7f, -.1f, -.4f, -.8f, -.2f, -.6f, -.3f, -.7f, -.1f, -.5f)

/** The soft pink-blue haze behind the orb. */
private fun DrawScope.drawBackdrop(t: Float) {
    val w = 320.dp.toPx()
    val h = 200.dp.toPx()
    val center = Offset(size.width / 2, 20.dp.toPx() + h / 2)
    scale(1f, h / w, pivot = center) {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0x25BD2CAA), 0.36f to Color(0x17495DFF), 0.7f to Color.Transparent,
                center = center, radius = w / 2,
            ),
            radius = w / 2, center = center,
        )
    }
}

/** The glowing arc the orb floats above. */
private fun DrawScope.drawHorizon(t: Float) {
    val hw = max(size.width, 530.dp.toPx())
    val hh = 133.dp.toPx()
    val left = (size.width - hw) / 2
    val top = size.height - 7.dp.toPx() - hh
    fun x(v: Float) = left + v / 440f * hw
    fun y(v: Float) = top + v / 133f * hh
    fun arc(control: Float) = Path().apply {
        moveTo(x(-35f), y(133f))
        cubicTo(x(95f), y(control), x(315f), y(control), x(475f), y(133f))
    }
    val brush = Brush.horizontalGradient(
        0f to Color(0x40F72C92), 0.27f to Color(0xF2C457FD), 0.51f to Color(0xFFFF75E8), 0.67f to Color(0xFF528BFF), 1f to Color(0x4DED418C),
        startX = left, endX = left + hw,
    )
    val pulse = 0.7f + 0.3f * wave(t, 4f)
    val main = arc(52f)
    drawPath(main, brush, alpha = 0.13f * pulse, style = Stroke(18f / 133f * hh))
    drawPath(main, brush, alpha = pulse, style = Stroke(2.dp.toPx()))
    drawPath(arc(62f), brush, alpha = 0.7f * pulse, style = Stroke(0.7.dp.toPx()))
}

/** The orb in its own 200×200 space, scaled by [unit]. */
private fun DrawScope.drawOrb(t: Float, unit: Float, state: AssistantState, voice: Float) {
    val c = Offset(100f, 100f)
    scale(unit, pivot = Offset.Zero) {
        // Breathing inner glow.
        val breathe = wave(t, 3f)
        scale(0.93f + 0.17f * breathe, pivot = c) {
            drawCircle(
                Brush.radialGradient(0f to Color(0x55EF55C7), 0.5f to Color(0x334A8DFF), 1f to Color.Transparent, center = c, radius = 80f),
                radius = 80f, center = c, alpha = 0.4f + 0.5f * breathe,
            )
        }
        val pinkBlue = Brush.linearGradient(*PinkBlue.toTypedArray(), start = Offset(0f, 0f), end = Offset(200f, 200f))
        val bluePink = Brush.linearGradient(*BluePink.toTypedArray(), start = Offset(0f, 200f), end = Offset(200f, 0f))

        // Dotted orbit.
        rotate(-22f + 360f * ((t / 20f) % 1f), pivot = c) {
            drawOval(
                pinkBlue, topLeft = Offset(4f, 36f), size = Size(192f, 128f), alpha = 0.5f,
                style = Stroke(0.65f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f, 2.5f))),
            )
        }

        // Ribbons, each with a glow pass under it.
        val one = swing(t, 9f)
        val two = swing(t, 7f)
        fun ribbonPath(path: Path, brush: Brush, degrees: Float, size: Float, width: Float, alpha: Float, glow: Color) {
            rotate(degrees, pivot = c) {
                scale(size, pivot = c) {
                    drawPath(path, glow, alpha = 0.22f * alpha, style = Stroke(width * 3.2f, cap = StrokeCap.Round))
                    drawPath(path, brush, alpha = alpha, style = Stroke(width, cap = StrokeCap.Round), blendMode = BlendMode.Screen)
                }
            }
        }
        ribbonPath(RibbonOne, pinkBlue, -10f + 24f * one, 1f + 0.035f * one + 0.05f * voice, 2.7f, 1f, Color(0xFFFA5ACA))
        ribbonPath(RibbonTwo, bluePink, 22f - 40f * two, 1f - 0.03f * two + 0.04f * voice, 2.7f, 0.85f, Color(0xFF647FFF))
        ribbonPath(RibbonThree, pinkBlue, 360f * ((t / 11f) % 1f), 1f, 1.2f, 0.7f, Color(0xFFBA56FF))

        // Sparks.
        listOf(Triple(Offset(16f, 92f), 1.65f, Color(0xFFFF84DF)), Triple(Offset(188f, 77f), 1.5f, Color(0xFFFDA5EE)), Triple(Offset(57f, 24f), 1.2f, Color(0xFF83BBFF)))
            .forEachIndexed { i, (at, r, color) ->
                val glow = 0.3f + 0.7f * swing(t + i * 0.7f, 2.2f)
                drawCircle(color, radius = r * 3f, center = at, alpha = 0.15f * glow)
                drawCircle(color, radius = r, center = at, alpha = glow)
            }

        // Waveform: follows the voice while listening, slows while thinking, full while speaking.
        val (period, amplitude) = when (state) {
            AssistantState.Listening -> 0.9f to (0.3f + 0.7f * voice)
            AssistantState.Processing -> 1.8f to 0.45f
            AssistantState.Speaking -> 0.9f to 1f
            AssistantState.Idle -> 2.4f to 0.25f
        }
        val bars = Brush.verticalGradient(0f to Color(0xFFFC61D5), 0.52f to Color(0xFFFFF1FF), 1f to Color(0xFFFF74D1))
        val barWidth = 2f
        val gap = 3f
        val total = BarHeights.size * barWidth + (BarHeights.size - 1) * gap
        BarHeights.forEachIndexed { i, height ->
            val pulse = 0.34f + 0.66f * wave(t, period, BarDelays[i])
            val h = height * (0.2f + 0.8f * pulse * amplitude)
            val x = 100f - total / 2 + i * (barWidth + gap)
            val topLeft = Offset(x, 100f - h / 2)
            drawRoundRect(Color(0xFFFF69D9), topLeft = topLeft - Offset(1.5f, 1.5f), size = Size(barWidth + 3f, h + 3f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f), alpha = 0.25f)
            drawRoundRect(bars, topLeft = topLeft, size = Size(barWidth, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f),
                alpha = 0.64f + 0.36f * pulse)
        }
    }
}
