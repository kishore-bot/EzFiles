package com.ezfiles.reader.presentation.assistant

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.application.assistant.ConversationManager
import com.ezfiles.reader.application.assistant.ConversationTurn
import com.ezfiles.reader.application.assistant.ModelManager
import com.ezfiles.reader.application.assistant.ModelState
import com.ezfiles.reader.application.assistant.parseModelResponse
import com.ezfiles.reader.application.assistant.spokenTextFor
import com.ezfiles.reader.platform.SpeechState
import com.ezfiles.reader.platform.TtsState
import com.ezfiles.reader.platform.rememberSpeechRecognition
import com.ezfiles.reader.platform.rememberTextToSpeech
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.components.EzSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import kotlinx.coroutines.launch

enum class AssistantState { Idle, Listening, Processing, Speaking }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceAssistantSheet(
    onDismiss: () -> Unit,
    modelManager: ModelManager,
    conversation: ConversationManager,
) {
    val scope = rememberCoroutineScope()
    val modelState by modelManager.state.collectAsState()
    val turns by conversation.turns.collectAsState()
    var assistantState by remember { mutableStateOf(AssistantState.Idle) }
    var partialText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val tts = rememberTextToSpeech()
    val ttsState by tts.state.collectAsState()

    val speech = rememberSpeechRecognition { result ->
        partialText = result.text
        if (result.isFinal && result.text.isNotBlank()) {
            val userText = result.text
            partialText = ""
            assistantState = AssistantState.Processing
            scope.launch {
                val raw = modelManager.call(userText, conversation.recentContext())
                val response = parseModelResponse(raw)
                val spoken = spokenTextFor(response)
                conversation.addTurn(userText, raw, spoken)
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

    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1)
    }

    EzSheet(onDismissRequest = onDismiss, title = "Fily") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            if (modelState != ModelState.Ready) {
                Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) {
                    Text(
                        when (modelState) {
                            ModelState.Loading -> "Loading model…"
                            ModelState.Error -> "Model failed to load. Push the GGUF to the device."
                            else -> "Model not loaded."
                        },
                        color = EzColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    reverseLayout = false,
                ) {
                    items(turns, key = { it.id }) { turn ->
                        TurnBubble(turn)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            AnimatedContent(assistantState, modifier = Modifier.fillMaxWidth()) { state ->
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    Text(
                        when (state) {
                            AssistantState.Idle -> if (partialText.isNotBlank()) partialText else "Tap mic to start"
                            AssistantState.Listening -> partialText.ifBlank { "Listening…" }
                            AssistantState.Processing -> "Thinking…"
                            AssistantState.Speaking -> "Speaking…"
                        },
                        color = EzColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Box(Modifier.fillMaxWidth(), Alignment.Center) {
                IconButton(
                    onClick = {
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
                    },
                    enabled = modelState == ModelState.Ready && assistantState != AssistantState.Processing,
                    modifier = Modifier.size(64.dp).clip(CircleShape).glass(CircleShape, 0.7f),
                ) {
                    Icon(
                        if (assistantState == AssistantState.Listening) EzIcons.Close else EzIcons.Mic,
                        contentDescription = if (assistantState == AssistantState.Listening) "Stop" else "Speak",
                        tint = if (assistantState == AssistantState.Listening) EzColors.Primary else EzColors.Text,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TurnBubble(turn: ConversationTurn) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // User bubble — right
        Box(Modifier.fillMaxWidth(), Alignment.CenterEnd) {
            Text(
                turn.userText,
                color = EzColors.Text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .glass(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp), 0.55f)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        // Assistant bubble — left
        Box(Modifier.fillMaxWidth(), Alignment.CenterStart) {
            Text(
                turn.responseText,
                color = EzColors.Text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                    .glass(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp), 0.45f)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
