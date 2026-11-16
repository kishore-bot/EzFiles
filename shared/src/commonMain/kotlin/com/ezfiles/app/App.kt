package com.ezfiles.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.ezfiles.reader.application.DocumentGlancer
import com.ezfiles.reader.presentation.glance.LocalGlancer
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.ezfiles.reader.application.assistant.AssistantAccess
import com.ezfiles.reader.application.assistant.AssistantActions
import com.ezfiles.reader.application.assistant.AssistantAction
import com.ezfiles.reader.application.assistant.AssistantDestination
import com.ezfiles.reader.application.assistant.AssistantNavigation
import com.ezfiles.reader.application.assistant.AssistantDraft
import com.ezfiles.reader.application.assistant.LocalAssistantDraft
import com.ezfiles.reader.application.assistant.AssistantTidyActions
import com.ezfiles.reader.presentation.pdftools.LocalPdfToolRequest
import com.ezfiles.reader.presentation.pdftools.LocalPdfToolRequestId
import com.ezfiles.reader.presentation.pdftools.LocalPdfToolsPreselectList
import com.ezfiles.reader.presentation.bookmarks.newBookmark
import com.ezfiles.reader.platform.rememberMicPermission
import com.ezfiles.reader.presentation.assistant.VoiceAssistantSheet
import com.ezfiles.reader.presentation.components.GlassIconButton
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.viewers.LocalReaderControl
import com.ezfiles.reader.presentation.viewers.PdfExitGuard
import com.ezfiles.reader.presentation.viewers.PhotoGallerySession
import com.ezfiles.reader.presentation.viewers.PhotoGalleryState
import com.ezfiles.reader.presentation.viewers.LocalPhotoGallerySession
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.platform.rememberDocumentDelivery
import com.ezfiles.reader.presentation.components.EzTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.ezfiles.reader.platform.ReportStartupReady
import com.ezfiles.reader.platform.rememberReducedMotion
import com.ezfiles.reader.presentation.theme.LocalReducedMotion
import androidx.compose.animation.core.tween
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ezfiles.reader.application.DocumentReadingService
import com.ezfiles.reader.application.SensitiveCheck
import com.ezfiles.reader.application.TidyService
import com.ezfiles.reader.presentation.tidy.LocalSensitiveCheck
import com.ezfiles.reader.presentation.tidy.LocalTidy
import com.ezfiles.reader.crash.CrashBreadcrumbs
import com.ezfiles.reader.platform.AppInfo
import com.ezfiles.reader.platform.CrashReports
import com.ezfiles.reader.platform.LocalAppInfo
import com.ezfiles.reader.platform.LocalCrashReports
import com.ezfiles.reader.presentation.about.AboutPages
import com.ezfiles.reader.presentation.about.AboutRoute
import com.ezfiles.reader.presentation.about.CrashReportPrompt
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.ContentSearch
import com.ezfiles.reader.domain.DeviceDocuments
import com.ezfiles.reader.domain.DocumentText
import com.ezfiles.reader.domain.FileNameSearch
import com.ezfiles.reader.domain.NoDocumentText
import com.ezfiles.reader.domain.SubstringFileNameSearch
import com.ezfiles.reader.domain.DocumentReader
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfCreator
import com.ezfiles.reader.domain.PdfTools
import com.ezfiles.reader.platform.DocumentEditor
import com.ezfiles.reader.platform.DocumentFiles
import com.ezfiles.reader.platform.LocalDocumentEditor
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.presentation.create.CreateSheet
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import com.ezfiles.reader.presentation.create.CreateTool
import com.ezfiles.reader.presentation.create.CreateToolScreen
import com.ezfiles.reader.presentation.create.LocalPdfCreator
import com.ezfiles.reader.presentation.pdftools.LocalPdfTools
import com.ezfiles.reader.presentation.pdftools.LocalPdfToolsPreselect
import com.ezfiles.reader.domain.RecentDocuments
import com.ezfiles.reader.domain.Bookmarks
import com.ezfiles.reader.data.memoryBookmarks
import com.ezfiles.reader.presentation.bookmarks.LocalBookmarks
import com.ezfiles.reader.presentation.bookmarks.BookmarksScreen
import com.ezfiles.reader.platform.LocalPdfConverter
import com.ezfiles.reader.platform.LocalPdfOpener
import com.ezfiles.reader.platform.LocalSavedSignatures
import com.ezfiles.reader.platform.MemoryPressure
import com.ezfiles.reader.platform.PdfConverter
import com.ezfiles.reader.platform.PdfOpener
import com.ezfiles.reader.platform.SavedSignatures
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.platform.rememberDocumentPicker
import com.ezfiles.reader.presentation.ReaderAction
import com.ezfiles.reader.presentation.ReaderScreen
import com.ezfiles.reader.presentation.ReaderUiState
import com.ezfiles.reader.presentation.ReaderViewModel
import com.ezfiles.reader.presentation.library.LibraryScreen
import com.ezfiles.reader.presentation.library.LibraryViewModel
import com.ezfiles.reader.presentation.theme.EzTheme
import com.ezfiles.reader.presentation.theme.LocalHapticsEnabled
import com.ezfiles.reader.presentation.theme.ThemePreference
import com.ezfiles.reader.presentation.theme.ThemeSettings
import com.ezfiles.reader.platform.SystemBarAppearance
import androidx.compose.foundation.isSystemInDarkTheme
import com.ezfiles.reader.presentation.viewers.LocalDocumentResource
import com.ezfiles.reader.presentation.viewers.LocalSlideImages
import com.ezfiles.reader.presentation.viewers.SlideImageCache
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The object graph, built by each platform entry point (manual DI, ADR-010). */
class AppDependencies internal constructor(
    internal val reader: DocumentReader,
    internal val recents: RecentDocuments,
    internal val device: DeviceDocuments,
    internal val pdf: PdfOpener,
    internal val files: DocumentFiles,
    internal val pdfCreator: PdfCreator,
    internal val pdfTools: PdfTools,
    internal val pdfConverter: PdfConverter,
    internal val signatures: SavedSignatures,
    internal val bookmarks: Bookmarks = memoryBookmarks(),
    internal val themeSettings: ThemeSettings,
    internal val crashReports: CrashReports,
    internal val appInfo: AppInfo,
    val assistantAccess: AssistantAccess,
    val assistantActions: AssistantActions,
    val assistantNavigation: AssistantNavigation = AssistantNavigation(),
    val assistantTidy: AssistantTidyActions? = null,
    internal val startupReady: StateFlow<Boolean> = MutableStateFlow(true),
    /** Plain text of a document, for offline features that look at what files say. */
    internal val documentText: DocumentText = NoDocumentText,
    /** Typo-tolerant ranking of file names in the library's search. */
    internal val nameSearch: FileNameSearch = SubstringFileNameSearch,
    /** Search inside the library's files; null where it isn't available. */
    internal val contentSearch: ContentSearch? = null,
    /** Tidy up and the reader's sensitive-file hint; absent where the platform has no folders (iOS). */
    internal val tidy: TidyService? = null,
    internal val sensitiveCheck: SensitiveCheck? = null,
    /** The reader's "At a glance" sheet; absent where there is no document text. */
    internal val glancer: DocumentGlancer? = null,
    internal val documentEditor: DocumentEditor? = null,
    internal val modelManager: com.ezfiles.reader.application.assistant.ModelManager? = null,
    internal val filySession: com.ezfiles.reader.application.assistant.FilySession? = null,
    /** The viewer on screen, as the assistant may drive it. */
    internal val readerControl: com.ezfiles.reader.application.assistant.ReaderControl? = null,
)

