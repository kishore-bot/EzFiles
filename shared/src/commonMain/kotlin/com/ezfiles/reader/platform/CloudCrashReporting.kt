package com.ezfiles.reader.platform

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface CloudCrashReporting {
    val available: Boolean
    val enabled: StateFlow<Boolean>
    /** SDK activation is fixed at process startup, after stored consent. */
    val active: Boolean
    suspend fun setEnabled(enabled: Boolean)
}

object NoCloudCrashReporting : CloudCrashReporting {
    override val available = false
    override val enabled: StateFlow<Boolean> = MutableStateFlow(false)
    override val active = false
    override suspend fun setEnabled(enabled: Boolean) {}
}

val LocalCloudCrashReporting = staticCompositionLocalOf<CloudCrashReporting> { NoCloudCrashReporting }
