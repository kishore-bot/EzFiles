package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/**
 * Launcher for the system folder picker, or `null` where the platform has none.
 * [onPicked] receives the chosen folder and isn't called when the user backs
 * out. [onUnavailable] runs if the device has no picker to show.
 */
@Composable
expect fun rememberFolderPicker(onPicked: (String) -> Unit, onUnavailable: () -> Unit): (() -> Unit)?

/**
 * Like [rememberFolderPicker], for a folder this app will create files in. The
 * grant lasts for this session and isn't kept: use it through [DocumentOutputs].
 */
@Composable
expect fun rememberWritableFolderPicker(onPicked: (String) -> Unit, onUnavailable: () -> Unit): (() -> Unit)?
