package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable

/** iOS is parked: documents arrive through the file picker. */
@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit, onUnavailable: () -> Unit): (() -> Unit)? = null

/** iOS is parked. */
@Composable
actual fun rememberWritableFolderPicker(onPicked: (String) -> Unit, onUnavailable: () -> Unit): (() -> Unit)? = null
