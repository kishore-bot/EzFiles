package com.ezfiles.reader.presentation.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.application.assistant.AssistantModel
import com.ezfiles.reader.application.assistant.ModelDownloads
import com.ezfiles.reader.application.assistant.ModelInstall
import com.ezfiles.reader.platform.rememberModelFilePicker
import com.ezfiles.reader.presentation.components.EzDialog
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.theme.EzColors

/** The app's model downloads; null where models can't be downloaded (tests, iOS). */
val LocalModelDownloads = staticCompositionLocalOf<ModelDownloads?> { null }

private fun megabytes(bytes: Long) = "${(bytes + 500_000) / 1_000_000} MB"

/** Status and actions for one model: download (with progress), import, cancel, or remove. */
@Composable
fun ModelInstallRow(model: AssistantModel, modifier: Modifier = Modifier) {
    val downloads = LocalModelDownloads.current ?: return
    val state by downloads.state(model).collectAsState()
    val pick = rememberModelFilePicker { uris -> if (uris.isNotEmpty()) downloads.import(model, uris) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.label, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
                Text(
                    when (val s = state) {
                        ModelInstall.Missing -> "Not installed · ${megabytes(model.bytes)} download"
                        is ModelInstall.Downloading -> "Downloading ${megabytes(s.done)} of ${megabytes(s.total)}"
                        ModelInstall.Verifying -> "Checking the download…"
                        ModelInstall.Installed -> "Installed on this phone"
                        is ModelInstall.Failed -> s.message
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is ModelInstall.Failed) EzColors.Accent else EzColors.TextSecondary,
                )
            }
            when (state) {
                ModelInstall.Missing, is ModelInstall.Failed -> {
                    EzTextButton("Download", { downloads.download(model) })
                    EzTextButton("Import", pick)
                }
                is ModelInstall.Downloading, ModelInstall.Verifying -> EzTextButton("Cancel", { downloads.cancel(model) })
                ModelInstall.Installed -> {}
            }
        }
        (state as? ModelInstall.Downloading)?.let { s ->
            LinearProgressIndicator(progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Shown instead of the voice overlay while Fily's model isn't on the phone: a one-time download
 * (or an import of the file), after which [onReady] opens Fily.
 */
@Composable
fun GetFilyDialog(onDismiss: () -> Unit, onReady: () -> Unit) {
    val downloads = LocalModelDownloads.current ?: return
    val state by downloads.state(AssistantModel.Fily).collectAsState()
    LaunchedEffect(state) { if (state == ModelInstall.Installed) onReady() }
    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get Fily") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Fily runs entirely on your phone. Its AI model is a one-time ${megabytes(AssistantModel.Fily.bytes)} " +
                        "download; after that, nothing you ask leaves the phone. Wi-Fi is best.",
                )
                ModelInstallRow(AssistantModel.Fily)
            }
        },
        confirmButton = { EzTextButton("Close", onDismiss) },
    )
}
