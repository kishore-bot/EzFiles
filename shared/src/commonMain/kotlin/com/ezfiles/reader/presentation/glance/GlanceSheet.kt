package com.ezfiles.reader.presentation.glance

import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.application.DocumentGlancer
import com.ezfiles.reader.domain.DocumentGlance
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.GlanceItem
import com.ezfiles.reader.domain.GlanceKind
import com.ezfiles.reader.platform.QuickActions
import com.ezfiles.reader.platform.LocalPdfOpener
import com.ezfiles.reader.platform.rememberQuickActions
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzSheet
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import com.ezfiles.reader.presentation.viewers.CopyIcon
import com.ezfiles.reader.presentation.viewers.LinkDialog
import com.ezfiles.reader.presentation.viewers.LinkPrompt
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import com.ezfiles.reader.presentation.components.EzTextButton

/** The reader's "At a glance"; `null` where the app has none (previews, iOS). */
val LocalGlancer = staticCompositionLocalOf<DocumentGlancer?> { null }

private sealed interface GlanceState {
    data object Reading : GlanceState

    data object NoText : GlanceState
    data object Failed : GlanceState

    data class Ready(val glance: DocumentGlance) : GlanceState
}

/**
 * A sheet beside the open document with its key sentences, keywords and the
 * dates, amounts, numbers and links in it. The document underneath is not
 * touched. Work starts only when the sheet opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlanceSheet(ref: DocumentRef, title: String, onDismiss: () -> Unit) {
    val glancer = LocalGlancer.current ?: return
    var state by remember(ref.identity) { mutableStateOf<GlanceState>(GlanceState.Reading) }
    var attempt by remember(ref.identity) { mutableIntStateOf(0) }
    val recognitionNotice = LocalPdfOpener.current?.textRecognitionNotice.takeIf { ref.displayName.endsWith(".pdf", ignoreCase = true) }
    LaunchedEffect(ref.identity, attempt) {
        state = GlanceState.Reading
        state = try {
            glancer.glance(ref)?.let { GlanceState.Ready(it) } ?: GlanceState.NoText
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GlanceState.Failed
        }
    }
    EzSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), title = "At a glance") {
        recognitionNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            when (val s = state) {
                GlanceState.Failed -> Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text("This document couldn't be analyzed. You can keep reading or try again.", color = EzColors.TextSecondary)
                    EzTextButton("Try again", { attempt++ })
                }
                GlanceState.Reading -> Row(
                    Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = EzColors.Primary, strokeWidth = 2.dp)
                    Text("Reading the document…", style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
                }
                GlanceState.NoText -> Text(
                    if (recognitionNotice != null) "No Latin-script text could be read from the first 8 pages. Use Image to text in Tools for another page." else
                        "This file has no text to look at. Scanned pages and pictures can be read with Image to text in Tools.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
                is GlanceState.Ready -> GlanceContent(s.glance, title)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun GlanceContent(glance: DocumentGlance, title: String) {
    val actions = rememberQuickActions()
    val clipboard = LocalClipboardManager.current
    val haptics = rememberEzHaptics()
    var prompt by remember { mutableStateOf<LinkPrompt?>(null) }
    var copied by remember { mutableStateOf<GlanceItem?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPIED_MS)
            copied = null
        }
    }
    LinkDialog(prompt) { prompt = it }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
        item(key = "facts") {
            Text(
                facts(glance),
                style = MaterialTheme.typography.labelLarge,
                color = EzColors.TextSecondary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
        }
        if (glance.keySentences.isNotEmpty()) {
            item(key = "sentences-title") { SectionTitle("Key sentences") }
            items(glance.keySentences) { sentence -> KeySentence(sentence) }
        }
        if (glance.keywords.isNotEmpty()) {
            item(key = "keywords") {
                Column {
                    SectionTitle("Keywords")
                    FlowRow(
                        Modifier.padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (word in glance.keywords) {
                            Text(
                                word,
                                style = MaterialTheme.typography.labelLarge,
                                color = EzColors.Text,
                                modifier = Modifier
                                    .background(EzColors.Primary.copy(alpha = 0.12f), EzShapes.Field)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        if (glance.items.isNotEmpty()) {
            item(key = "items-title") { SectionTitle("In this file") }
            items(glance.items, key = { "${it.kind}|${it.value}" }) { item ->
                val run = primaryAction(item, title, actions, onLink = { prompt = LinkPrompt.Open(it) }) { done ->
                    if (!done) prompt = LinkPrompt.Notice(NO_APP)
                }
                val copy = {
                    clipboard.setText(AnnotatedString(copyText(item)))
                    haptics.confirm()
                    copied = item
                }
                ItemRow(
                    item = item,
                    copied = copied == item,
                    actionLabel = run?.first,
                    onAction = run?.second ?: copy,
                    onCopy = if (run != null) copy else null,
                )
            }
        }
        item(key = "note") {
            Text(
                "Worked out on this phone from the file's text. Nothing is sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = EzColors.TextSecondary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = EzColors.Text,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}

/** Quoted as written, marked by a rose rule rather than restyled. */
@Composable
private fun KeySentence(sentence: String) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 10.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(EzColors.Primary.copy(alpha = 0.6f), EzShapes.Field))
        Text(sentence, style = MaterialTheme.typography.bodyMedium, color = EzColors.Text, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ItemRow(item: GlanceItem, copied: Boolean, actionLabel: String?, onAction: () -> Unit, onCopy: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = EzSpacing.Touch)
            .clickable(role = Role.Button, onClickLabel = actionLabel ?: "Copy", onClick = rememberHapticActivation(onAction))
            .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon(item.kind), null, tint = EzColors.Primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(item.text, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val hint = if (copied) "Copied" else listOfNotNull(subtitle(item), actionLabel ?: "Tap to copy").joinToString(" · ")
            Text(hint, style = MaterialTheme.typography.bodySmall, color = if (copied) EzColors.Primary else EzColors.TextSecondary, maxLines = 1)
        }
        if (onCopy != null) BarIconButton(CopyIcon, "Copy ${item.text}", onCopy, tint = EzColors.TextSecondary)
    }
}

