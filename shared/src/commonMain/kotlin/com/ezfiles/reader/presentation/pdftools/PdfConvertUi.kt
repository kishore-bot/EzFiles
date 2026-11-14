package com.ezfiles.reader.presentation.pdftools

import com.ezfiles.reader.presentation.theme.rememberHapticChoice


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.presentation.rememberDocumentDeliveryActions
import com.ezfiles.reader.presentation.tidy.TidyIcons
import com.ezfiles.reader.platform.LocalPdfOpener
import com.ezfiles.reader.platform.PageImageFormat
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.platform.PdfPasswordRequired
import com.ezfiles.reader.platform.rememberDocumentPicker
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.create.CreateToolFrame
import com.ezfiles.reader.presentation.components.EzActionPanel
import com.ezfiles.reader.presentation.components.EzDialog
import com.ezfiles.reader.presentation.components.EzProgressRow
import com.ezfiles.reader.presentation.components.EzSettingLabel
import com.ezfiles.reader.presentation.components.EzStatusCard
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.components.SecondaryActionButton
import com.ezfiles.reader.presentation.theme.EzColors
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.FeedbackOnAppear
import com.ezfiles.reader.presentation.theme.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

internal sealed interface ConvertPdfLoad {
    data object Idle : ConvertPdfLoad
    data object Loading : ConvertPdfLoad
    data class Open(val pages: PdfPages) : ConvertPdfLoad
    data class NeedsPassword(val incorrect: Boolean) : ConvertPdfLoad

    /** [isPdf] when the file is fine but this device can't open it, so copying it whole would still work. */
    data class Failed(val message: String, val isPdf: Boolean = false) : ConvertPdfLoad
}

/** Keeps [ref] open while composed, and closes it on leaving or on a change of file. */
@Composable
internal fun rememberConvertPdf(ref: DocumentRef?, password: String?): ConvertPdfLoad {
    val opener = LocalPdfOpener.current
    val load by produceState<ConvertPdfLoad>(ConvertPdfLoad.Idle, ref, password) {
        if (ref == null) {
            value = ConvertPdfLoad.Idle
            return@produceState
        }
        value = ConvertPdfLoad.Loading
        var pages: PdfPages? = null
        try {
            withContext(Dispatchers.IO) { pages = opener.open(ref, password) }
            value = ConvertPdfLoad.Open(checkNotNull(pages))
            awaitCancellation()
        } catch (e: PdfPasswordRequired) {
            value = ConvertPdfLoad.NeedsPassword(e.incorrect)
        } catch (e: DocumentException) {
            value = ConvertPdfLoad.Failed(e.error.detail, isPdf = e.error is DocumentError.Unsupported)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            value = ConvertPdfLoad.Failed("This file couldn't be opened as a PDF.")
        } finally {
            withContext(NonCancellable) { pages?.close() }
        }
    }
    return load
}

/**
 * The frame of a tool that works on one PDF: asks for a file and its password
 * if it has one, then shows [content] for as long as it's open. The password is
 * only ever in memory, for this screen.
 */
@Composable
internal fun ConvertPdfGate(
    title: String,
    intro: String,
    onClose: () -> Unit,
    content: @Composable (ref: DocumentRef, pages: PdfPages, chooseAnother: () -> Unit) -> Unit,
) {
    val initialRef = LocalPdfToolsPreselect.current ?: LocalPdfToolsPreselectList.current.firstOrNull()
    var ref by remember { mutableStateOf(initialRef) }
    var password by remember(ref) { mutableStateOf<String?>(null) }
    val picker = rememberDocumentPicker { picked -> if (picked != null) ref = picked }
    val load = rememberConvertPdf(ref, password)
    val current = ref
    CreateToolFrame(title, onClose) {
        when (load) {
            ConvertPdfLoad.Idle -> ConvertPickPrompt(intro, picker::launch)
            ConvertPdfLoad.Loading -> ConvertBusy()
            is ConvertPdfLoad.NeedsPassword -> {
                ConvertBusy()
                ConvertPasswordDialog(load.incorrect, onUnlock = { password = it }, onDismiss = { ref = null })
            }
            is ConvertPdfLoad.Failed -> ConvertPickPrompt(load.message, picker::launch)
            is ConvertPdfLoad.Open -> if (current != null) content(current, load.pages) { ref = null }
        }
    }
}

