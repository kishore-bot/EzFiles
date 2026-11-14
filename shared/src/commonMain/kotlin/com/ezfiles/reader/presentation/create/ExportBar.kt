package com.ezfiles.reader.presentation.create

import com.ezfiles.reader.presentation.theme.rememberHapticChoice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.border
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.presentation.rememberDocumentDeliveryActions
import com.ezfiles.reader.presentation.components.EzActionPanel
import com.ezfiles.reader.presentation.components.EzProgressRow
import com.ezfiles.reader.presentation.components.EzSettingLabel
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.scan.ScanIcons
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.FeedbackOnAppear
import com.ezfiles.reader.presentation.theme.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Where an export stands. */
sealed interface ExportStatus {
    data object Idle : ExportStatus

    data class Working(val done: Int, val total: Int, val cancelling: Boolean = false) : ExportStatus

    /** Finished; [uri] can be opened. */
    data class Saved(val uri: String, val message: String) : ExportStatus

    /** Nothing was written; [message] says why. */
    data class Stopped(val message: String) : ExportStatus
}

/** Runs one export at a time and reports it as state. Leaving the screen cancels it with [scope]. */
@Stable
internal class ExportRunner(private val scope: CoroutineScope) {
    var status by mutableStateOf<ExportStatus>(ExportStatus.Idle)
        private set
    private var job: Job? = null

    /** [work] gets a progress callback and returns the message to show on success. */
    fun run(uri: String, work: suspend (onProgress: (Int, Int) -> Unit) -> String) {
        if (status is ExportStatus.Working) return
        status = ExportStatus.Working(0, 0)
        job = scope.launch {
            status = try {
                ExportStatus.Saved(uri, work { done, total -> status = ExportStatus.Working(done, total, (status as? ExportStatus.Working)?.cancelling == true) })
            } catch (e: CancellationException) {
                status = ExportStatus.Stopped("Cancelled. Nothing was saved.")
                throw e
            } catch (e: DocumentException) {
                ExportStatus.Stopped(e.error.detail)
            }
        }
    }

    fun cancel() {
        (status as? ExportStatus.Working)?.let { status = it.copy(cancelling = true) }
        job?.cancel()
    }

    fun clear() {
        if (status !is ExportStatus.Working) status = ExportStatus.Idle
    }

    fun report(message: String) {
        status = ExportStatus.Stopped(message)
    }
}

/** Progress and Cancel while working, the result afterwards, and [actions] the rest of the time. */
@Composable
internal fun ExportBar(status: ExportStatus, onCancel: () -> Unit, onOpen: (String) -> Unit, actions: @Composable () -> Unit) {
    EzActionPanel {
        when (status) {
            is ExportStatus.Working -> {
                val label = when {
                    status.cancelling -> "Cancelling…"
                    status.total > 0 -> "Page ${status.done} of ${status.total}"
                    else -> "Preparing…"
                }
                EzProgressRow(
                    label,
                    if (status.total > 0) status.done.toFloat() / status.total else null,
                    onCancel.takeIf { !status.cancelling },
                )
            }
            is ExportStatus.Saved -> {
                val files = LocalDocumentFiles.current
                val delivery = rememberDocumentDeliveryActions()
                var savedRef by remember(status.uri) { mutableStateOf<DocumentRef?>(null) }
                var deliveryNote by remember(status.uri) { mutableStateOf<String?>(null) }
                LaunchedEffect(status.uri, files) {
                    try {
                        savedRef = files.refOf(status.uri)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        deliveryNote = "Open the saved file again to share or print it."
                    }
                }
                FeedbackOnAppear(status.uri) { confirm() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ScanIcons.Check, null, tint = EzColors.Positive, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                    Text(status.message, style = MaterialTheme.typography.bodyMedium, color = EzColors.Text, modifier = Modifier.weight(1f))
                    EzTextButton("Open", { onOpen(status.uri) }, enabled = !delivery.busy)
                }
                savedRef?.let { ref ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EzTextButton("Share", { delivery.share(listOf(ref)) }, enabled = !delivery.busy)
                        if (delivery.canPrint(ref)) EzTextButton("Print", { delivery.print(ref) }, enabled = !delivery.busy)
                    }
                }
                deliveryNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary) }
                actions()
            }
            is ExportStatus.Stopped -> {
                Text(status.message, style = MaterialTheme.typography.bodyMedium, color = EzColors.Accent)
                actions()
            }
            ExportStatus.Idle -> actions()
        }
    }
}

/** A tertiary action in a tool. */
@Composable
internal fun TextAction(label: String, onClick: () -> Unit) {
    EzTextButton(label, onClick)
}

/** A section of a tool's settings: a small label over its controls. */
@Composable
internal fun SettingLabel(text: String) {
    EzSettingLabel(text)
}

/** A round colour choice with a ring when selected. */
@Composable
internal fun ColorSwatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .semantics { contentDescription = name }
            .selectable(selected = selected, role = Role.RadioButton, onClick = rememberHapticChoice(selected, onClick))
            .padding(4.dp)
            .border(if (selected) 3.dp else 1.dp, if (selected) EzColors.Primary else EzColors.PageOutline, CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(color),
    )
}
