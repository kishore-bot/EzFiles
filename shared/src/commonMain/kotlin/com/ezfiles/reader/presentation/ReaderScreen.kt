package com.ezfiles.reader.presentation

import com.ezfiles.reader.presentation.glance.GlanceSheet
import com.ezfiles.reader.presentation.glance.LocalGlancer
import com.ezfiles.reader.presentation.tidy.SensitiveFileHint
import androidx.compose.foundation.text.selection.SelectionContainer
import com.ezfiles.reader.presentation.components.EzDialog
import com.ezfiles.reader.platform.WarmUpWebView
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.platform.opensInWebView

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.ezfiles.reader.platform.HiddenSystemBars
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.presentation.theme.FeedbackOnAppear
import com.ezfiles.reader.presentation.viewers.LocalImmersive
import com.ezfiles.reader.presentation.viewers.PdfBarActions
import com.ezfiles.reader.presentation.viewers.formatBytes
import com.ezfiles.reader.domain.DocumentInfo
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.ezfiles.reader.platform.LocalDocumentEditor
import com.ezfiles.reader.presentation.components.FindReplaceSheet
import com.ezfiles.reader.presentation.components.SlideToolsSheet
import com.ezfiles.reader.presentation.components.FloatingReaderModeSwitch
import com.ezfiles.reader.presentation.components.LocalReaderScrollActivity
import com.ezfiles.reader.presentation.components.ReaderControlVisibility
import com.ezfiles.reader.presentation.components.isShortWindow
import com.ezfiles.reader.presentation.theme.LocalReducedMotion
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.DarkPaperColors
import com.ezfiles.reader.presentation.theme.LightPaperColors
import com.ezfiles.reader.presentation.theme.LocalPaperColors
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.presentation.viewers.LocalPhotoGallerySession
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentKind
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.BookmarkSpot
import com.ezfiles.reader.presentation.bookmarks.BookmarkBar
import com.ezfiles.reader.presentation.bookmarks.BookmarkToggleButton
import com.ezfiles.reader.presentation.bookmarks.LocalBookmarkBar
import com.ezfiles.reader.presentation.bookmarks.LocalBookmarks
import com.ezfiles.reader.presentation.bookmarks.ReaderBookmarksSheet
import com.ezfiles.reader.presentation.bookmarks.lostBookmark
import com.ezfiles.reader.presentation.components.SecondaryActionButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.rememberDocumentDelivery
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.presentation.tidy.TidyIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzDropdownMenu
import com.ezfiles.reader.presentation.components.EzMenuItem
import com.ezfiles.reader.presentation.components.EzProgressCard
import com.ezfiles.reader.presentation.components.EzSheet
import com.ezfiles.reader.presentation.components.EzStatusCard
import com.ezfiles.reader.presentation.components.EzStatusText
import com.ezfiles.reader.presentation.components.EzTopBar
import com.ezfiles.reader.presentation.theme.glassBar
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.editor.TextEditorScreen
import com.ezfiles.reader.presentation.editor.TextEditorViewModel
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.components.GlassIconButton
import com.ezfiles.reader.presentation.theme.raised
import com.ezfiles.reader.presentation.theme.readerBackground
import com.ezfiles.reader.presentation.theme.appSafeArea
import com.ezfiles.reader.platform.MediaSource
import com.ezfiles.reader.presentation.viewers.DocumentViewer
import com.ezfiles.reader.presentation.viewers.MediaPlayerView
import com.ezfiles.reader.presentation.viewers.PdfExitGuard
import com.ezfiles.reader.presentation.viewers.PdfReader