/** What tapping the row does, when it is more than copying. */
private fun primaryAction(
    item: GlanceItem,
    title: String,
    actions: QuickActions?,
    onLink: (String) -> Unit,
    result: (Boolean) -> Unit,
): Pair<String, () -> Unit>? = when (item.kind) {
    GlanceKind.Link -> "Open" to { onLink(item.value) }
    GlanceKind.Phone -> actions?.let { a -> "Call" to { result(a.dial(item.value)) } }
    GlanceKind.Email -> actions?.let { a -> "Write" to { result(a.email(item.value)) } }
    GlanceKind.Date -> item.date?.let { (y, m, d) -> actions?.let { a -> "Add to calendar" to { result(a.addToCalendar(title, y, m, d)) } } }
    GlanceKind.Amount, GlanceKind.Reference -> null
}

/** Dates copy as written; numbers, addresses and links copy ready to paste. */
private fun copyText(item: GlanceItem) = if (item.kind == GlanceKind.Date) item.text else item.value

private fun subtitle(item: GlanceItem): String? = when (item.kind) {
    GlanceKind.Date -> "Date"
    GlanceKind.Amount -> item.label?.let { "Amount in $it" } ?: "Amount"
    GlanceKind.Phone -> "Phone"
    GlanceKind.Email -> "Email"
    GlanceKind.Link -> item.label ?: "Link"
    GlanceKind.Reference -> item.label ?: "Reference"
}

private fun icon(kind: GlanceKind): ImageVector = when (kind) {
    GlanceKind.Date -> GlanceIcons.Event
    GlanceKind.Amount -> GlanceIcons.Payments
    GlanceKind.Phone -> GlanceIcons.Call
    GlanceKind.Email -> GlanceIcons.Mail
    GlanceKind.Link -> GlanceIcons.Link
    GlanceKind.Reference -> GlanceIcons.Tag
}

/** "1,240 words · 6 min read · English" */
private fun facts(g: DocumentGlance): String = listOfNotNull(
    "${grouped(g.words)} ${if (g.words == 1) "word" else "words"}",
    "${g.readingMinutes} min read",
    g.language,
).joinToString(" · ")

private fun grouped(n: Int): String = n.toString().reversed().chunked(3).joinToString(",").reversed()

private const val COPIED_MS = 1_500L
private const val NO_APP = "No app on this phone can do that."