@Composable
internal fun ConvertBusy() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = EzColors.Primary) }
}

@Composable
internal fun ConvertPickPrompt(message: String, onPick: () -> Unit) {
    // The same card as the other PDF tools' first step.
    EzStatusCard {
        Icon(EzIcons.PdfFile, null, tint = EzColors.Primary, modifier = Modifier.size(36.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text, textAlign = TextAlign.Center)
        PrimaryActionButton("Choose a PDF", EzIcons.Folder, onPick)
    }
}

@Composable
internal fun ConvertPasswordDialog(incorrect: Boolean, onUnlock: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text("This PDF is protected") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (incorrect) "That password didn't work. Try again." else "Enter the password to open it. It isn't stored.",
                    color = if (incorrect) EzColors.Accent else EzColors.TextSecondary,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotEmpty()) onUnlock(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { EzTextButton("Unlock", { onUnlock(text) }, enabled = text.isNotEmpty()) },
        dismissButton = { EzTextButton("Cancel", onDismiss, color = EzColors.TextSecondary) },
    )
}

internal val ConvertDpiChoices = listOf(100, 150, 300)

/** Format and resolution, for every tool that turns pages into pictures. */
@Composable
internal fun PageImageOptions(format: PageImageFormat, onFormat: (PageImageFormat) -> Unit, dpi: Int, onDpi: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EzSettingLabel("Format")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EzChip("JPEG", format == PageImageFormat.Jpeg, { onFormat(PageImageFormat.Jpeg) })
            EzChip("PNG", format == PageImageFormat.Png, { onFormat(PageImageFormat.Png) })
        }
        EzSettingLabel("Resolution")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConvertDpiChoices.forEach { choice -> EzChip("$choice DPI", dpi == choice, { onDpi(choice) }) }
        }
        Text(
            "Higher is sharper and larger. Very large pages are reduced to about 16 megapixels so they fit in memory.",
            style = MaterialTheme.typography.bodySmall,
            color = EzColors.TextSecondary,
        )
    }
}

/** [total] of `null` means the length isn't known. */
@Composable
internal fun ConvertProgress(label: String, done: Long, total: Long?, onCancel: () -> Unit) {
    EzActionPanel {
        EzProgressRow(label, if (total != null && total > 0) done.toFloat() / total else null, onCancel)
    }
}

/** What a run came to, and the ways on from there. */
@Composable
internal fun ConvertResult(
    title: String,
    message: String?,
    onDone: () -> Unit,
    doneLabel: String = "Convert another",
    onOpen: (() -> Unit)? = null,
    outputRef: DocumentRef? = null,
) {
    FeedbackOnAppear(title) { confirm() }
    val delivery = if (outputRef != null) rememberDocumentDeliveryActions() else null
    EzActionPanel {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = EzColors.Text,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary) }
        if (outputRef != null && delivery != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryActionButton("Share", TidyIcons.Share, { delivery.share(listOf(outputRef)) }, Modifier.weight(1f), enabled = !delivery.busy)
                SecondaryActionButton("Print", EzIcons.PdfFile, { delivery.print(outputRef) }, Modifier.weight(1f), enabled = !delivery.busy && delivery.canPrint(outputRef))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            onOpen?.let { open -> SecondaryActionButton("Open", EzIcons.Document, open) }
            EzTextButton(doneLabel, onDone)
        }
    }
}

/** A row that reads as one radio choice with an explanation beneath. */
@Composable
internal fun ConvertOption(title: String, detail: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(EzShapes.Card, if (selected) 0.85f else 0.4f)
            .then(if (selected) Modifier.border(2.dp, EzColors.Primary, EzShapes.Card) else Modifier)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = rememberHapticChoice(selected, onSelect))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = if (!enabled) EzColors.TextSecondary else if (selected) EzColors.Accent else EzColors.Text,
        )
        Text(detail, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
    }
}
