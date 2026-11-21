package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.domain.spokenNumber
import com.ezfiles.reader.presentation.theme.rememberHapticChoice
import com.ezfiles.reader.presentation.theme.rememberEzHaptics

import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import com.ezfiles.reader.application.assistant.ReaderPosition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import com.ezfiles.reader.presentation.bookmarks.PdfBookmarks
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.LocalPdfOpener
import com.ezfiles.reader.presentation.components.EzNotice
import com.ezfiles.reader.presentation.components.ReaderPaneLayout
import com.ezfiles.reader.presentation.components.isWideLandscape
import com.ezfiles.reader.presentation.components.isShortWindow
import com.ezfiles.reader.presentation.components.EzDropdownMenu
import com.ezfiles.reader.presentation.components.EzMenuItem
import com.ezfiles.reader.platform.MemoryPressure
import com.ezfiles.reader.platform.PdfAnnotation
import com.ezfiles.reader.platform.PdfEditInfo
import com.ezfiles.reader.platform.PdfInkStyle
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.platform.PdfPasswordRequired
import com.ezfiles.reader.platform.PdfPoint
import com.ezfiles.reader.platform.PlatformBackHandler
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzBlockingProgress
import com.ezfiles.reader.presentation.components.EzDialog
import com.ezfiles.reader.presentation.components.EzStatusCard
import com.ezfiles.reader.presentation.components.EzStatusText
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.theme.glassBar
import com.ezfiles.reader.presentation.components.GlassIconButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzPaper
import androidx.compose.ui.semantics.selected
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.raised
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class ViewMode { Single, TwoUp, Grid }

private val PDF_VOICE_OPERATIONS = setOf(
    "next_page", "previous_page", "go_to_page", "zoom_in", "zoom_out", "scroll_down", "scroll_up", "scroll_top", "scroll_bottom",
)
private const val MIN_ZOOM = 50
private const val MAX_ZOOM = 300
private const val ZOOM_STEP = 25
private const val A4_ASPECT = 1f / 1.414f
private const val SEARCH_DEBOUNCE_MS = 300L

/** Pages rendered ahead of the last visible row, once scrolling has rested this long. */
private const val PREFETCH_PAGES = 2
private const val PREFETCH_IDLE_MS = 150L
private const val SAVE_MESSAGE_MS = 4000L
private const val CONTROLS_ANIMATION_MS = 150

// Longer than a frame, so a keyboard still animating counts as one change of height.
private const val LAYOUT_SETTLE_MS = 120L
private val ControlsEnter = expandVertically(tween(CONTROLS_ANIMATION_MS), clip = false) + fadeIn(tween(CONTROLS_ANIMATION_MS))
private val ControlsExit = shrinkVertically(tween(CONTROLS_ANIMATION_MS), clip = false) + fadeOut(tween(CONTROLS_ANIMATION_MS))

private sealed interface PdfLoad {
    data object Loading : PdfLoad
    data class Open(val pages: PdfPages) : PdfLoad
    data class NeedsPassword(val incorrect: Boolean) : PdfLoad
    data class Failed(val message: String) : PdfLoad
}

/**
 * Lets the reader's chrome ask before leaving a PDF with unsaved annotations.
 * [leave] runs its action at once unless the open reader wants to confirm.
 */
class PdfExitGuard {
    internal var confirm: ((onLeave: () -> Unit) -> Unit)? = null

    fun leave(action: () -> Unit) {
        confirm?.invoke(action) ?: action()
    }
}

/**
 * The PDF's own buttons in the app's top bar, next to the file name: the reader
 * sets them, the screen draws them. Null hides a button.
 */
class PdfBarActions {
    var search by mutableStateOf<(() -> Unit)?>(null)
    var annotate by mutableStateOf<(() -> Unit)?>(null)
    var annotateReady by mutableStateOf(false)
    var annotating by mutableStateOf(false)
}

/**
 * PDF reader: platform-rendered pages under shared controls (ADR-009).
 * [onClose] leaves the document when system back confirms past unsaved work.
 * [onSearchOpen] says when the PDF's search bar is open: it takes the place of the app's bar.
 */
@Composable
fun PdfReader(
    ref: DocumentRef,
    exitGuard: PdfExitGuard? = null,
    barActions: PdfBarActions? = null,
    onSearchOpen: (Boolean) -> Unit = {},
    /** Bookmarks: the page, from 0, to open at. */
    initialPage: Int = 0,
    onClose: () -> Unit = {},
) {
    val opener = LocalPdfOpener.current
    // Kept only in memory for this screen; never stored.
    var password by remember(ref) { mutableStateOf<String?>(null) }
    val load by produceState<PdfLoad>(PdfLoad.Loading, ref, password) {
        value = PdfLoad.Loading
        var pages: PdfPages? = null
        try {
            withContext(Dispatchers.IO) { pages = CachedPdfPages(opener.open(ref, password)) }
            value = PdfLoad.Open(checkNotNull(pages))
            awaitCancellation()
        } catch (e: PdfPasswordRequired) {
            value = PdfLoad.NeedsPassword(e.incorrect)
        } catch (e: DocumentException) {
            value = PdfLoad.Failed(e.error.detail)
        } finally {
            withContext(NonCancellable) { pages?.close() }
        }
    }
    when (val state = load) {
        PdfLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = EzColors.Primary)
        }
        is PdfLoad.Failed -> EzStatusCard(Modifier.padding(horizontal = 4.dp)) {
            EzStatusText("Couldn't open this PDF", state.message)
        }
        is PdfLoad.NeedsPassword -> PasswordPrompt(state.incorrect) { password = it }
        is PdfLoad.Open -> {
            DisposableEffect(state.pages) {
                // Pages on screen stay: their composables still hold them.
                val unregister = (state.pages as? CachedPdfPages)?.let { MemoryPressure.register(it::clear) }
                onDispose { unregister?.invoke() }
            }
            PdfReaderContent(state.pages, ref, exitGuard, barActions, onSearchOpen, initialPage, onClose)
        }
    }
}