@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onAction: (ReaderAction) -> Unit,
    onPdfTools: (DocumentRef) -> Unit = {},
    onExitGuardChange: (PdfExitGuard?) -> Unit = {},
    darkReading: Boolean = false,
    /** Extra buttons in the top bar, between search and the menu (one fits on a phone). Shown once a document is open. */
    trailingActions: @Composable RowScope.() -> Unit = {},
) {
    if (state is ReaderUiState.Ready && state.editing) {
        val files = LocalDocumentFiles.current
        val editor = viewModel(key = "reader-editor") { TextEditorViewModel(files) }
        LaunchedEffect(state.ref.uri) { editor.open(state.ref, state.info.byteSize) }
        TextEditorScreen(
            viewModel = editor,
            onFinished = { saved -> onAction(ReaderAction.CloseEditor(saved)) },
            waitingFile = state.pendingOpen?.displayName,
        )
        return
    }
    if (state is ReaderUiState.Playing) {
        // Audio and video take the whole screen; the player has its own close button.
        HiddenSystemBars()
        MediaPlayerView(MediaSource.File(state.ref), state.kind, state.ref.displayName, ref = state.ref) { onAction(ReaderAction.CloseDocument) }
        return
    }
    val ready = state as? ReaderUiState.Ready
    val paperColors = if (darkReading) DarkPaperColors else LightPaperColors

    // CSV cell editing save-as flow.
    val csvFiles = LocalDocumentFiles.current
    val csvScope = rememberCoroutineScope()
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    val csvSaver = rememberDocumentSaver { uri ->
        if (uri != null) {
            val content = pendingCsv ?: return@rememberDocumentSaver
            csvScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { csvFiles.write(uri, content.encodeToByteArray()) }
                pendingCsv = null
            }
        } else pendingCsv = null
    }

    // Find & Replace flow for DOCX/PPTX.
    val docEditor = LocalDocumentEditor.current
    val replaceScope = rememberCoroutineScope()
    var findReplaceOpen by remember { mutableStateOf(false) }
    var replaceRunning by remember { mutableStateOf(false) }
    var replaceResultCount by remember { mutableStateOf<Int?>(null) }
    var pendingReplaceBytes by remember { mutableStateOf<ByteArray?>(null) }
    val replaceSaver = rememberDocumentSaver { uri ->
        if (uri != null) {
            val bytes = pendingReplaceBytes ?: return@rememberDocumentSaver
            replaceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { csvFiles.write(uri, bytes) }
                pendingReplaceBytes = null
            }
        } else pendingReplaceBytes = null
    }

    // Slide tools flow for PPTX.
    val slideScope = rememberCoroutineScope()
    var slideToolsOpen by remember { mutableStateOf(false) }
    var slideCount by remember { mutableStateOf(0) }
    var slideSaving by remember { mutableStateOf(false) }
    var pendingSlideBytes by remember { mutableStateOf<ByteArray?>(null) }
    val slideSaver = rememberDocumentSaver { uri ->
        if (uri != null) {
            val bytes = pendingSlideBytes ?: return@rememberDocumentSaver
            slideScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { csvFiles.write(uri, bytes) }
                pendingSlideBytes = null
            }
        } else pendingSlideBytes = null
    }

    val modeControls = remember(ready?.openId) { ReaderControlVisibility() }
    val canSwitchSource = ready?.canSwitchSource == true
    val onReaderScroll = remember(modeControls, canSwitchSource) {
        { if (canSwitchSource) modeControls.scrolled() }
    }
    val readerScroll = remember(onReaderScroll) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed != Offset.Zero) onReaderScroll()
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(modeControls, modeControls.visible) {
        if (!modeControls.visible) modeControls.revealAfterIdle()
    }
    val reducedMotion = LocalReducedMotion.current
    val photoSession = LocalPhotoGallerySession.current?.takeIf { gallery ->
        ready?.info?.kind == DocumentKind.Image && gallery.indexOf(ready.ref.uri) >= 0
    }
    val selectedPhoto = photoSession?.let { it.selected.collectAsStateWithLifecycle().value }
    val effectiveRef = selectedPhoto ?: ready?.ref
    val effectiveInfo = ready?.info?.let { info -> selectedPhoto?.let { photo ->
        info.copy(name = photo.displayName, byteSize = photo.sizeBytes ?: if (photo.uri == ready.ref.uri) info.byteSize else 0L)
    } ?: info }
    // Full screen: the document alone, without the app's bars or the system's.
    var immersive by rememberSaveable(ready?.openId) { mutableStateOf(false) }
    if (immersive && (ready == null || ready.search != null)) immersive = false
    var exitVisible by remember { mutableStateOf(true) }
    if (immersive) {
        HiddenSystemBars()
        PlatformBackHandler(enabled = true) { immersive = false }
        LaunchedEffect(exitVisible) {
            if (exitVisible) {
                delay(IMMERSIVE_EXIT_HINT_MS)
                exitVisible = false
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .readerBackground()
            .appSafeArea()
            // Any touch brings back the way out, without taking the touch from the document.
            .pointerInput(immersive, modeControls, canSwitchSource) {
                if (!immersive && !canSwitchSource) return@pointerInput
                awaitPointerEventScope {
                    var origin: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) {
                            if (immersive) exitVisible = true
                            if (canSwitchSource && !modeControls.visible) modeControls.tapped()
                            origin = event.changes.firstOrNull()?.position
                        } else if (event.type == PointerEventType.Move && canSwitchSource) {
                            val start = origin
                            val pointer = event.changes.firstOrNull { it.pressed }
                            if (start != null && pointer != null && (pointer.position - start).getDistance() > viewConfiguration.touchSlop) {
                                modeControls.scrolled()
                            }
                        } else if (event.type == PointerEventType.Release) origin = null
                    }
                }
            },
    ) {
        Column(Modifier.fillMaxSize().then(if (immersive) Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
            else Modifier.statusBarsPadding().navigationBarsPadding())) {
            val pdfExitGuard = remember { PdfExitGuard() }
            DisposableEffect(pdfExitGuard) {
                onExitGuardChange(pdfExitGuard)
                onDispose { onExitGuardChange(null) }
            }
            val pdfBar = remember { PdfBarActions() }
            // Bookmarks: the open viewer's bookmark toggle and list, drawn by the top bar.
            val bookmarkBar = remember { BookmarkBar() }
            // The PDF's search bar takes the place of this one while it's open.
            var pdfSearchOpen by remember { mutableStateOf(false) }
            val search = ready?.search
            if (ready != null && search != null) {
                ReaderSearchBar(ready, search, onAction)
            } else {
                AnimatedVisibility(!immersive && !pdfSearchOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    ReaderTopBar(
                        title = selectedPhoto?.displayName ?: state.title(),
                        onBack = { pdfExitGuard.leave { onAction(ReaderAction.CloseDocument) } },
                        onOpenAnother = { pdfExitGuard.leave { onAction(ReaderAction.PickDocument) } },
                        info = effectiveInfo,
                        onSearch = if (ready?.canSearch == true) ({ onAction(ReaderAction.OpenSearch) }) else pdfBar.search,
                        onAnnotate = pdfBar.annotate,
                        bookmarks = bookmarkBar, // Bookmarks
                        ref = effectiveRef,
                        detailReference = selectedPhoto?.takeIf { it.uri != ready?.ref?.uri },
                        annotateReady = pdfBar.annotateReady,
                        annotating = pdfBar.annotating,
                        onEdit = if (ready?.canEdit == true) ({ onAction(ReaderAction.OpenEditor) }) else null,
                        onFindReplace = if (docEditor != null && ready?.info?.format in setOf(DocumentFormat.Docx, DocumentFormat.Pptx, DocumentFormat.Odt, DocumentFormat.Odp)) ({ findReplaceOpen = true }) else null,
                        darkReading = darkReading,
                        onToggleDarkReading = if (ready?.info?.kind in setOf(DocumentKind.Text, DocumentKind.Spreadsheet)) ({ onAction(ReaderAction.ToggleDarkReading) }) else null,
                        onSlideTools = if (docEditor != null && ready?.info?.format == DocumentFormat.Pptx) ({
                            slideScope.launch {
                                val ref = ready.ref
                                val count = docEditor.pptxSlideCount(ref.uri, ref.displayName)
                                slideCount = count
                                slideToolsOpen = true
                            }
                        }) else null,
                        onPdfTools = if (ready?.info?.kind == DocumentKind.Pdf) ({ pdfExitGuard.leave { onPdfTools(ready.ref) } }) else null,
                        beforeDelivery = { action -> pdfExitGuard.leave(action) },
                        onFullScreen = if (ready != null) ({
                            exitVisible = true
                            immersive = true
                        }) else null,
                        trailingActions = if (ready != null) trailingActions else ({}),
                    )
                }
            }
            CompositionLocalProvider(LocalImmersive provides immersive, LocalBookmarkBar provides bookmarkBar,
                LocalReaderScrollActivity provides onReaderScroll) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (immersive) 0.dp else 12.dp).nestedScroll(readerScroll)) {
                when (state) {
                    ReaderUiState.Idle, is ReaderUiState.SelectingFile -> {}
                    is ReaderUiState.Loading -> {
                        Progress("Opening ${state.name}…") { onAction(ReaderAction.Cancel) }
                        if (opensInWebView(state.name)) WarmUpWebView()
                    }
                    is ReaderUiState.Reading -> Progress("Reading ${state.info.name}…") { onAction(ReaderAction.Cancel) }
                    is ReaderUiState.PasswordNeeded -> PasswordSheet(state, onAction)
                    is ReaderUiState.Failed -> {
                        FeedbackOnAppear(state) { reject() }
                        Message(state.error.title(), state.error.detail, onAction, lostBookmark(state))
                    }
                    is ReaderUiState.Cancelled -> Message("Stopped opening ${state.name}", null, onAction)
                    // PDFs, slides and images draw directly on the reader background.
                    is ReaderUiState.Ready -> if (state.info.kind == DocumentKind.Pdf) {
                        PdfReader(
                            state.ref,
                            pdfExitGuard,
                            pdfBar,
                            onSearchOpen = { pdfSearchOpen = it },
                            initialPage = (state.initialLocation as? BookmarkLocation.Page)?.index ?: 0, // Bookmarks
                        ) { onAction(ReaderAction.CloseDocument) }
                    } else if (state.info.kind == DocumentKind.Presentation || state.info.kind == DocumentKind.Image) {
                        DocumentViewer(
                            state = state,
                            onLoadMore = { onAction(ReaderAction.LoadMore) },
                            onRetry = { onAction(ReaderAction.RetryLoadMore) },
                            darkReading = darkReading,
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                // Keeps the current match above the search keyboard.
                                .imePadding()
                                .then(
                                    if (immersive) {
                                        Modifier
                                    } else {
                                        Modifier.padding(top = 12.dp, bottom = 12.dp).raised(EzShapes.Page).clip(EzShapes.Page)
                                    },
                                )
                                .background(paperColors.background),
                        ) {
                            CompositionLocalProvider(
                                LocalContentColor provides paperColors.text,
                                LocalPaperColors provides paperColors,
                            ) {
                                DocumentViewer(
                                    state = state,
                                    onLoadMore = { onAction(ReaderAction.LoadMore) },
                                    onRetry = { onAction(ReaderAction.RetryLoadMore) },
                                    onPageFind = { onAction(ReaderAction.PageFindChanged(it)) },
                                    onEntryTap = if (state.info.kind == DocumentKind.Archive) {{ path -> onAction(ReaderAction.ExtractEntry(path)) }} else null,
                                    onSaveCsv = if (state.info.format == DocumentFormat.Csv) {{ csv ->
                                        pendingCsv = csv
                                        csvSaver.launch(state.info.name, "text/csv")
                                    }} else null,
                                    onSaveXlsx = if (docEditor != null && state.info.format == DocumentFormat.Xlsx) {{ sheetIdx, edits ->
                                        val editor = docEditor
                                        slideScope.launch {
                                            try {
                                                val output = editor.xlsxEditCells(state.ref.uri, state.info.name, sheetIdx, edits)
                                                pendingSlideBytes = output
                                                val baseName = state.info.name.substringBeforeLast('.')
                                                slideSaver.launch(
                                                    "${baseName}_edited.xlsx",
                                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                                )
                                            } catch (_: Exception) {}
                                        }
                                    }} else null,
                                    darkReading = darkReading,
                                )
                            }
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = canSwitchSource && !immersive && modeControls.visible,
                    enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(140)) + slideInVertically(tween(140)) { it / 3 },
                    exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(100)) + slideOutVertically(tween(100)) { it / 3 },
                    modifier = Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = 12.dp),
                ) {
                    ready?.let {
                        val label = when (it.info.format) {
                            DocumentFormat.SourceCode -> "Book"
                            DocumentFormat.Json -> "Tree"
                            else -> "Preview"
                        }
                        FloatingReaderModeSwitch(it.source, { onAction(ReaderAction.ToggleSource) }, previewLabel = label)
                    }
                }
            }
            }
        }
        AnimatedVisibility(
            visible = immersive && exitVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(14.dp),
        ) {
            GlassIconButton(EzIcons.FullscreenExit, "Exit full screen", { immersive = false })
        }
        // Tidy up: offers to keep a file with card or ID numbers out of screenshots.
        SensitiveFileHint(
            ref = ready?.ref,
            visible = !immersive && ready?.search == null,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = TOP_BAR_CLEARANCE),
        )
        if (findReplaceOpen && ready != null) {
        FindReplaceSheet(
            onReplace = { find, replace, caseSensitive ->
                val editor = docEditor ?: return@FindReplaceSheet
                val sourceUri = ready.ref.uri
                val name = ready.info.name
                replaceRunning = true
                replaceResultCount = null
                replaceScope.launch {
                    try {
                        val result = editor.replaceAll(sourceUri, name, find, replace, caseSensitive)
                        replaceResultCount = result.matchCount
                        if (result.matchCount > 0) {
                            pendingReplaceBytes = result.outputBytes
                            val ext = name.substringAfterLast('.', "docx")
                            val baseName = name.substringBeforeLast('.')
                            val mime = when (ext.lowercase()) {
                                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                                "odt" -> "application/vnd.oasis.opendocument.text"
                                "odp" -> "application/vnd.oasis.opendocument.presentation"
                                else -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            }
                            replaceSaver.launch("${baseName}_edited.$ext", mime)
                        }
                    } catch (_: Exception) {
                        replaceResultCount = 0
                    } finally {
                        replaceRunning = false
                    }
                }
            },
            onDismiss = {
                findReplaceOpen = false
                replaceResultCount = null
            },
            matchCount = replaceResultCount,
            replacing = replaceRunning,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
        }
        if (slideToolsOpen && ready != null && slideCount > 0) {
        SlideToolsSheet(
            slideCount = slideCount,
            saving = slideSaving,
            onSave = { keepIndices ->
                val editor = docEditor ?: return@SlideToolsSheet
                val sourceUri = ready.ref.uri
                val name = ready.info.name
                slideSaving = true
                slideScope.launch {
                    try {
                        val output = editor.pptxReorderSlides(sourceUri, name, keepIndices)
                        pendingSlideBytes = output
                        val baseName = name.substringBeforeLast('.')
                        slideSaver.launch(
                            "${baseName}_edited.pptx",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        )
                    } catch (_: Exception) {
                    } finally {
                        slideSaving = false
                    }
                }
            },
            onDismiss = { slideToolsOpen = false },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
        }
    }
}

