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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import com.ezfiles.reader.platform.SpeechState
import com.ezfiles.reader.presentation.theme.LocalReducedMotion
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
import com.ezfiles.reader.application.assistant.FilySession
import com.ezfiles.reader.application.assistant.ModelManager
import com.ezfiles.reader.application.assistant.ModelState
import com.ezfiles.reader.platform.TtsState
import com.ezfiles.reader.platform.rememberSpeechRecognition
import com.ezfiles.reader.platform.rememberTextToSpeech
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.components.GlassIconButton
import com.ezfiles.reader.platform.PlatformBackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class AssistantState { Idle, Listening, Processing, Speaking }

@Composable
fun VoiceAssistantSheet(
    onDismiss: () -> Unit,
    modelManager: ModelManager,
    session: FilySession,
) {
    val scope = rememberCoroutineScope()
    val modelState by modelManager.state.collectAsState()
    var assistantState by remember { mutableStateOf(AssistantState.Idle) }
    var partialText by remember { mutableStateOf("") }
    var lastResponse by remember { mutableStateOf("") }
    var lastUserText by remember { mutableStateOf("") }

    val tts = rememberTextToSpeech()
    val ttsState by tts.state.collectAsState()

    // Silent sessions in a row; after MAX_QUIET_RESTARTS Fily pauses instead of listening forever.
    var quietRestarts by remember { mutableIntStateOf(0) }
    val speech = rememberSpeechRecognition(hints = session::speechHints) { result ->
        partialText = result.text
        if (result.isFinal && result.text.isNotBlank()) {
            quietRestarts = 0
            val userText = session.bestTranscript(result.alternatives)
            partialText = ""
            lastUserText = userText
            lastResponse = ""
            assistantState = AssistantState.Processing
            scope.launch {
                val spoken = session.ask(userText)
                lastResponse = spoken
                assistantState = AssistantState.Speaking
                tts.speak(spoken)
            }
        }
    }
    val speechState by speech.state.collectAsState()
    val speechLevel = speech.level.collectAsState()

    LaunchedEffect(ttsState) {
        if (ttsState != TtsState.Speaking && assistantState == AssistantState.Speaking) {
            assistantState = AssistantState.Listening
            speech.start()
        }
    }

    // Speech that never starts (no voice engine, or nothing to say) must not leave Fily stuck.
    LaunchedEffect(assistantState) {
        if (assistantState != AssistantState.Speaking) return@LaunchedEffect
        delay(SPEECH_START_TIMEOUT_MS)
        if (assistantState == AssistantState.Speaking && tts.state.value != TtsState.Speaking) {
            assistantState = AssistantState.Listening
            speech.start()
        }
    }

    // Continuous listening: the recognizer ends on silence, a timeout or a dropped service without a
    // request; Fily listens again by itself, and pauses after a long silence.
    LaunchedEffect(speechState, assistantState) {
        if (assistantState != AssistantState.Listening || speechState == SpeechState.Listening) return@LaunchedEffect
        delay(RESTART_DELAY_MS)
        if (assistantState != AssistantState.Listening) return@LaunchedEffect
        if (quietRestarts >= MAX_QUIET_RESTARTS) {
            quietRestarts = 0
            partialText = ""
            assistantState = AssistantState.Idle
            return@LaunchedEffect
        }
        quietRestarts++
        speech.start()
    }

    LaunchedEffect(modelState) {
        if (modelState == ModelState.Ready && assistantState == AssistantState.Idle) {
            assistantState = AssistantState.Listening
            speech.start()
        }
    }

    // Closing stops listening and speaking; the app stays usable underneath while Fily is open.
    PlatformBackHandler(enabled = true, onBack = onDismiss)
    DisposableEffect(Unit) {
        onDispose {
            speech.cancel()
            tts.stop()
        }
    }

    // Counts while thinking, so a slow answer reads as work, not a freeze.
    var thinkingSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(assistantState) {
        thinkingSeconds = 0
        while (assistantState == AssistantState.Processing) {
            delay(1000)
            thinkingSeconds++
        }
    }

    fun toggleState() {
        when (assistantState) {
            AssistantState.Idle -> {
                quietRestarts = 0
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

    // Floating overlay — no background, no sheet, just the orb
    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Conversation card at the top, under the status bar
        val question = partialText.ifBlank { lastUserText }
        val answer = when {
            assistantState == AssistantState.Processing -> if (thinkingSeconds < 2) "Thinking…" else "Thinking… ${thinkingSeconds}s"
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

        // The hologram, above the bottom bar; only the orb itself takes taps.
        HologramOrb(
            state = assistantState,
            level = speechLevel,
            reducedMotion = LocalReducedMotion.current,
            onTap = { if (modelState == ModelState.Ready) toggleState() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = HOLOGRAM_BOTTOM),
        )

        // Always says what Fily is doing, so "is it loaded? is it listening?" never needs a guess.
        val (status, dot) = when {
            modelState == ModelState.Error -> "Fily couldn't load" to Color(0xFFE5484D)
            modelState != ModelState.Ready -> "Loading Fily…" to Color(0xFFF5A524)
            assistantState == AssistantState.Listening && speechState == SpeechState.Listening -> "Listening · speak now" to Color(0xFF30A46C)
            assistantState == AssistantState.Listening -> "Starting the mic…" to Color(0xFFF5A524)
            assistantState == AssistantState.Processing -> "Thinking…" to Color(0xFF3E63DD)
            assistantState == AssistantState.Speaking -> "Speaking · tap to interrupt" to Color(0xFF8E4EC6)
            else -> "Paused · tap the orb to talk" to EzColors.TextSecondary
        }
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = HOLOGRAM_BOTTOM + 12.dp)
                .glassPanel(RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(status, color = EzColors.Text, style = MaterialTheme.typography.labelMedium)
        }

        GlassIconButton(
            EzIcons.Close,
            "Close Fily",
            onDismiss,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = HOLOGRAM_BOTTOM + 180.dp),
        )
    }
}

private const val RESTART_DELAY_MS = 250L
private const val SPEECH_START_TIMEOUT_MS = 2_500L
/** About a minute of silence (each silent session lasts ~5-8 s) before Fily pauses. */
private const val MAX_QUIET_RESTARTS = 8

/** Clears the library's bottom bar. */
private val HOLOGRAM_BOTTOM = 76.dp
