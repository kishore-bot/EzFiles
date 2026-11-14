package com.ezfiles.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import com.ezfiles.reader.application.assistant.AssistantAccess
import com.ezfiles.reader.application.assistant.AssistantActions
import com.ezfiles.reader.application.assistant.AssistantNavigation
import com.ezfiles.reader.data.BookmarksStore
import com.ezfiles.reader.data.EngineDocumentText
import com.ezfiles.reader.data.IosDeviceDocuments
import com.ezfiles.reader.data.IosDocumentAccess
import com.ezfiles.reader.data.IosPdfTools
import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.data.RecentDocumentsStore
import com.ezfiles.reader.data.RustDocumentReader
import com.ezfiles.reader.data.SignatureStore
import com.ezfiles.reader.platform.IosAppInfo
import com.ezfiles.reader.platform.IosDocumentFiles
import com.ezfiles.reader.platform.NoCrashReports
import com.ezfiles.reader.platform.IosPdfConverter
import com.ezfiles.reader.platform.IosPdfCreator
import com.ezfiles.reader.platform.IosPdfOpener
import com.ezfiles.reader.presentation.theme.ThemeSettings
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.PickedDocuments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIViewController
import platform.posix.close

/** Entry point for `iosApp`: called from Swift as `MainViewControllerKt.MainViewController()`. */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val openRequest by IosAppRuntime.openRequest.collectAsState()
    App(
        dependencies = IosAppRuntime.dependencies,
        openRequest = openRequest,
        onOpenRequestHandled = { IosAppRuntime.openRequest.value = null },
    )
}

/** One graph for the reader and App Intents, including a cold Siri launch. */
internal object IosAppRuntime {
    val openRequest = MutableStateFlow<DocumentRef?>(null)
    val dependencies: AppDependencies by lazy { createDependencies() }
}

private fun createDependencies(): AppDependencies {
    val defaults = NSUserDefaults.standardUserDefaults
    val storage = object : KeyValueStorage {
        override fun get(key: String): String? = defaults.stringForKey(key)

        override fun put(key: String, value: String?) {
            if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
        }
    }
    val access = IosDocumentAccess()
    val recents = RecentDocumentsStore(storage, ::currentTimeMillis)
    val bookmarks = BookmarksStore(storage)
    val documentText = EngineDocumentText(access, IosPdfOpener)
    val assistantAccess = AssistantAccess(storage)
    return AppDependencies(
        reader = RustDocumentReader(access),
        recents = recents,
        device = IosDeviceDocuments,
        pdf = IosPdfOpener,
        files = IosDocumentFiles,
        pdfCreator = IosPdfCreator,
        pdfTools = IosPdfTools,
        pdfConverter = IosPdfConverter,
        signatures = SignatureStore(storage),
        bookmarks = bookmarks,
        themeSettings = ThemeSettings(storage),
        crashReports = NoCrashReports,
        appInfo = IosAppInfo,
        documentText = documentText,
        assistantAccess = assistantAccess,
        assistantActions = AssistantActions(
            device = IosDeviceDocuments,
            recents = recents,
            access = assistantAccess,
            opaqueId = ::iosAssistantDocumentId,
            validateRef = { ref ->
                withContext(Dispatchers.IO) {
                    try {
                        close(access.openDescriptor(ref, com.ezfiles.reader.domain.ReadLimits.Default))
                        PickedDocuments.matchesVersion(ref)
                    } catch (_: Exception) {
                        false
                    }
                }
            },
            documentText = documentText,
            bookmarks = bookmarks,
        ),
        assistantNavigation = AssistantNavigation(),
    )
}

/** Hash the stable picker identity; never publish bookmark bytes or a sandbox path. */
@OptIn(ExperimentalForeignApi::class)
private fun iosAssistantDocumentId(ref: DocumentRef): String {
    val bytes = ("ezfiles-document:" + ref.identity.ifEmpty { ref.uri }).encodeToByteArray()
    val digest = ByteArray(32)
    bytes.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(input.addressOf(0), bytes.size.toUInt(), output.addressOf(0).reinterpret())
        }
    }
    return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