@OptIn(ExperimentalTime::class)
internal fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

private val OriginSaver = listSaver<DocumentRef?, Any?>(
    save = { ref -> if (ref == null) emptyList() else listOf(ref.uri, ref.displayName, ref.sizeBytes, ref.modifiedAt, ref.identity) },
    restore = { saved ->
        saved.takeIf { it.isNotEmpty() }?.let { DocumentRef(it[0] as String, it[1] as String, it[2] as Long?, it[3] as Long?, it[4] as String) }
    },
)

/**
 * Root composable: the library when no document is open, the reader
 * otherwise. Navigation is driven by the reader's state, so there is a single
 * source of truth and no navigation library.
 */
@Composable
fun App(
    dependencies: AppDependencies,
    /** A document another app asked us to open, until [onOpenRequestHandled]. */
    openRequest: DocumentRef? = null,
    onOpenRequestHandled: () -> Unit = {},
) {
    val reader = viewModel { ReaderViewModel(DocumentReadingService(dependencies.reader, recents = dependencies.recents)) }
    val library = viewModel {
        LibraryViewModel(
            dependencies.recents,
            dependencies.device,
            nameSearch = dependencies.nameSearch,
            contentSearch = dependencies.contentSearch,
        )
    }
    val state by reader.state.collectAsStateWithLifecycle()
    val libraryStartupReady by remember(dependencies) {
        combine(dependencies.startupReady, dependencies.device.scanning, dependencies.device.files, dependencies.device.folders) {
            cached, scanning, files, folders -> cached && (!scanning || files.isNotEmpty() || folders.isEmpty())
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)
    val startupScreen = (state as? ReaderUiState.SelectingFile)?.previous ?: state
    ReportStartupReady(when (startupScreen) {
        ReaderUiState.Idle -> libraryStartupReady
        is ReaderUiState.Ready, is ReaderUiState.Playing, is ReaderUiState.Failed,
        is ReaderUiState.PasswordNeeded, is ReaderUiState.Cancelled -> true
        else -> false
    })
    val galleryState = viewModel { PhotoGalleryState() }
    var photoGallery by galleryState.session
    LaunchedEffect(state is ReaderUiState.Idle) {
        if (state is ReaderUiState.Idle) photoGallery = null
    }
    val pickedAction: (ReaderAction) -> Unit = { action ->
        if (action is ReaderAction.DocumentPicked) photoGallery = null
        reader.onAction(action)
    }
    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            photoGallery = null
            reader.onAction(ReaderAction.DocumentPicked(openRequest))
            onOpenRequestHandled()
        }
    }
    LaunchedEffect(dependencies.modelManager) {
        val mm = dependencies.modelManager ?: return@LaunchedEffect
        if (mm.state.value == com.ezfiles.reader.application.assistant.ModelState.Idle) {
            mm.load(
                modelPath = "/data/local/tmp/Fily-EzFiles-350M-Q8_0.gguf",
                preferGpu = true,
            )
        }
    }

    val appScope = rememberCoroutineScope()
    val themePreference by dependencies.themeSettings.preference.collectAsStateWithLifecycle()
    val hapticsOn by dependencies.themeSettings.haptics.collectAsStateWithLifecycle()
    val assistantOn by dependencies.assistantAccess.enabled.collectAsStateWithLifecycle()
    val assistantContentOn by dependencies.assistantAccess.contentAccess.collectAsStateWithLifecycle()
    val assistantDestination by dependencies.assistantNavigation.pending.collectAsStateWithLifecycle()
    val darkTheme = themePreference.isDark(isSystemInDarkTheme())
    val reducedMotion = rememberReducedMotion()
    val bookmarksContent: @Composable () -> Unit = {
        BookmarksScreen(onOpen = { reader.onAction(ReaderAction.DocumentPicked(it.ref, at = it.location)) })
    }

    EzTheme(darkTheme) {
        SystemBarAppearance(darkTheme, appDark = themePreference.takeUnless { it == ThemePreference.System }?.let { it == ThemePreference.Dark })
        CompositionLocalProvider(
            LocalReducedMotion provides reducedMotion,
            LocalHapticsEnabled provides hapticsOn,
            LocalPdfOpener provides dependencies.pdf,
            LocalDocumentFiles provides dependencies.files,
            LocalPdfCreator provides dependencies.pdfCreator,
            LocalPdfTools provides dependencies.pdfTools,
            LocalPdfConverter provides dependencies.pdfConverter,
            LocalSavedSignatures provides dependencies.signatures,
            LocalBookmarks provides dependencies.bookmarks,
            LocalCrashReports provides dependencies.crashReports,
            LocalAppInfo provides dependencies.appInfo,
            LocalTidy provides dependencies.tidy,
            LocalSensitiveCheck provides dependencies.sensitiveCheck,
            LocalGlancer provides dependencies.glancer,
            LocalDocumentEditor provides dependencies.documentEditor,
            LocalReaderControl provides dependencies.readerControl,
        ) {
            PickerHost(state, pickedAction)
            // Fily stays open across screens, so "open my resume" then "next page" is one conversation.
            var voiceOpen by rememberSaveable { mutableStateOf(false) }
            val micPermission = rememberMicPermission { granted -> if (granted) voiceOpen = true }
            val openVoice: (() -> Unit)? = if (dependencies.modelManager != null && dependencies.filySession != null) {
                { if (micPermission.granted) voiceOpen = true else micPermission.request() }
            } else null
            val openDocument = (state as? ReaderUiState.Ready)?.ref ?: (state as? ReaderUiState.Playing)?.ref
            LaunchedEffect(openDocument) {
                dependencies.filySession?.openDocumentId = openDocument?.let(dependencies.assistantActions::documentId)
            }
            var tool by rememberSaveable { mutableStateOf<CreateTool?>(null) }
            var createSheet by rememberSaveable { mutableStateOf(false) }
            // About agent: the About and Why EzFiles pages, over the library like a tool.
            var aboutRoute by rememberSaveable { mutableStateOf<AboutRoute?>(null) }
            val delivery = rememberDocumentDelivery()
            var assistantReview by remember { mutableStateOf<AssistantDestination?>(null) }
            var assistantError by remember { mutableStateOf<String?>(null) }
            var assistantBusy by remember { mutableStateOf(false) }
            var assistantToolRequest by remember { mutableStateOf<AssistantDestination?>(null) }
            var readerExitGuard by remember { mutableStateOf<PdfExitGuard?>(null) }
            LaunchedEffect(assistantOn, assistantContentOn) {
                assistantReview?.takeIf { it.authorization != dependencies.assistantAccess.generation }?.let { assistantReview = null }
                if (!assistantOn) dependencies.assistantNavigation.clear()
            }
            val navigationHaptics = rememberEzHaptics()
            val openAbout: () -> Unit = { navigationHaptics.navigate(); aboutRoute = AboutRoute.About }
            val sendFeedback: () -> Unit = { dependencies.appInfo.sendFeedback() }
            // The PDF that PDF tools was opened from: tools start with it, and closing them goes back to it.
            var toolOrigin by rememberSaveable(stateSaver = OriginSaver) { mutableStateOf<DocumentRef?>(null) }
            val closeTool = {
                if (tool != null) navigationHaptics.navigate()
                tool = null
                assistantToolRequest = null
                toolOrigin?.let { reader.onAction(ReaderAction.DocumentPicked(it)) }
                toolOrigin = null
            }
            LaunchedEffect(assistantDestination) {
                val destination = assistantDestination ?: return@LaunchedEffect
                try {
                    if (tool != null || (state as? ReaderUiState.Ready)?.editing == true) {
                        assistantError = "Finish the open editor or tool before starting an assistant action. Your work has been kept."
                        return@LaunchedEffect
                    }
                    dependencies.assistantAccess.recheckAuthorization(destination.authorization)
                    val openId = (state as? ReaderUiState.Ready)?.openId
                    val exitGuard = readerExitGuard
                    if (exitGuard != null && openId != null && destination.readerExitApprovedFor != openId) {
                        exitGuard.leave {
                            if (runCatching { dependencies.assistantAccess.recheckAuthorization(destination.authorization) }.isSuccess) {
                                dependencies.assistantNavigation.request(destination.copy(readerExitApprovedFor = openId))
                            } else assistantError = "This assistant request expired."
                        }
                        return@LaunchedEffect
                    }
                    val ref = destination.ref?.let { dependencies.assistantActions.revalidateDocument(it) }
                    val documents = destination.documents.map { dependencies.assistantActions.revalidateDocument(it) }
                    dependencies.assistantAccess.recheckAuthorization(destination.authorization)
                    when (destination.action) {
                        AssistantAction.Open -> {
                            requireNotNull(ref)
                            aboutRoute = null
                            tool = null
                            photoGallery = PhotoGallerySession.create(ref, dependencies.device.files.value)
                            reader.onAction(ReaderAction.DocumentPicked(ref, at = destination.bookmarkLocation))
                        }
                        AssistantAction.Share, AssistantAction.Print, AssistantAction.Bookmark -> assistantReview = destination.copy(ref = requireNotNull(ref), documents = documents)
                        AssistantAction.Tool -> {
                            val selected = CreateTool.entries.firstOrNull { it.name == destination.tool }
                                ?: error("This tool is unavailable.")
                            aboutRoute = null
                            toolOrigin = ref
                            assistantToolRequest = destination.copy(ref = ref, documents = documents)
                            tool = selected
                            reader.onAction(ReaderAction.CloseDocument)
                            if (destination.startScan) {
                                require(selected == CreateTool.TidyUp)
                                requireNotNull(dependencies.tidy) { "Tidy Up is unavailable." }.scan()
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    assistantError = "This assistant request expired or its file is no longer available."
                } finally {
                    dependencies.assistantNavigation.consume(destination)
                }
            }
            assistantReview?.let { destination ->
                val ref = requireNotNull(destination.ref)
                val label = when (destination.action) {
                    AssistantAction.Print -> "Print"
                    AssistantAction.Bookmark -> "Add bookmark"
                    else -> "Share"
                }
                AlertDialog(
                    onDismissRequest = { if (!assistantBusy) assistantReview = null },
                    title = { Text(if (destination.action == AssistantAction.Bookmark) "Add bookmark?"
                        else if (destination.documents.size > 1) "$label ${destination.documents.size} documents?" else "$label document?") },
                    text = {
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            (destination.documents.ifEmpty { listOf(ref) }).forEach { Text(it.displayName) }
                            Text(if (destination.action == AssistantAction.Bookmark) "Add a bookmark on page ${destination.pageNumber}?"
                                else "Choose the destination in the system $label screen.")
                        }
                    },
                    confirmButton = {
                        EzTextButton(if (assistantBusy) "Preparing…" else label, {
                            if (!assistantBusy) appScope.launch {
                                assistantBusy = true
                                try {
                                    dependencies.assistantAccess.authorized {
                                        dependencies.assistantAccess.recheckAuthorization(destination.authorization)
                                        val current = dependencies.assistantActions.revalidateDocument(ref)
                                        dependencies.assistantAccess.recheckAuthorization(destination.authorization)
                                        when (destination.action) {
                                            AssistantAction.Print -> delivery.print(current)
                                            AssistantAction.Bookmark -> {
                                                val page = requireNotNull(destination.pageNumber)
                                                withContext(Dispatchers.IO) {
                                                    val pages = dependencies.pdf.open(current, null)
                                                    try { require(page in 1..pages.pageCount) { "Choose an existing PDF page." } } finally { pages.close() }
                                                }
                                                dependencies.assistantAccess.recheckAuthorization(destination.authorization)
                                                dependencies.bookmarks.add(newBookmark(current, DocumentFormat.Pdf, BookmarkLocation.Page(page - 1)))
                                                reader.onAction(ReaderAction.DocumentPicked(current, at = BookmarkLocation.Page(page - 1)))
                                            }
                                            else -> delivery.share(if (destination.documents.isEmpty()) listOf(current)
                                                else destination.documents.map { dependencies.assistantActions.revalidateDocument(it) })
                                        }
                                    }
                                    assistantReview = null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    assistantReview = null
                                    assistantError = "This request could not be completed. Check assistant access and choose the file again."
                                } finally {
                                    assistantBusy = false
                                }
                            }
                        })
                    },
                    dismissButton = { EzTextButton("Cancel", { if (!assistantBusy) assistantReview = null }) },
                )
            }
            assistantError?.let { message ->
                AlertDialog(onDismissRequest = { assistantError = null }, title = { Text("Assistant request") },
                    text = { Text(message) }, confirmButton = { EzTextButton("OK", { assistantError = null }) })
            }
            val shown = (state as? ReaderUiState.SelectingFile)?.previous ?: state
            // A file handed over by another app goes straight to the reader, without flashing the library.
            val inLibrary = shown is ReaderUiState.Idle && openRequest == null
            // About agent: where the user was, for a crash report. A screen and a format, never a file.
            SideEffect {
                CrashBreadcrumbs.screen = when {
                    !inLibrary -> "reader" + ((shown as? ReaderUiState.Ready)?.info?.format?.let { " (${it.name})" } ?: "")
                    tool != null -> "tool ${tool?.name}"
                    aboutRoute != null -> "about"
                    else -> "library"
                }
            }
            PlatformBackHandler(enabled = tool != null && inLibrary, onBack = closeTool)
            PlatformBackHandler(enabled = !inLibrary) {
                // Back closes an open search before it leaves the document.
                val searching = (shown as? ReaderUiState.Ready)?.search != null
                if (!searching) navigationHaptics.navigate()
                reader.onAction(if (searching) ReaderAction.CloseSearch else ReaderAction.CloseDocument)
            }
            AnimatedContent(
                targetState = inLibrary,
                transitionSpec = {
                    if (reducedMotion) fadeIn(tween(100)) togetherWith fadeOut(tween(100))
                    else (fadeIn(spring()) + scaleIn(spring(dampingRatio = 0.8f), initialScale = 0.96f)) togetherWith fadeOut(spring())
                },
                label = "screen",
            ) { libraryVisible ->
                if (libraryVisible) {
                    // A tool replaces the library, whose controls must not stay reachable behind it.
                    if (tool == null && aboutRoute == null) {
                        LibraryScreen(
                            viewModel = library,
                            onOpenFile = { navigationHaptics.navigate(); reader.onAction(ReaderAction.PickDocument) },
                            onCreate = { createSheet = true },
                            onOpenRecent = {
                                navigationHaptics.navigate()
                                photoGallery = PhotoGallerySession.create(it, dependencies.device.files.value)
                                reader.onAction(ReaderAction.DocumentPicked(it))
                            },
                            onOpenAbout = openAbout,
                            onVoice = openVoice,
                            bookmarksContent = bookmarksContent,
                            // A PDF opens at the page the match is on, like a bookmark.
                            onOpenHit = { ref, page ->
                                navigationHaptics.navigate()
                                photoGallery = PhotoGallerySession.create(ref, dependencies.device.files.value)
                                reader.onAction(ReaderAction.DocumentPicked(ref, at = page?.let(BookmarkLocation::Page)))
                            },
                        )
                    }
                } else {
                    // One picture cache per open document.
                    val ref = (shown as? ReaderUiState.Ready)?.ref
                    val images = remember(ref) { SlideImageCache(reader::loadResource) }
                    DisposableEffect(images) {
                        val unregister = MemoryPressure.register(images::trimMemory)
                        onDispose {
                            unregister()
                            images.close()
                        }
                    }
                    // ZIP entry extraction: save the extracted bytes when the user picks a location.
                    val extractScope = rememberCoroutineScope()
                    val extractFiles = LocalDocumentFiles.current
                    var extractPending by remember { mutableStateOf<String?>(null) }
                    val extractSaver = rememberDocumentSaver { uri ->
                        if (uri != null) {
                            val entry = reader.extractedEntry.value ?: return@rememberDocumentSaver
                            extractScope.launch(Dispatchers.IO) {
                                runCatching { extractFiles.write(uri, entry.bytes) }
                                reader.clearExtractedEntry()
                            }
                        } else reader.clearExtractedEntry()
                        extractPending = null
                    }
                    val extractedState = reader.extractedEntry.collectAsStateWithLifecycle()
                    val pendingName = extractedState.value?.name
                    if (pendingName != null && extractPending != pendingName) {
                        extractPending = pendingName
                        extractSaver.launch(pendingName, guessMimeType(pendingName))
                    }
                    CompositionLocalProvider(LocalSlideImages provides images, LocalDocumentResource provides reader::loadResource,
                        LocalPhotoGallerySession provides photoGallery) {
                        val darkReading by reader.darkReading.collectAsStateWithLifecycle()
                        ReaderScreen(shown, { action ->
                            if (action == ReaderAction.CloseDocument) navigationHaptics.navigate()
                            pickedAction(action)
                        }, onExitGuardChange = { readerExitGuard = it }, darkReading = darkReading, onPdfTools = { open ->
                            navigationHaptics.navigate()
                            assistantToolRequest = null
                            toolOrigin = open
                            tool = CreateTool.PdfTools
                            reader.onAction(ReaderAction.CloseDocument)
                        }, trailingActions = {
                            if (openVoice != null) GlassIconButton(EzIcons.Mic, "Voice assistant", openVoice)
                        })
                    }
                }
            }
            // A tool covers the library; a saved document opens the reader over both.
            tool?.takeIf { inLibrary }?.let { current ->
                val request = assistantToolRequest
                CompositionLocalProvider(
                    LocalPdfToolsPreselect provides toolOrigin,
                    LocalPdfToolsPreselectList provides request?.documents.orEmpty(),
                    LocalPdfToolRequest provides request?.pdfTool,
                    LocalPdfToolRequestId provides (request?.requestSequence ?: 0L),
                    LocalAssistantDraft provides request?.draftText?.let { AssistantDraft(it, request.draftTitle.orEmpty(), request.requestSequence) },
                ) {
                    key(request?.requestSequence ?: 0L) {
                    CreateToolScreen(
                        tool = current,
                        onClose = closeTool,
                        onSwitchTool = { if (tool != it) navigationHaptics.navigate(); assistantToolRequest = null; tool = it },
                        onOpen = { ref ->
                            photoGallery = null
                            // Tidy up stays underneath, so closing the file goes back to its results.
                            if (current != CreateTool.TidyUp) {
                                tool = null
                                toolOrigin = null
                                assistantToolRequest = null
                            }
                            reader.onAction(ReaderAction.DocumentPicked(ref))
                        },
                    )
                    }
                }
            }
            // About agent: the About pages cover the library; a document opened meanwhile shows over them.
            aboutRoute?.takeIf { inLibrary && tool == null }?.let { route ->
                AboutPages(
                    route,
                    onRouteChange = { if (aboutRoute != it) navigationHaptics.navigate(); aboutRoute = it },
                    onSendFeedback = sendFeedback,
                    theme = themePreference,
                    onThemeChange = dependencies.themeSettings::set,
                    haptics = hapticsOn,
                    onHapticsChange = dependencies.themeSettings::setHaptics,
                    assistantEnabled = assistantOn,
                    onAssistantChange = dependencies.assistantAccess::setEnabled,
                    contentEnabled = assistantContentOn,
                    onContentChange = dependencies.assistantAccess::setContentAccess,
                    onClearSearchIndex = dependencies.contentSearch?.let { search -> { appScope.launch { search.clear() } } },
                )
            }
            // About agent: after a crash, offers to send the saved report.
            CrashReportPrompt()
            if (createSheet) {
                CreateSheet(
                    onSelect = {
                        navigationHaptics.navigate()
                        createSheet = false
                        assistantToolRequest = null
                        tool = it
                    },
                    onDismiss = { createSheet = false },
                )
            }
            val model = dependencies.modelManager
            val session = dependencies.filySession
            if (voiceOpen && model != null && session != null) {
                VoiceAssistantSheet(
                    onDismiss = {
                        voiceOpen = false
                        session.clear()
                    },
                    modelManager = model,
                    session = session,
                )
            }
        }
    }
}

private fun guessMimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "txt", "text" -> "text/plain"
    "pdf" -> "application/pdf"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "html", "htm" -> "text/html"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "csv" -> "text/csv"
    "zip" -> "application/zip"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    else -> "application/octet-stream"
}

/** Launches the system picker whenever the state asks for it. */
@Composable
private fun PickerHost(state: ReaderUiState, onAction: (ReaderAction) -> Unit) {
    // Survives activity recreation so a picker already showing is not launched twice.
    var pickerShowing by rememberSaveable { mutableStateOf(false) }
    val picker = rememberDocumentPicker { ref ->
        pickerShowing = false
        onAction(if (ref == null) ReaderAction.PickerDismissed else ReaderAction.DocumentPicked(ref))
    }
    if (state is ReaderUiState.SelectingFile && !pickerShowing) {
        LaunchedEffect(Unit) {
            pickerShowing = true
            picker.launch()
        }
    }
}
