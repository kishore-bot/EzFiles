package com.ezfiles.app

import android.content.Context
import com.ezfiles.reader.application.DocumentGlancer
import com.ezfiles.reader.application.assistant.AssistantAccess
import com.ezfiles.reader.application.assistant.AssistantActions
import com.ezfiles.reader.application.assistant.AssistantNavigation
import com.ezfiles.reader.application.assistant.AssistantTidyActions
import com.ezfiles.reader.application.assistant.FilySession
import com.ezfiles.reader.application.assistant.ModelManager
import com.ezfiles.reader.application.assistant.ReaderControl
import com.ezfiles.reader.application.assistant.ToolExecutor
import com.ezfiles.reader.data.androidDocumentIdentity
import android.net.Uri
import java.security.MessageDigest
import kotlinx.coroutines.withContext
import com.ezfiles.reader.data.RustGlanceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.asCoroutineDispatcher
import com.ezfiles.reader.data.EngineDocumentText
import com.ezfiles.reader.data.LibraryContentSearch
import com.ezfiles.reader.data.RustFileNameSearch
import com.ezfiles.reader.data.RustFullTextIndex
import com.ezfiles.reader.data.AndroidDocumentAccess
import com.ezfiles.reader.data.AndroidDocumentTrees
import com.ezfiles.reader.data.AndroidPdfConverter
import com.ezfiles.reader.data.AndroidPdfCreator
import com.ezfiles.reader.data.AndroidPdfTools
import com.ezfiles.reader.data.BookmarksStore
import com.ezfiles.reader.data.ChosenFolderDocuments
import com.ezfiles.reader.data.FileKeyValueStorage
import com.ezfiles.reader.data.KeyValueStorage
import com.ezfiles.reader.data.RecentDocumentsStore
import com.ezfiles.reader.data.RustDocumentReader
import com.ezfiles.reader.data.SignatureStore
import com.ezfiles.reader.platform.AndroidAppInfo
import com.ezfiles.reader.platform.AndroidCrashReports
import com.ezfiles.reader.platform.AndroidDocumentEditor
import com.ezfiles.reader.platform.AndroidDocumentFiles
import com.ezfiles.reader.platform.AndroidPdfOpener
import com.ezfiles.reader.presentation.theme.ThemeSettings
import com.ezfiles.reader.application.SensitiveCheck
import com.ezfiles.reader.application.TidyService
import com.ezfiles.reader.data.AndroidTidyFiles
import com.ezfiles.reader.data.RustTidyEngine
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/** Entry point for `androidApp`: the object graph for this process. */
fun androidAppDependencies(
    context: Context,
    applicationScope: CoroutineScope,
    assistantAccess: AssistantAccess = androidAssistantAccess(context),
): AppDependencies {
    android.os.Trace.beginSection("EzFiles.dependencies")
    return try {
        createAndroidAppDependencies(context, applicationScope, assistantAccess)
    } finally {
        android.os.Trace.endSection()
    }
}