private const val IMMERSIVE_EXIT_HINT_MS = 2500L

/** Clears the top bar: its height plus its outer padding. */
private val TOP_BAR_CLEARANCE = 76.dp

@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    onOpenAnother: () -> Unit,
    info: DocumentInfo?,
    onSearch: (() -> Unit)?,
    onAnnotate: (() -> Unit)?,
    annotateReady: Boolean,
    annotating: Boolean,
    bookmarks: BookmarkBar,
    ref: DocumentRef?,
    onEdit: (() -> Unit)?,
    onFindReplace: (() -> Unit)?,
    darkReading: Boolean = false,
    onToggleDarkReading: (() -> Unit)? = null,
    onSlideTools: (() -> Unit)? = null,
    onPdfTools: (() -> Unit)?,
    beforeDelivery: (() -> Unit) -> Unit = { it() },
    detailReference: DocumentRef? = null,
    onFullScreen: (() -> Unit)? = null,
    trailingActions: @Composable RowScope.() -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val delivery = rememberDocumentDeliveryActions()
    var detailsOpen by remember { mutableStateOf(false) }
    if (detailsOpen && info != null) FileDetails(info, detailReference) { detailsOpen = false }
    // Bookmarks: this file's list, from the menu.
    var bookmarksOpen by remember { mutableStateOf(false) }
    if (bookmarksOpen && ref != null && bookmarks.onGo != null) ReaderBookmarksSheet(ref, bookmarks) { bookmarksOpen = false }
    // At a glance: key sentences, keywords and things to act on, beside the untouched document.
    var glanceOpen by remember { mutableStateOf(false) }
    val canGlance = LocalGlancer.current != null && ref != null
    if (glanceOpen && ref != null) GlanceSheet(ref, title) { glanceOpen = false }
    // The narrower reader gutter lines the bar up with the page below it.
    EzTopBar(title = title, onBack = onBack, backLabel = "Back to files", gutter = EzSpacing.ReaderGutter) {
        if (onSearch != null) BarIconButton(EzIcons.Search, "Find in document", onSearch)
        if (onAnnotate != null) BarIconButton(
            EzIcons.Edit,
            if (annotating) "Finish annotating" else "Annotate",
            onAnnotate,
            enabled = annotateReady,
            tint = if (annotating) EzColors.Primary else EzColors.Text,
        )
        BookmarkToggleButton(bookmarks) // Bookmarks
        trailingActions()
        Box {
            BarIconButton(EzIcons.More, "More options", { menuOpen = true })
            EzDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val item: @Composable (String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit) -> Unit = { label, icon, action ->
                    EzMenuItem(
                        label = label,
                        icon = icon,
                        onClick = {
                            menuOpen = false
                            action()
                        },
                    )
                }
                if (onFullScreen != null) item("Full screen", EzIcons.Fullscreen, onFullScreen)
                if (bookmarks.onGo != null && ref != null) item("Bookmarks", EzIcons.BookmarkOutline) { bookmarksOpen = true } // Bookmarks
                if (canGlance) item("At a glance", EzIcons.Lightbulb) { glanceOpen = true }
                if (ref != null) {
                    EzMenuItem("Share original", icon = TidyIcons.Share, enabled = !delivery.busy, onClick = {
                        menuOpen = false
                        delivery.share(listOf(ref))
                    })
                    EzMenuItem("Print", icon = EzIcons.PdfFile, enabled = !delivery.busy && delivery.canPrint(ref), onClick = {
                        menuOpen = false
                        beforeDelivery { delivery.print(ref) }
                    })
                }
                if (onEdit != null) item("Edit text", EzIcons.Edit, onEdit)
                if (onFindReplace != null) item("Find & Replace", EzIcons.Search, onFindReplace)
                if (onToggleDarkReading != null) item(
                    if (darkReading) "Light reading" else "Dark reading",
                    if (darkReading) EzIcons.LightMode else EzIcons.DarkMode,
                    onToggleDarkReading,
                )
                if (onSlideTools != null) item("Slide tools", EzIcons.ListLines, onSlideTools)
                if (onPdfTools != null) item("PDF tools", EzIcons.PdfFile, onPdfTools)
                if (info != null) item("File details", EzIcons.Document) { detailsOpen = true }
            }
        }
    }
}

