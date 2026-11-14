package com.ezfiles.reader.presentation.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.platform.LocalCloudCrashReporting
import com.ezfiles.reader.presentation.components.EzDialog
import com.ezfiles.reader.presentation.components.EzSwitchRow
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CloudCrashReportingCard() {
    val reports = LocalCloudCrashReporting.current
    if (!reports.available) return
    val enabled by reports.enabled.collectAsState()
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun save(value: Boolean) {
        if (saving) return
        saving = true
        failed = false
        scope.launch {
            try { reports.setEnabled(value) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { saving = false }
        }
    }
    Column(Modifier.fillMaxWidth().glass(EzShapes.Card, 0.55f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EzSwitchRow("Share crash diagnostics", "Optional Firebase Crashlytics reporting. Off by default; document reading remains offline.",
            enabled, { if (!saving) { if (it) confirming = true else save(false) } })
        Text(when {
            saving -> "Saving your preference…"
            failed -> "Could not save the preference. Please try again."
            enabled && !reports.active -> "Close and reopen EzFiles to activate reporting for future crashes. Existing local reports can still be shared manually."
            !enabled && reports.active -> "New crash forwarding is off. Close and reopen EzFiles to fully unload diagnostics; reports already sent cannot be recalled."
            enabled -> "Future crash diagnostics may be sent to Firebase. Turn this off at any time."
            else -> "No Firebase initialization or crash uploads while this stays off."
        }, color = if (failed) EzColors.Accent else EzColors.TextSecondary)
    }
    if (confirming) EzDialog(
        onDismissRequest = { confirming = false },
        title = { Text("Allow crash diagnostics?") },
        text = { Text("After you restart EzFiles, Firebase Crashlytics can receive future technical crash diagnostics, app/device information and installation identifiers. Exception messages are scrubbed before forwarding; documents are not attached. The SDK report is different from the local report preview. No Analytics is added. You can turn this off here; already sent reports cannot be recalled.") },
        confirmButton = { EzTextButton("Allow", { confirming = false; save(true) }) },
        dismissButton = { EzTextButton("Keep off", { confirming = false }) },
    )
}