@Composable
private fun PasswordPrompt(incorrect: Boolean, onUnlock: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    // Above the keyboard, and scrollable on short screens, so Unlock stays reachable.
    Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth().glass(EzShapes.Large, 0.6f).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(EzIcons.Lock, null, tint = EzColors.Primary, modifier = Modifier.size(36.dp))
            Text("This PDF is protected", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
            Text(
                if (incorrect) "That password didn't work. Try again." else "Enter the password to open it.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (incorrect) EzColors.Accent else EzColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.isNotEmpty()) onUnlock(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryActionButton("Unlock", EzIcons.Lock, onClick = { if (text.isNotEmpty()) onUnlock(text) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** One step of a save the user is being walked through. */
private sealed interface SaveStep {
    data object Choose : SaveStep
    data object ConfirmOverwrite : SaveStep
}

@Composable
private fun PdfReaderContent(
    pages: PdfPages,
    ref: DocumentRef,
    exitGuard: PdfExitGuard?,
    barActions: PdfBarActions?,
    onSearchOpen: (Boolean) -> Unit,
    initialPage: Int,
    onClose: () -> Unit,
) {
    val displayName = ref.displayName
    var mode by rememberSaveable { mutableStateOf(ViewMode.Single) }
    var zoom by rememberSaveable { mutableIntStateOf(100) }
    var jumpOpen by remember { mutableStateOf(false) }
    // Bookmarks: opens at [initialPage]; one page per row, as the reader starts in single-page view.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceIn(0, (pages.pageCount - 1).coerceAtLeast(0)))
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val pageCount = pages.pageCount
    val perRow = if (mode == ViewMode.TwoUp) 2 else 1
    val currentPage by remember(mode) {
        derivedStateOf {
            when (mode) {
                ViewMode.Grid -> gridState.firstVisibleItemIndex
                else -> {
                    val info = listState.layoutInfo
                    val rows = info.visibleItemsInfo.map { VisibleRow(it.index, it.offset, it.offset + it.size) }
                    val atEnd = !listState.canScrollForward && listState.canScrollBackward
                    currentRow(rows, info.viewportEndOffset - info.viewportStartOffset, atEnd, listState.firstVisibleItemIndex) * perRow
                }
            }.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        }
    }
    val goTo: (Int) -> Unit = { page ->
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        scope.launch {
            if (mode == ViewMode.Grid) gridState.animateScrollToItem(target) else listState.animateScrollToItem(target / perRow)
        }
    }

    PdfBookmarks(ref, currentPage, goTo) // Bookmarks

    val scrollToPage: suspend (Int) -> Unit = { page ->
        if (mode == ViewMode.Grid) gridState.animateScrollToItem(page) else listState.animateScrollToItem(page / perRow)
    }
    ReaderControlTarget(PDF_VOICE_OPERATIONS, ReaderPosition(page = currentPage + 1, pageCount = pageCount)) { operation, value ->
        when (operation) {
            "next_page" -> if (currentPage + perRow >= pageCount) "at_last_page" else null.also { scrollToPage(currentPage + perRow) }
            "previous_page" -> if (currentPage == 0) "at_first_page" else null.also { scrollToPage((currentPage - perRow).coerceAtLeast(0)) }
            "go_to_page" -> when (val page = spokenNumber(value)) {
                null -> "missing_page"
                !in 1..pageCount -> "out_of_range"
                else -> null.also { scrollToPage(page - 1) }
            }
            "scroll_top" -> null.also { scrollToPage(0) }
            "scroll_bottom" -> null.also { scrollToPage(pageCount - 1) }
            "scroll_down", "scroll_up" -> {
                val state = if (mode == ViewMode.Grid) gridState else listState
                val screen = if (mode == ViewMode.Grid) gridState.layoutInfo.viewportSize.height else listState.layoutInfo.viewportSize.height
                state.animateScrollBy((if (operation == "scroll_down") 0.8f else -0.8f) * screen)
                null
            }
            "zoom_in", "zoom_out" -> when {
                mode == ViewMode.Grid -> "unsupported_here"
                operation == "zoom_in" && zoom >= MAX_ZOOM -> "at_limit"
                operation == "zoom_out" && zoom <= MIN_ZOOM -> "at_limit"
                else -> null.also { zoom = (zoom + if (operation == "zoom_in") ZOOM_STEP else -ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM) }
            }
            else -> "unsupported_here"
        }
    }

    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf(PdfMatches.None) }
    var searchStatus by remember { mutableStateOf<PdfSearchStatus>(PdfSearchStatus.Idle) }
    val horizontalScroll = rememberScrollState()
    val closeSearch = {
        searchOpen = false
        query = ""
    }
    val needle = query.trim()
    // Restarting on a new query or on leaving cancels the search in progress.
    LaunchedEffect(pages, searchOpen, needle) {
        if (!searchOpen || needle.isEmpty()) {
            matches = PdfMatches.None
            searchStatus = PdfSearchStatus.Idle
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MS)
        matches = PdfMatches.None
        searchStatus = searchPdf(
            pages = pages,
            query = needle,
            startPage = currentPage,
            onProgress = { searchStatus = it },
            onMatches = { page, found -> matches = matches.withPage(page, found) },
        )
    }
    val reveal: suspend (PdfSearchHit) -> Unit = { hit ->
        if (mode == ViewMode.Grid) gridState.animateScrollToItem(hit.page) else revealHit(hit, listState, horizontalScroll, perRow)
    }
    LaunchedEffect(matches.current) {
        reveal(matches.current ?: return@LaunchedEffect)
    }
    // The keyboard and the controls change the page's height. Lazy lists keep their position
    // through that, which can leave the current match below the new bottom edge.
    LaunchedEffect(mode) {
        var tracker = PdfMatchTracker.None
        snapshotFlow {
            matches.current?.let { hit ->
                if (mode == ViewMode.Grid) gridState.viewOf(hit) else listState.viewOf(hit, perRow)
            }
        }.collectLatest { view ->
            tracker = tracker.after(view)
            if (!tracker.resized) return@collectLatest
            delay(LAYOUT_SETTLE_MS)
            val outOfView = tracker.needsReveal
            tracker = tracker.settled()
            // Launched apart: the scroll changes the view, which would cancel it inside this block.
            if (outOfView && view != null) launch { reveal(view.hit) }
        }
    }

    // Selection and annotation. Pages keep drawing through the platform; all of this is an overlay.
    val editor = pages.editor
    val clipboard = LocalClipboardManager.current
    // Null until the file has been looked at. Saved annotations show once it has, on any Android version.
    val editInfo by produceState<PdfEditInfo?>(null, pages) { value = editor?.inspect() }
    var existing by remember(editInfo) { mutableStateOf(editInfo?.existing.orEmpty()) }
    val existingByPage = remember(existing) { existing.groupBy { it.page } }
    var history by remember { mutableStateOf(PdfAnnotationHistory.Empty) }
    var annotating by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(PdfTool.Hand) }
    var selection by remember { mutableStateOf<PdfSelection?>(null) }
    var selectJob by remember { mutableStateOf<Job?>(null) }
    var highlightColor by remember { mutableStateOf(HighlightColors.first()) }
    var highlightWidth by remember { mutableStateOf(HighlightWidths[1]) }
    var penColor by remember { mutableStateOf(PenColors.first()) }
    var penWidth by remember { mutableStateOf(PenWidths[1]) }
    var noteAtPoint by remember { mutableStateOf<Pair<Int, PdfPoint>?>(null) }
    var shownNote by remember { mutableStateOf<PdfAnnotation.Note?>(null) }
    val signing = remember { SignaturePlacer() }
    var saveStep by remember { mutableStateOf<SaveStep?>(null) }
    var canOverwrite by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }
    var dialogMessage by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }

    val runSave: (String, suspend () -> Unit) -> Unit = { success, work ->
        saving = true
        saveStep = null
        scope.launch {
            try {
                work()
                saveMessage = success
            } catch (e: DocumentException) {
                dialogMessage = "Couldn't save" to e.error.detail
            } finally {
                saving = false
            }
        }
    }
    val saver = rememberDocumentSaver { uri ->
        if (uri != null && editor != null) {
            val items = history.items
            runSave("Saved a copy with your annotations") {
                editor.saveCopy(items, uri)
                history = history.markSaved()
            }
        }
    }
    LaunchedEffect(saveMessage) {
        if (saveMessage != null) {
            delay(SAVE_MESSAGE_MS)
            saveMessage = null
        }
    }
    LaunchedEffect(saveStep) {
        if (saveStep == SaveStep.Choose) canOverwrite = editor?.canOverwrite() == true
    }

    val selectRange: (Int, PdfPoint, PdfPoint) -> Unit = { page, start, end ->
        // Only the newest drag position matters; the platform lock keeps these in order.
        selectJob?.cancel()
        selectJob = scope.launch {
            val found = try {
                pages.selectText(page, start, end)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // A damaged page has nothing to select.
            }
            val next = PdfSelection.of(page, found)
            if (next != null) selection = next
        }
    }
    val pageActions = PdfPageActions(
        tool = tool,
        inkColor = if (tool == PdfTool.Highlight) highlightColor else penColor,
        inkWidth = if (tool == PdfTool.Highlight) highlightWidth else penWidth,
        canSelectText = pages.canSelectText,
        onTap = { page, point, aspect ->
            when {
                selection != null -> selection = null
                signing.active != null -> signing.finish()
                signing.pending != null -> {
                    history = signing.placeAt(history, page, point, aspect)
                    tool = PdfTool.Hand
                }
                tool == PdfTool.Note -> noteAtPoint = page to point
                else -> shownNote = noteAt(existingByPage[page].orEmpty() + history.onPage(page), point, aspect)
            }
        },
        onLongPress = { page, point ->
            selection = null
            selectRange(page, point, point)
        },
        onExtendSelection = { page, to ->
            selection?.takeIf { it.page == page }?.let { active ->
                val (start, end) = active.rangeAfterDrag(PdfSelectionHandle.End, to)
                selectRange(page, start, end)
            }
        },
        onHandleDrag = { handle, to ->
            selection?.let { active ->
                val (start, end) = active.rangeAfterDrag(handle, to)
                selectRange(active.page, start, end)
            }
        },
        onStroke = { page, points ->
            val stroke = simplifyStroke(points)
            if (tool == PdfTool.Highlight) {
                val (color, width) = highlightColor to highlightWidth
                scope.launch {
                    val covered = if (pages.canSelectText) {
                        try {
                            pages.selectText(page, stroke.first(), stroke.last())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                    history = history.add(markerAnnotation(page, color, width, stroke, covered))
                }
            } else {
                history = history.add(PdfAnnotation.Ink(page, penColor, penWidth, listOf(stroke), PdfInkStyle.Pen))
            }
        },
        placement = signing.active,
        onPlacementChange = { history = signing.adjust(history, it) },
        onPlacementDone = signing::finish,
    )
    val finishAnnotating = {
        annotating = false
        tool = PdfTool.Hand
        signing.clear()
    }
    val requestAnnotate = {
        val block = editInfo?.saveBlock
        if (block != null) {
            dialogMessage = "Annotations can't be saved" to block.message
        } else {
            selection = null
            annotating = true
            if (mode == ViewMode.Grid) {
                val page = currentPage
                mode = ViewMode.Single
                scope.launch { listState.scrollToItem(page) }
            }
        }
    }

    // Leaving with unsaved annotations asks first, from the top bar and from system back alike.
    val dirty = history.isDirty
    DisposableEffect(exitGuard, dirty) {
        exitGuard?.confirm = if (dirty) ({ leave -> pendingLeave = leave }) else null
        onDispose { exitGuard?.confirm = null }
    }
    PlatformBackHandler(enabled = dirty) { pendingLeave = onClose }
    PlatformBackHandler(enabled = annotating, onBack = finishAnnotating)
    PlatformBackHandler(enabled = searchOpen, onBack = closeSearch)
    PlatformBackHandler(enabled = selection != null) { selection = null }

    val ime = WindowInsets.ime
    val density = LocalDensity.current
    // Derived so that the keyboard's animation recomposes this screen only when it opens or closes.
    val keyboardOpen by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val typingSearch = searchHidesPageControls(searchOpen, keyboardOpen, annotating)
    val controlsVisible = !typingSearch && !LocalImmersive.current
    // Reset on leaving, or the app's bar would stay hidden for the next document.
    DisposableEffect(searchOpen) {
        onSearchOpen(searchOpen)
        onDispose { onSearchOpen(false) }
    }
    if (barActions != null) {
        // Older Android has no PDF text search, so no control promises one.
        val search = if (pages.canSearch) ({ searchOpen = true }) else null
        val annotate = if (editor != null) ({ if (annotating) finishAnnotating() else requestAnnotate() }) else null
        SideEffect {
            barActions.search = search
            barActions.annotate = annotate
            // Until the file has been read, it isn't known whether a save can work.
            barActions.annotateReady = editInfo != null
            barActions.annotating = annotating
        }
        DisposableEffect(barActions) {
            onDispose {
                barActions.search = null
                barActions.annotate = null
            }
        }
    }

    val landscape = isWideLandscape()
    val compactLandscape = landscape && isShortWindow()
    var pinch by remember { mutableStateOf(1f) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().then(if (searchOpen) Modifier.imePadding() else Modifier)) {
        if (searchOpen) {
            PdfSearchBar(
                query = query,
                onQuery = { query = it },
                matches = matches,
                status = searchStatus,
                onPrevious = { matches = matches.previous() },
                onNext = { matches = matches.next() },
                onClose = closeSearch,
            )
            pages.textRecognitionNotice?.let { EzNotice(it, onDismiss = null) }
        }
        ReaderPaneLayout(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            landscape = landscape && !compactLandscape && !annotating,
            controlsVisible = annotating || controlsVisible || saveMessage != null,
            sideWidth = 192.dp,
            controls = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    saveMessage?.let { message ->
                        Text(
                            message,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = EzColors.Text,
                            modifier = Modifier.padding(top = 8.dp, start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    if (annotating) {
                        val drawing = tool == PdfTool.Pen || tool == PdfTool.Highlight
                        AnnotationToolbar(
                            tool = tool,
                            onTool = { chosen ->
                                signing.clear()
                                if (chosen == PdfTool.Signature) signing.startPicking()
                                tool = chosen
                            },
                            canUndo = history.canUndo,
                            canRedo = history.canRedo,
                            onUndo = { history = signing.undo(history) },
                            onRedo = {
                                signing.finish()
                                history = history.redo()
                            },
                            canSave = dirty && history.items.isNotEmpty(),
                            saving = saving,
                            onSave = {
                                signing.finish()
                                saveStep = SaveStep.Choose
                            },
                            onDone = finishAnnotating,
                            hint = when {
                                signing.hint != null -> signing.hint
                                tool == PdfTool.Note -> "Tap the page to add a note"
                                tool == PdfTool.Highlight && pages.canSelectText -> "Drag across text to highlight it"
                                tool == PdfTool.Highlight -> "Drag to draw a highlight"
                                tool == PdfTool.Pen -> "Draw with one finger, pinch with two"
                                else -> null
                            },
                            colors = when {
                                !drawing -> emptyList()
                                tool == PdfTool.Highlight -> HighlightColors
                                else -> PenColors
                            },
                            color = if (tool == PdfTool.Highlight) highlightColor else penColor,
                            onColor = { if (tool == PdfTool.Highlight) highlightColor = it else penColor = it },
                            widths = if (tool == PdfTool.Highlight) HighlightWidths else PenWidths,
                            width = if (tool == PdfTool.Highlight) highlightWidth else penWidth,
                            onWidth = { if (tool == PdfTool.Highlight) highlightWidth = it else penWidth = it },
                        )
                    } else {
                        AnimatedVisibility(visible = controlsVisible, enter = ControlsEnter, exit = ControlsExit) {
                          Column {
                            // Its own row above the toolbar, so it takes space from the pages rather than covering them.
                            if (pageCount > 1 && !compactLandscape) {
                                if (landscape) LandscapePageScrubber(pages, currentPage, goTo)
                                else PdfPageScrubber(pages, currentPage, goTo, Modifier.padding(top = 10.dp))
                            }
                            ReaderToolbar(
                                rail = landscape && !compactLandscape,
                                compact = compactLandscape,
                                currentPage = currentPage,
                                pageCount = pageCount,
                                zoom = zoom,
                                mode = mode,
                                onPrevious = { goTo(currentPage - perRow) },
                                onNext = { goTo(currentPage + perRow) },
                                onJump = { jumpOpen = true },
                                onZoom = { zoom = it.coerceIn(MIN_ZOOM, MAX_ZOOM) },
                                onMode = { newMode ->
                                    val page = currentPage
                                    mode = newMode
                                    scope.launch {
                                        when (newMode) {
                                            ViewMode.Grid -> gridState.scrollToItem(page)
                                            ViewMode.TwoUp -> listState.scrollToItem(page / 2)
                                            ViewMode.Single -> listState.scrollToItem(page)
                                        }
                                    }
                                },
                            )
                          }
                        }
                    }
                }
            },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp)
                    .clipToBounds()
                    // While a signature can move, a pinch resizes it instead of zooming.
                    .pointerInput(mode, signing.active != null) {
                        if (mode == ViewMode.Grid || signing.active != null) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var pinching = false
                            do {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    pinching = true
                                    pinch = (pinch * event.calculateZoom()).coerceIn(MIN_ZOOM / zoom.toFloat(), MAX_ZOOM / zoom.toFloat())
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (pinching) {
                                zoom = ((zoom * pinch) / 5).roundToInt().times(5).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                pinch = 1f
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = pinch
                        scaleY = pinch
                    },
            ) {
                val overlay: @Composable (Int, PdfPageActions?) -> Unit = { page, actions ->
                    val annotations = existingByPage[page].orEmpty() + history.onPage(page)
                    PdfPageOverlay(page, annotations, selection?.takeIf { it.page == page }, actions)
                }
                when (mode) {
                    ViewMode.Grid -> PageGrid(pages, gridState, matches, overlay = { overlay(it, null) }) { page ->
                        mode = ViewMode.Single
                        scope.launch { listState.scrollToItem(page) }
                    }
                    // A finger on a page draws while a drawing tool is on, so it must not also scroll.
                    else -> PageList(pages, listState, horizontalScroll, perRow, zoom, matches, !pageActions.drawsStrokes) {
                        overlay(it, pageActions)
                    }
                }
            }
        }

    }
        // Floats over the page: pushing the page down would move the text under the finger.
        selection?.let { active ->
            SelectionBar(
                text = active.text,
                canHighlight = editInfo != null && editInfo?.saveBlock == null,
                onCopy = {
                    clipboard.setText(AnnotatedString(active.text))
                    selection = null
                },
                onHighlight = {
                    history = history.add(PdfAnnotation.Highlight(active.page, highlightColor, active.boxes))
                    selection = null
                    // Save lives in annotate mode, so go where it can be reached.
                    annotating = true
                },
                onClose = { selection = null },
            )
        }
    }
    if (jumpOpen) {
        GoToPageDialog(pageCount, currentPage, onDismiss = { jumpOpen = false }) { page ->
            jumpOpen = false
            goTo(page)
        }
    }
    noteAtPoint?.let { (page, point) ->
        NoteEditorDialog(onDismiss = { noteAtPoint = null }) { text ->
            history = history.add(PdfAnnotation.Note(page, NOTE_COLOR, point.x, point.y, text))
            noteAtPoint = null
        }
    }
    shownNote?.let { note -> NoteViewerDialog(note) { shownNote = null } }
    SignatureChooser(signing, onDismiss = { tool = PdfTool.Hand })
    when (saveStep) {
        SaveStep.Choose -> SaveDialog(
            warning = editInfo?.let { saveWarning(it.signed, it.archival) },
            canOverwrite = canOverwrite,
            onSaveCopy = {
                saveStep = null
                saver.launch(annotatedFileName(displayName), "application/pdf")
            },
            onOverwrite = { saveStep = SaveStep.ConfirmOverwrite },
            onDismiss = { saveStep = null },
        )
        SaveStep.ConfirmOverwrite -> ConfirmDialog(
            title = "Update the original?",
            message = "Your annotations will be added to the original file. Its current content stays in the file, but Xfiles can't take the annotations out again.",
            confirm = "Update original",
            dismiss = "Cancel",
            onConfirm = {
                val items = history.items
                if (editor != null) {
                    runSave("Added your annotations to the original") {
                        editor.overwrite(items)
                        // They are in the file now, and no longer undoable here.
                        existing = existing + items
                        history = PdfAnnotationHistory.Empty
                    }
                }
            },
            onDismiss = { saveStep = null },
        )
        null -> Unit
    }
    // Blocks the page too, so nothing can be drawn into a save already under way.
    if (saving) EzBlockingProgress("Saving…")
    dialogMessage?.let { (title, message) -> MessageDialog(title, message) { dialogMessage = null } }
    pendingLeave?.let { leave ->
        ConfirmDialog(
            title = "Discard annotations?",
            message = "You have annotations that haven't been saved.",
            confirm = "Discard",
            dismiss = "Keep editing",
            onConfirm = {
                pendingLeave = null
                history = PdfAnnotationHistory.Empty
                leave()
            },
            onDismiss = { pendingLeave = null },
        )
    }
}

@Composable
private fun PageList(
    pages: PdfPages,
    state: LazyListState,
    horizontalScroll: ScrollState,
    perRow: Int,
    zoom: Int,
    matches: PdfMatches,
    scrollEnabled: Boolean,
    overlay: @Composable (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val contentWidth: Dp = maxWidth * (zoom / 100f)
        val rowWidth = if (zoom > 100) contentWidth else maxWidth
        val pageWidth = (rowWidth - 8.dp * (perRow - 1)) / perRow
        val pageWidthPx = with(LocalDensity.current) { pageWidth.roundToPx() }
        val rows = (pages.pageCount + perRow - 1) / perRow
        (pages as? CachedPdfPages)?.let { cache ->
            // Once scrolling rests, the next pages render on the one renderer while
            // nothing on screen is waiting; scrolling again cancels what hasn't started.
            LaunchedEffect(cache, state, perRow, pageWidthPx) {
                snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                    .filterNotNull()
                    .distinctUntilChanged()
                    .collectLatest { lastRow ->
                        delay(PREFETCH_IDLE_MS)
                        val next = (lastRow + 1) * perRow
                        for (index in next until next + PREFETCH_PAGES) cache.prefetch(index, pageWidthPx)
                    }
            }
        }
        Box(Modifier.fillMaxSize().then(if (zoom > 100) Modifier.horizontalScroll(horizontalScroll, enabled = scrollEnabled) else Modifier)) {
            LazyColumn(
                state = state,
                userScrollEnabled = scrollEnabled,
                modifier = Modifier.width(rowWidth).fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(count = rows, key = { it }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (index in row * perRow until minOf(pages.pageCount, (row + 1) * perRow)) {
                            PageCard(pages, index, pageWidthPx, matches, overlay, Modifier.width(pageWidth))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageGrid(
    pages: PdfPages,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    matches: PdfMatches,
    overlay: @Composable (Int) -> Unit,
    onOpen: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cellPx = with(LocalDensity.current) { ((maxWidth - 24.dp) / 3).roundToPx() }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = state,
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(count = pages.pageCount, key = { it }) { index ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PageCard(pages, index, cellPx, matches, overlay, Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = rememberHapticActivation { onOpen(index) }))
                    Text("${index + 1}", fontSize = 12.sp, color = EzColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/**
 * A page rendered [widthPx] wide, for as long as this stays composed. Through
 * the reader's cache it is held while shown, and given back a frame after it
 * leaves: by then no recorded frame draws it, so the pool may draw over it.
 */
@Composable
private fun rememberPageBitmap(pages: PdfPages, index: Int, widthPx: Int): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, pages, index, widthPx) {
        val cache = pages as? CachedPdfPages
        if (cache == null) {
            value = pages.render(index, widthPx)
            return@produceState
        }
        val page = cache.hold(index, widthPx) ?: return@produceState
        try {
            value = page.bitmap
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                withFrameNanos { }
                cache.release(page)
            }
        }
    }
    return bitmap
}

/** A page's thumbnail for the scrubber, held like a page while it's shown. */
@Composable
internal fun rememberThumbnail(pages: PdfPages, index: Int, widthPx: Int): ImageBitmap? = rememberPageBitmap(pages, index, widthPx)

/** One page: white card with a soft shadow; rendered only while composed. */
@Composable
private fun PageCard(
    pages: PdfPages,
    index: Int,
    widthPx: Int,
    matches: PdfMatches,
    overlay: @Composable (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberPageBitmap(pages, index, widthPx)
    val aspect by produceState(A4_ASPECT, pages, index) { value = pages.aspectRatio(index) }
    Box(
        modifier = modifier
            .aspectRatio(bitmap?.let { it.width.toFloat() / it.height } ?: aspect)
            .raised(EzShapes.Page, 10.dp)
            .clip(EzShapes.Page)
            .background(EzPaper.Background),
    ) {
        bitmap?.let { Image(it, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize()) }
        val hits = matches.onPage(index)
        overlay(index)
        if (hits.isNotEmpty()) MatchHighlights(hits, matches.current)
    }
}

/** Scales with the page card, so highlights hold at every zoom and in every view mode. */
@Composable
private fun MatchHighlights(hits: List<PdfSearchHit>, current: PdfSearchHit?) {
    Canvas(Modifier.fillMaxSize()) {
        for (hit in hits) {
            val isCurrent = hit == current
            for (box in hit.boxes) {
                val topLeft = Offset(box.left * size.width, box.top * size.height)
                val boxSize = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height)
                drawRect(if (isCurrent) EzColors.PdfSearchCurrent else EzColors.PdfSearchMatch, topLeft, boxSize)
                if (isCurrent) drawRect(EzColors.PdfSearchCurrentOutline, topLeft, boxSize, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** Brings a match into view: its page first, then the match itself on a page taller or wider than the screen. */
private suspend fun revealHit(hit: PdfSearchHit, list: LazyListState, horizontal: ScrollState, perRow: Int) {
    val box = hit.boxes.firstOrNull() ?: return
    val row = hit.page / perRow
    fun rowItem() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == row }
    if (rowItem() == null) list.animateScrollToItem(row)
    val item = rowItem() ?: return
    val viewportHeight = list.layoutInfo.viewportSize.height
    if (!matchInViewport(box, item.offset, item.size, viewportHeight)) {
        list.animateScrollToItem(row, (item.size * box.top - viewportHeight / 3f).roundToInt().coerceAtLeast(0))
    }
    if (horizontal.maxValue > 0) {
        val center = (hit.page % perRow + (box.left + box.right) / 2) / perRow * (horizontal.maxValue + horizontal.viewportSize)
        if (center < horizontal.value || center > horizontal.value + horizontal.viewportSize) {
            horizontal.animateScrollTo((center - horizontal.viewportSize / 2f).roundToInt().coerceIn(0, horizontal.maxValue))
        }
    }
}

private fun LazyListState.viewOf(hit: PdfSearchHit, perRow: Int): PdfMatchView {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == hit.page / perRow }
    return pdfMatchView(hit, info.viewportSize.height, item?.offset, item?.size ?: 0)
}

/** A cell also holds its page number, so this reads a match as slightly lower than it is. */
private fun LazyGridState.viewOf(hit: PdfSearchHit): PdfMatchView {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == hit.page }
    return pdfMatchView(hit, info.viewportSize.height, item?.offset?.y, item?.size?.height ?: 0)
}

@Composable
private fun PdfSearchBar(
    query: String,
    onQuery: (String) -> Unit,
    matches: PdfMatches,
    status: PdfSearchStatus,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .glassBar()
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = EzColors.Text),
                cursorBrush = SolidColor(EzColors.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { field ->
                    if (query.isEmpty()) Text("Search in PDF", fontSize = 16.sp, color = EzColors.TextSecondary)
                    field()
                },
            )
            if (matches.count > 0) {
                Text(
                    matches.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = EzColors.Text,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            BarIconButton(EzIcons.ChevronUp, "Previous match", onPrevious, enabled = matches.count > 0)
            BarIconButton(EzIcons.ChevronDown, "Next match", onNext, enabled = matches.count > 0)
            BarIconButton(EzIcons.Close, "Close search", onClose)
        }
        status.message()?.let { message ->
            Text(
                message,
                fontSize = 12.sp,
                color = EzColors.TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun ReaderToolbar(
    rail: Boolean = false,
    compact: Boolean = false,
    currentPage: Int,
    pageCount: Int,
    zoom: Int,
    mode: ViewMode,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJump: () -> Unit,
    onZoom: (Int) -> Unit,
    onMode: (ViewMode) -> Unit,
) {
    val haptics = rememberEzHaptics()
    if (compact) {
        CompactLandscapeToolbar(currentPage, pageCount, zoom, mode, onPrevious, onNext, onJump, onZoom, onMode)
        return
    }
    val toolbarModifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp, bottom = 10.dp)
        .glassBar()
        .padding(horizontal = 6.dp, vertical = 6.dp)
    val groups: @Composable () -> Unit = {
        if (rail) Column(Modifier.fillMaxWidth().glass(EzShapes.Pill, 0.75f), horizontalAlignment = Alignment.CenterHorizontally) {
            PageJumpLabel(currentPage, pageCount, onJump)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ToolbarButton(EzIcons.ChevronLeft, "Previous page", { haptics.pageTurn(); onPrevious() }, currentPage > 0, true)
                ToolbarButton(EzIcons.ChevronRight, "Next page", { haptics.pageTurn(); onNext() }, currentPage < pageCount - 1, true)
            }
        } else {
            Row(Modifier.glass(EzShapes.Pill, 0.75f), verticalAlignment = Alignment.CenterVertically) {
                ToolbarButton(EzIcons.ChevronLeft, "Previous page", { haptics.pageTurn(); onPrevious() }, currentPage > 0, rail)
                Row(
                    Modifier.clip(EzShapes.Pill).clickable(role = Role.Button, onClick = rememberHapticActivation(onJump)).padding(horizontal = 2.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${currentPage + 1} / $pageCount", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = EzColors.Text)
                    Icon(EzIcons.DropDown, "Go to page", tint = EzColors.Text, modifier = Modifier.size(18.dp))
                }
                ToolbarButton(EzIcons.ChevronRight, "Next page", { haptics.pageTurn(); onNext() }, currentPage < pageCount - 1, rail)
            }
        }
        Row(
            Modifier.glass(EzShapes.Pill, 0.75f).then(if (mode == ViewMode.Grid) Modifier.graphicsLayer { alpha = 0.45f } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarButton(EzIcons.Minus, "Zoom out", { onZoom(zoom - ZOOM_STEP) }, mode != ViewMode.Grid && zoom > MIN_ZOOM, rail)
            Text("$zoom%", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = EzColors.Text)
            ToolbarButton(EzIcons.Plus, "Zoom in", { onZoom(zoom + ZOOM_STEP) }, mode != ViewMode.Grid && zoom < MAX_ZOOM, rail)
        }
        Row(Modifier.glass(EzShapes.Pill, 0.75f).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeButton(EzIcons.SinglePage, "Single page", mode == ViewMode.Single, rail) { onMode(ViewMode.Single) }
            ModeButton(EzIcons.TwoPages, "Two pages", mode == ViewMode.TwoUp, rail) { onMode(ViewMode.TwoUp) }
            ModeButton(EzIcons.Grid, "Page grid", mode == ViewMode.Grid, rail) { onMode(ViewMode.Grid) }
        }
    }
    if (rail) Column(toolbarModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) { groups() }
    else Row(toolbarModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { groups() }
}

/** Short landscape windows keep one reading pane; secondary choices open on demand. */
@Composable
private fun CompactLandscapeToolbar(
    currentPage: Int,
    pageCount: Int,
    zoom: Int,
    mode: ViewMode,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onJump: () -> Unit,
    onZoom: (Int) -> Unit,
    onMode: (ViewMode) -> Unit,
) {
    val haptics = rememberEzHaptics()
    var viewMenu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 560.dp).glassBar().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolbarButton(EzIcons.ChevronLeft, "Previous page", { haptics.pageTurn(); onPrevious() }, currentPage > 0, true)
            Row(
                Modifier.heightIn(min = 48.dp).clip(EzShapes.Pill)
                    .clickable(role = Role.Button, onClick = rememberHapticActivation(onJump)).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${currentPage + 1} / $pageCount", color = EzColors.Text, fontSize = 13.sp, maxLines = 1)
                Icon(EzIcons.DropDown, "Go to page", tint = EzColors.Text, modifier = Modifier.size(18.dp))
            }
            ToolbarButton(EzIcons.ChevronRight, "Next page", { haptics.pageTurn(); onNext() }, currentPage < pageCount - 1, true)
            ToolbarButton(EzIcons.Minus, "Zoom out", { onZoom(zoom - ZOOM_STEP) }, mode != ViewMode.Grid && zoom > MIN_ZOOM, true)
            Text("$zoom%", color = EzColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
            ToolbarButton(EzIcons.Plus, "Zoom in", { onZoom(zoom + ZOOM_STEP) }, mode != ViewMode.Grid && zoom < MAX_ZOOM, true)
            Box {
                ToolbarButton(
                    when (mode) { ViewMode.Single -> EzIcons.SinglePage; ViewMode.TwoUp -> EzIcons.TwoPages; ViewMode.Grid -> EzIcons.Grid },
                    "View options", { viewMenu = true }, true, true,
                )
                EzDropdownMenu(viewMenu, { viewMenu = false }) {
                    EzMenuItem("Single page", { viewMenu = false; onMode(ViewMode.Single) }, EzIcons.SinglePage)
                    EzMenuItem("Two pages", { viewMenu = false; onMode(ViewMode.TwoUp) }, EzIcons.TwoPages)
                    EzMenuItem("Page grid", { viewMenu = false; onMode(ViewMode.Grid) }, EzIcons.Grid)
                }
            }
        }
    }
}

@Composable
private fun PageJumpLabel(currentPage: Int, pageCount: Int, onJump: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(EzShapes.Pill)
            .clickable(role = Role.Button, onClick = rememberHapticActivation(onJump)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text("${currentPage + 1} / $pageCount", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = EzColors.Text, maxLines = 1, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
        Icon(EzIcons.DropDown, "Go to page", tint = EzColors.Text, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ToolbarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, enabled: Boolean, rail: Boolean) {
    Box(Modifier.size(if (rail) 48.dp else 40.dp), contentAlignment = Alignment.Center) {
        BarIconButton(icon, label, onClick, enabled)
    }
}

@Composable
private fun ModeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, rail: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (rail) 48.dp else 38.dp)
            .clip(EzShapes.Pill)
            .background(if (selected) EzColors.Selected else Color.Transparent)
            .clickable(role = Role.Tab, onClick = rememberHapticChoice(selected, onClick))
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (selected) EzColors.Accent else EzColors.Text, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, current: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    var dragged by remember { mutableStateOf(current.toFloat()) }
    // A typed number wins; otherwise the slider's page.
    val page = text.toIntOrNull()?.takeIf { it in 1..pageCount }?.minus(1) ?: dragged.roundToInt().takeIf { text.isEmpty() }
    EzDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pageCount > 1) {
                    Text(
                        "Page ${dragged.roundToInt() + 1} of $pageCount",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EzColors.TextSecondary,
                    )
                    Slider(
                        value = dragged,
                        onValueChange = {
                            dragged = it
                            text = ""
                        },
                        valueRange = 0f..(pageCount - 1).toFloat(),
                        colors = SliderDefaults.colors(
                            thumbColor = EzColors.Primary,
                            activeTrackColor = EzColors.Primary,
                            inactiveTrackColor = EzColors.SliderTrack,
                        ),
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    label = { Text("Page number (1–$pageCount)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { page?.let(onGo) }),
                )
            }
        },
        confirmButton = { EzTextButton("Go", { page?.let(onGo) }, enabled = page != null) },
        dismissButton = { EzTextButton("Cancel", onDismiss, color = EzColors.TextSecondary) },
    )
}