internal class DocumentDeliveryActions(
    val share: (List<DocumentRef>) -> Unit,
    val shareText: (String, String) -> Unit,
    val print: (DocumentRef) -> Unit,
    val canPrint: (DocumentRef) -> Boolean,
    val busy: Boolean,
)

/** Shared reader/library feedback. Passwords stay only in this unsaved dialog state. */
@Composable
internal fun rememberDocumentDeliveryActions(): DocumentDeliveryActions {
    val delivery = rememberDocumentDelivery()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var passwordRef by remember { mutableStateOf<DocumentRef?>(null) }
    var passwordDetail by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    fun print(ref: DocumentRef, suppliedPassword: String?) {
        if (busy) return
        busy = true
        running = scope.launch {
            try {
                delivery.print(ref, suppliedPassword)
                passwordRef = null
                password = ""
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: DocumentException) {
                if (failure.error is DocumentError.PasswordRequired || failure.error is DocumentError.WrongPassword) {
                    passwordRef = ref
                    passwordDetail = failure.error.detail
                    password = ""
                } else {
                    passwordRef = null
                    error = failure.error.detail
                }
            } catch (_: Exception) {
                passwordRef = null
                error = "This file could not be printed. Try opening it again."
            } finally {
                busy = false
            }
        }
    }

    passwordRef?.let { ref ->
        val dismiss = { if (!busy) { passwordRef = null; password = "" } }
        EzDialog(
            onDismissRequest = dismiss,
            title = { Text("Print protected PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(passwordDetail)
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = { EzTextButton(if (busy) "Preparing…" else "Continue", {
                val supplied = password
                password = ""
                print(ref, supplied)
            }, enabled = !busy && password.isNotEmpty()) },
            dismissButton = { EzTextButton("Cancel", { if (busy) running?.cancel() else dismiss() }) },
        )
    }
    if (busy && passwordRef == null) {
        EzDialog(
            onDismissRequest = { running?.cancel() },
            title = { Text("Preparing document") },
            text = { CircularProgressIndicator() },
            confirmButton = { EzTextButton("Cancel", { running?.cancel() }) },
        )
    }
    error?.let { detail ->
        EzDialog(
            onDismissRequest = { error = null }, title = { Text("Document delivery") },
            text = { Text(detail) }, confirmButton = { EzTextButton("OK", { error = null }) },
        )
    }
    return DocumentDeliveryActions(
        share = { refs ->
            if (!busy) {
                busy = true
                running = scope.launch {
                    try {
                        delivery.share(refs)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: DocumentException) {
                        error = failure.error.detail
                    } catch (_: Exception) {
                        error = "These files could not be shared. Try opening them again."
                    } finally {
                        busy = false
                    }
                }
            }
        },
        shareText = { text, title ->
            if (!busy) {
                busy = true
                running = scope.launch {
                    try {
                        delivery.shareText(text, title)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: DocumentException) {
                        error = failure.error.detail
                    } catch (_: Exception) {
                        error = "This text could not be shared. Try again."
                    } finally {
                        busy = false
                    }
                }
            }
        },
        print = { print(it, null) }, canPrint = delivery::canPrint, busy = busy,
    )
}

/**
 * Replaces the top bar while searching. The field keeps its own text, so a
 * keystroke touches nothing else on screen until the debounced results arrive.
 */
@Composable
private fun ReaderSearchBar(state: ReaderUiState.Ready, search: ReaderSearch, onAction: (ReaderAction) -> Unit) {
    val compact = isShortWindow()
    var field by remember { mutableStateOf(TextFieldValue(search.results.query)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val hasMatches = search.hasMatches
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 8.dp)
            .glassBar()
            .padding(if (compact) 4.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                if (field.text.isEmpty()) {
                    Text("Find in document", color = EzColors.TextSecondary, fontSize = 16.sp, maxLines = 1)
                }
                BasicTextField(
                    value = field,
                    onValueChange = { value ->
                        val changed = value.text != field.text
                        field = value
                        if (changed) onAction(ReaderAction.SearchQueryChanged(value.text))
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = EzColors.Text, fontSize = 16.sp),
                    cursorBrush = SolidColor(EzColors.Primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onAction(ReaderAction.NextMatch) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (field.text.isNotEmpty()) SearchCount(search)
            BarIconButton(EzIcons.ChevronUp, "Previous match", { onAction(ReaderAction.PreviousMatch) }, enabled = hasMatches)
            BarIconButton(EzIcons.ChevronDown, "Next match", { onAction(ReaderAction.NextMatch) }, enabled = hasMatches)
            BarIconButton(EzIcons.Close, "Close search", { onAction(ReaderAction.CloseSearch) })
        }
        if (field.text.isNotEmpty()) SearchNote(state, search, onAction)
    }
}

/** "3 / 17" over "searching…", in a fixed height so the document never shifts. */
@Composable
private fun SearchCount(search: ReaderSearch) {
    Column(
        modifier = Modifier.padding(end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.End,
    ) {
        val count = search.countLabel
        if (count != null) {
            Text(count, style = MaterialTheme.typography.labelMedium, color = EzColors.Text, maxLines = 1)
        }
        if (search.searching) {
            Text("searching…", style = MaterialTheme.typography.labelSmall, color = EzColors.TextSecondary, maxLines = 1)
        } else if (count == null) {
            Text("No matches", style = MaterialTheme.typography.labelMedium, color = EzColors.TextSecondary, maxLines = 1)
        }
    }
}

/** Says when the count does not cover the whole document. */
@Composable
private fun SearchNote(state: ReaderUiState.Ready, search: ReaderSearch, onAction: (ReaderAction) -> Unit) {
    val note = when {
        state.loadMoreError != null -> "Couldn't read the rest of the file, so it wasn't searched."
        search.results.capped -> "Showing the first $MAX_SEARCH_MATCHES matches."
        state.truncated && !search.searching -> "Searched up to the preview limit; the rest of the file isn't loaded."
        else -> return
    }
    Row(Modifier.padding(start = 10.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(note, style = MaterialTheme.typography.labelMedium, color = EzColors.TextSecondary, modifier = Modifier.weight(1f))
        if (state.loadMoreError != null) {
            EzTextButton("Retry", { onAction(ReaderAction.RetryLoadMore) })
        }
    }
}

private fun ReaderUiState.title(): String = when (this) {
    is ReaderUiState.Ready -> info.name
    is ReaderUiState.Reading -> info.name
    is ReaderUiState.Loading -> name
    is ReaderUiState.PasswordNeeded -> ref.displayName
    is ReaderUiState.Failed -> name
    is ReaderUiState.Cancelled -> name
    is ReaderUiState.Playing -> ref.displayName
    else -> "EzFiles"
}

fun DocumentError.title(): String = when (this) {
    is DocumentError.FileAccess -> "Couldn't open the file"
    is DocumentError.Unsupported -> "This file type isn't supported yet"
    is DocumentError.Malformed -> "The file appears to be damaged"
    is DocumentError.LimitExceeded -> "The file is too large"
    is DocumentError.PasswordRequired, is DocumentError.WrongPassword -> "This file is protected"
    is DocumentError.UnsupportedEncryption -> "This file's protection isn't supported"
    is DocumentError.Internal -> "Something went wrong"
}

/**
 * Asks for a protected file's password, in the look of the PDF reader's prompt.
 * The text lives only in this composable, not in saved state, and goes to the
 * engine for one try at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordSheet(state: ReaderUiState.PasswordNeeded, onAction: (ReaderAction) -> Unit) {
    var text by remember(state.ref) { mutableStateOf("") }
    var visible by remember(state.ref) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val submit = { if (text.isNotEmpty() && !state.unlocking) onAction(ReaderAction.SubmitPassword(text)) }
    val cancel = { onAction(ReaderAction.Cancel) }
    EzSheet(
        onDismissRequest = cancel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(EzIcons.Lock, null, tint = EzColors.Primary, modifier = Modifier.size(36.dp))
            Text("This file is protected", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
            Text(
                state.ref.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = EzColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                if (state.incorrect) "That password didn't work. Try again." else "Enter the password to open it.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.incorrect) EzColors.Accent else EzColors.TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                enabled = !state.unlocking,
                label = { Text("Password") },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    BarIconButton(
                        if (visible) EzIcons.EyeOff else EzIcons.Eye,
                        if (visible) "Hide password" else "Show password",
                        { visible = !visible },
                    )
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            // Here, not outside the sheet: its content is composed separately, and
            // asking before the field exists would throw.
            LaunchedEffect(Unit) { focus.requestFocus() }
            if (state.unlocking) {
                CircularProgressIndicator(color = EzColors.Primary)
            } else {
                PrimaryActionButton("Unlock", EzIcons.Lock, onClick = { submit() }, modifier = Modifier.fillMaxWidth())
            }
            EzTextButton("Cancel", cancel)
        }
    }
}

@Composable
private fun Message(title: String, body: String?, onAction: (ReaderAction) -> Unit, lost: BookmarkSpot? = null) {
    EzStatusCard(Modifier.padding(horizontal = 4.dp)) {
        EzStatusText(title, body)
        // Bookmarks: a file opened from a bookmark that can't be reached any more.
        if (lost != null) {
            val bookmarks = LocalBookmarks.current
            val scope = rememberCoroutineScope()
            Text(
                "This file was opened from a bookmark. Choose the file again to give EzFiles access, or remove the bookmark.",
                style = MaterialTheme.typography.bodyMedium,
                color = EzColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            SecondaryActionButton("Remove bookmark", EzIcons.Delete, {
                scope.launch { bookmarks.remove(lost) }
                onAction(ReaderAction.CloseDocument)
            })
        }
        PrimaryActionButton("Choose another file", EzIcons.Folder, { onAction(ReaderAction.PickDocument) })
        EzTextButton("Back to files", { onAction(ReaderAction.CloseDocument) })
    }
}

@Composable
private fun Progress(label: String, onCancel: () -> Unit) {
    EzProgressCard(label, onCancel, Modifier.padding(horizontal = 4.dp))
}

@Composable
private fun FileDetails(info: DocumentInfo, reference: DocumentRef? = null, onDismiss: () -> Unit) {
    val name = reference?.displayName ?: info.name
    val ext = name.substringAfterLast('.', "").lowercase().ifEmpty { null }
    val mime = ext?.let { mimeForExtension(it) }
    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text("File details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val rows = listOfNotNull(
                    "Name" to name,
                    "Format" to info.format.label,
                    ext?.let { "Extension" to ".$it" },
                    mime?.let { "MIME type" to it },
                    "Category" to info.kind.name,
                    "Size" to run {
                        val bytes = reference?.sizeBytes ?: info.byteSize
                        "${formatBytes(bytes)} ($bytes bytes)"
                    },
                    info.encoding?.let { "Text encoding" to it.uppercase() },
                    reference?.modifiedAt?.let { ms ->
                        "Modified" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(ms))
                    },
                    "Location" to (reference?.uri?.let { uri ->
                        if (uri.startsWith("content://")) uri.substringAfter("content://").substringBefore("/").replace("%3A", ":") else uri
                    } ?: "In-memory"),
                )
                for ((label, value) in rows) {
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = EzColors.TextSecondary)
                        SelectionContainer {
                            Text(value, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text)
                        }
                    }
                }
            }
        },
        confirmButton = { EzTextButton("Close", onDismiss) },
    )
}

private fun mimeForExtension(ext: String): String? = when (ext) {
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "doc" -> "application/msword"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "xls" -> "application/vnd.ms-excel"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "ppt" -> "application/vnd.ms-powerpoint"
    "odt" -> "application/vnd.oasis.opendocument.text"
    "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
    "odp" -> "application/vnd.oasis.opendocument.presentation"
    "epub" -> "application/epub+zip"
    "csv" -> "text/csv"
    "tsv" -> "text/tab-separated-values"
    "json" -> "application/json"
    "xml" -> "application/xml"
    "html", "htm" -> "text/html"
    "md", "markdown" -> "text/markdown"
    "txt" -> "text/plain"
    "zip" -> "application/zip"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "mp4" -> "video/mp4"
    "mp3" -> "audio/mpeg"
    "yaml", "yml" -> "application/yaml"
    "toml" -> "application/toml"
    "sql" -> "application/sql"
    "kt" -> "text/x-kotlin"
    "java" -> "text/x-java"
    "py" -> "text/x-python"
    "js" -> "text/javascript"
    "ts" -> "text/typescript"
    "rs" -> "text/x-rust"
    "cpp", "cc", "cxx" -> "text/x-c++src"
    "c" -> "text/x-csrc"
    "h", "hpp" -> "text/x-chdr"
    "cs" -> "text/x-csharp"
    "go" -> "text/x-go"
    "swift" -> "text/x-swift"
    "rb" -> "text/x-ruby"
    "php" -> "text/x-php"
    "sh", "bash" -> "text/x-shellscript"
    "css" -> "text/css"
    "ini", "cfg" -> "text/plain"
    else -> null
}