private fun createAndroidAppDependencies(
    context: Context,
    applicationScope: CoroutineScope,
    assistantAccess: AssistantAccess,
): AppDependencies {
    val app = context.applicationContext
    val preferences = app.getSharedPreferences("ezfiles", Context.MODE_PRIVATE)
    val storage = object : KeyValueStorage {
        override fun get(key: String): String? = preferences.getString(key, null)

        override fun put(key: String, value: String?) {
            preferences.edit().putString(key, value).apply()
        }
    }
    // Bookmarks: in a file of their own, so a long list doesn't slow every other setting's load.
    val bookmarkPrefs = app.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    val bookmarkStorage = object : KeyValueStorage {
        override fun get(key: String): String? = bookmarkPrefs.getString(key, null)

        override fun put(key: String, value: String?) {
            bookmarkPrefs.edit().putString(key, value).apply()
        }
    }
    val access = AndroidDocumentAccess.get(app)
    val reader = RustDocumentReader(access)
    // The library loads while the first screen draws, not when the first file opens.
    reader.warmUp(applicationScope)
    val device = ChosenFolderDocuments(storage, AndroidDocumentTrees(app), scanCache = FileKeyValueStorage(File(app.cacheDir, "library")))
    val pdf = AndroidPdfOpener(app)
    // Search: one background thread at the lowest priority reads files for the index, so it never
    // competes with the screen. Nothing runs until the library has been on screen for a moment.
    val background = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ezfiles-search").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()
    val documentText = EngineDocumentText(access, pdf, background)
    val contentSearch = LibraryContentSearch(
        device = device,
        text = documentText::read,
        // App-private cache: never backed up, and the system may reclaim it (the index is then rebuilt).
        openIndex = { RustFullTextIndex(File(app.cacheDir, "search").path) },
        indexing = background,
        queries = Dispatchers.IO,
    )
    // Tidy up: nothing here touches the disk or starts a thread until the user opens it.
    val tidyEngine = RustTidyEngine()
    val tidyCache = FileKeyValueStorage(File(app.cacheDir, "tidy"))
    // Its own low-priority thread, so a long scan never holds up search indexing or the UI.
    val tidyThread = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tidy").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }.asCoroutineDispatcher()
    val normalizeRef: (com.ezfiles.reader.domain.DocumentRef) -> com.ezfiles.reader.domain.DocumentRef = { ref ->
        ref.copy(identity = androidDocumentIdentity(Uri.parse(ref.uri)))
    }
    val recents = RecentDocumentsStore(storage, ::currentTimeMillis, normalizeRef)
    val bookmarks = BookmarksStore(bookmarkStorage, normalizeRef)
    val assistantActions = AssistantActions(
        device, recents, assistantAccess,
        opaqueId = { ref ->
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(androidDocumentIdentity(Uri.parse(ref.uri)).toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            CharArray(bytes.size * 2) { index ->
                val value = bytes[index / 2].toInt() and 0xff
                hex[if (index % 2 == 0) value ushr 4 else value and 15]
            }.concatToString()
        },
        validateRef = { ref -> validateAssistantDocument(app, ref) },
        documentText = documentText,
        bookmarks = bookmarks,
        contentSearch = contentSearch,
    )
    val tidy = TidyService(device, documentText, AndroidTidyFiles(app), tidyEngine, tidyCache, tidyThread, ::currentTimeMillis)
    val assistantNavigation = AssistantNavigation()
    val assistantTidy = AssistantTidyActions(assistantAccess, device,
        loadReport = { tidy.load(); tidy.report.value }, validateRef = { validateAssistantDocument(app, it) })
    // Compiled GPU kernels are cached here; the engine's default temp folder does not exist on Android.
    android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", File(app.cacheDir, "opencl-kernels").path, true)
    // Debug builds log kernel-cache HIT/MISS/SAVE, to tell first-run compiling from steady state.
    if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DEBUG", "1", true)
    }
    val modelManager = ModelManager(
        storage,
        buildStamp = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime.toString(),
    )
    val readerControl = ReaderControl()
    val modelDownloads = com.ezfiles.reader.data.AndroidModelDownloads(app)
    com.ezfiles.reader.platform.MoonshineSpeech.folder = {
        modelDownloads.folder(com.ezfiles.reader.application.assistant.AssistantModel.Moonshine)
    }
    val filySession = FilySession(
        { prompt, grammar -> modelManager.complete(prompt, grammar) },
        ToolExecutor(assistantActions, assistantNavigation, assistantTidy, readerControl),
        assistantAccess,
        today = { java.time.LocalDate.now().toString() },
        reader = readerControl,
        fileNames = { device.files.value.map { it.displayName } },
        log = if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            { message -> android.util.Log.i("EzFily", message) }
        } else { _ -> },
    )
    return AppDependencies(
        reader = reader,
        recents = recents,
        device = device,
        pdf = pdf,
        files = AndroidDocumentFiles(app),
        pdfCreator = AndroidPdfCreator(app),
        pdfTools = AndroidPdfTools(app),
        pdfConverter = AndroidPdfConverter(app),
        signatures = SignatureStore(storage),
        bookmarks = bookmarks,
        themeSettings = ThemeSettings(storage),
        crashReports = AndroidCrashReports(app),
        appInfo = AndroidAppInfo(app),
        assistantAccess = assistantAccess,
        assistantActions = assistantActions,
        assistantNavigation = assistantNavigation,
        startupReady = device.initialMetadataReady,
        assistantTidy = assistantTidy,
        documentText = documentText,
        nameSearch = RustFileNameSearch,
        contentSearch = contentSearch,
        tidy = tidy,
        // Not on the Tidy thread: a long scan there must not hold up the hint for the open file.
        sensitiveCheck = SensitiveCheck(documentText, tidyEngine, tidyCache, Dispatchers.IO),
        glancer = DocumentGlancer(documentText, RustGlanceEngine, Dispatchers.Default),
        documentEditor = AndroidDocumentEditor(app),
        modelManager = modelManager,
        filySession = filySession,
        readerControl = readerControl,
        speechSettings = com.ezfiles.reader.platform.SpeechSettings(storage),
        playbackMemory = com.ezfiles.reader.data.PlaybackMemory(storage, ::currentTimeMillis),
        modelDownloads = modelDownloads,
    )
}

/** Lightweight policy access: assistant registration must not initialize parsers or the library. */
fun androidAssistantAccess(context: Context): AssistantAccess {
    val preferences = context.applicationContext.getSharedPreferences("ezfiles", Context.MODE_PRIVATE)
    return AssistantAccess(object : KeyValueStorage {
        override fun get(key: String): String? = preferences.getString(key, null)
        override fun put(key: String, value: String?) { preferences.edit().putString(key, value).apply() }
    })
}
