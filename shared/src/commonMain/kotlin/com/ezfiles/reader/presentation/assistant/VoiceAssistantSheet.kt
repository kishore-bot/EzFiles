package com.ezfiles.reader.presentation.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.presentation.theme.glassPanel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.application.assistant.ConversationManager
import com.ezfiles.reader.application.assistant.ModelManager
import com.ezfiles.reader.application.assistant.ModelState
import com.ezfiles.reader.application.assistant.ToolExecutor
import com.ezfiles.reader.application.assistant.ToolResult
import com.ezfiles.reader.application.assistant.parseModelResponse
import com.ezfiles.reader.application.assistant.spokenTextFor
import com.ezfiles.reader.platform.TtsState
import com.ezfiles.reader.platform.rememberSpeechRecognition
import com.ezfiles.reader.platform.rememberTextToSpeech
import com.ezfiles.reader.presentation.theme.EzColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AssistantState { Idle, Listening, Processing, Speaking }

@Composable
fun VoiceAssistantSheet(
    onDismiss: () -> Unit,
    modelManager: ModelManager,
    conversation: ConversationManager,
    toolExecutor: ToolExecutor? = null,
    onNavigate: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val modelState by modelManager.state.collectAsState()
    val turns by conversation.turns.collectAsState()
    var assistantState by remember { mutableStateOf(AssistantState.Idle) }
    var partialText by remember { mutableStateOf("") }
    var lastResponse by remember { mutableStateOf("") }
    var lastUserText by remember { mutableStateOf("") }

    val tts = rememberTextToSpeech()
    val ttsState by tts.state.collectAsState()

    val speech = rememberSpeechRecognition { result ->
        partialText = result.text
        if (result.isFinal && result.text.isNotBlank()) {
            val userText = result.text
            partialText = ""
            lastUserText = userText
            lastResponse = ""
            assistantState = AssistantState.Processing
            scope.launch {
                val raw = modelManager.call(userText, conversation.recentContext())
                val response = parseModelResponse(raw)
                var spoken: String
                if (response.kind == "tool" && toolExecutor != null && response.toolName != null) {
                    val toolResult = toolExecutor.execute(response.toolName, response.toolArgs)
                    when (toolResult) {
                        is ToolResult.Navigation -> {
                            spoken = toolResult.summary
                            onNavigate(toolResult.documentId)
                        }
                        is ToolResult.Success -> {
                            val followUp = modelManager.callWithToolResult(
                                userText, response.toolName, toolResult.summary, conversation.recentContext()
                            )
                            val followUpResponse = parseModelResponse(followUp)
                            spoken = followUpResponse.text ?: toolResult.summary
                        }
                        is ToolResult.Error -> {
                            spoken = toolResult.message
                        }
                    }
                } else {
                    spoken = spokenTextFor(response)
                }
                conversation.addTurn(userText, raw, spoken)
                lastResponse = spoken
                assistantState = AssistantState.Speaking
                tts.speak(spoken)
            }
        }
    }
    val speechState by speech.state.collectAsState()

    LaunchedEffect(ttsState) {
        if (ttsState == TtsState.Idle && assistantState == AssistantState.Speaking) {
            assistantState = AssistantState.Listening
            speech.start()
        }
    }

    LaunchedEffect(modelState) {
        if (modelState == ModelState.Ready && assistantState == AssistantState.Idle) {
            assistantState = AssistantState.Listening
            speech.start()
        }
    }

    // --- Animations ---
    val infinite = rememberInfiniteTransition()

    // Hue rotation for holographic effect
    val hueShift by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart),
    )

    // Organic wobble
    val wobble1 by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
    )
    val wobble2 by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
    )

    // Bounce scale — springy when listening
    val bounceTarget = when (assistantState) {
        AssistantState.Idle -> 0.6f
        AssistantState.Listening -> 1.0f + 0.12f * sin(wobble1)
        AssistantState.Processing -> 0.75f + 0.05f * sin(wobble1 * 2f)
        AssistantState.Speaking -> 0.95f + 0.08f * sin(wobble1 * 1.5f)
    }
    val orbScale by animateFloatAsState(
        bounceTarget,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
    )

    fun toggleState() {
        when (assistantState) {
            AssistantState.Idle -> {
                assistantState = AssistantState.Listening
                speech.start()
            }
            AssistantState.Listening -> {
                speech.cancel()
                assistantState = AssistantState.Idle
                partialText = ""
            }
            AssistantState.Speaking -> {
                tts.stop()
                assistantState = AssistantState.Listening
                speech.start()
            }
            AssistantState.Processing -> {}
        }
    }

    // Holographic colors based on hue shift
    fun holoColor(offset: Float, alpha: Float = 0.8f): Color {
        val h = (hueShift + offset) % 360f
        return hsvToColor(h, 0.6f, 1f, alpha)
    }

    // Floating overlay — no background, no sheet, just the orb
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Conversation card at the top, under the status bar
        val question = partialText.ifBlank { lastUserText }
        val answer = when {
            assistantState == AssistantState.Processing -> "Thinking…"
            else -> lastResponse
        }
        AnimatedVisibility(
            visible = question.isNotBlank() || answer.isNotBlank(),
            enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { -it / 3 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 3 },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val cardShape = RoundedCornerShape(28.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassPanel(cardShape)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (question.isNotBlank()) {
                    Text(
                        question,
                        color = EzColors.TextSecondary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (answer.isNotBlank()) {
                    Text(
                        answer,
                        color = EzColors.Text,
                        style = MaterialTheme.typography.titleMedium.copy(lineHeight = 24.sp),
                    )
                }
                Text(
                    "Fily · On-device",
                    color = EzColors.TextSecondary.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // The orb
        val orbSize = 120.dp
        Canvas(
            Modifier
                .size(orbSize)
                .padding(bottom = 48.dp)
                .align(Alignment.BottomCenter)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { if (modelState == ModelState.Ready) toggleState() },
        ) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = (size.minDimension / 2) * orbScale

            // Outer holographic glow layers
            for (i in 5 downTo 1) {
                val glowRadius = baseRadius + i * 14f
                val c = holoColor(i * 60f, alpha = 0.04f * (6 - i))
                drawCircle(color = c, radius = glowRadius, center = center)
            }

            // Main orb — radial gradient with shifting holographic colors
            val color1 = holoColor(0f, 0.9f)
            val color2 = holoColor(120f, 0.7f)
            val color3 = holoColor(240f, 0.4f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.6f), color1, color2, color3, Color.Transparent),
                    center = center,
                    radius = baseRadius * 1.1f,
                ),
                radius = baseRadius,
                center = center,
            )

            // Inner bright core
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.1f), Color.Transparent),
                    center = Offset(center.x + baseRadius * 0.1f * cos(wobble2), center.y - baseRadius * 0.15f * sin(wobble1)),
                    radius = baseRadius * 0.55f,
                ),
                radius = baseRadius * 0.55f,
                center = Offset(center.x + baseRadius * 0.1f * cos(wobble2), center.y - baseRadius * 0.15f * sin(wobble1)),
            )

            // Swirling highlight arc
            val arcAngle = Math.toDegrees(wobble1.toDouble()).toFloat()
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        holoColor(0f, 0.5f),
                        holoColor(90f, 0.3f),
                        holoColor(180f, 0.5f),
                        holoColor(270f, 0.3f),
                        holoColor(360f, 0.5f),
                    ),
                    center = center,
                ),
                startAngle = arcAngle,
                sweepAngle = 160f,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx()),
                topLeft = Offset(center.x - baseRadius, center.y - baseRadius),
                size = androidx.compose.ui.geometry.Size(baseRadius * 2, baseRadius * 2),
            )

            // Processing: spinning ring
            if (assistantState == AssistantState.Processing) {
                val spinAngle = Math.toDegrees((wobble1 * 3f).toDouble()).toFloat()
                drawArc(
                    color = Color.White.copy(alpha = 0.6f),
                    startAngle = spinAngle,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx()),
                    topLeft = Offset(center.x - baseRadius - 8f, center.y - baseRadius - 8f),
                    size = androidx.compose.ui.geometry.Size((baseRadius + 8f) * 2, (baseRadius + 8f) * 2),
                )
            }

            // Speaking: expanding ripple rings
            if (assistantState == AssistantState.Speaking) {
                val t = (wobble1 / (2f * PI.toFloat()))
                for (i in 0..2) {
                    val phase = (t + i * 0.33f) % 1f
                    val r = baseRadius + phase * 35f
                    val c = holoColor(phase * 120f, 0.3f * (1f - phase))
                    drawCircle(color = c, radius = r, center = center, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
        }

        // Status text below orb
        Text(
            when (assistantState) {
                AssistantState.Idle -> if (modelState != ModelState.Ready) "Loading…" else ""
                AssistantState.Listening -> "Listening…"
                AssistantState.Processing -> ""
                AssistantState.Speaking -> ""
            },
            color = Color.White.copy(alpha = 0.35f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
}

// HSV to Compose Color
private fun hsvToColor(h: Float, s: Float, v: Float, alpha: Float): Color {
    val c = v * s
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r + m, g + m, b + m, alpha)
}
