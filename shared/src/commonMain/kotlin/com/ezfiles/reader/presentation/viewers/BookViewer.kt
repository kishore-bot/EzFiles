package com.ezfiles.reader.presentation.viewers

import androidx.compose.animation.AnimatedVisibility
import com.ezfiles.reader.presentation.components.EzTextButton
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ezfiles.app.currentTimeMillis
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.Bookmark
import com.ezfiles.reader.domain.BookmarkList
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.presentation.bookmarks.LocalBookmarkBar
import com.ezfiles.reader.presentation.bookmarks.LocalBookmarks
import com.ezfiles.reader.presentation.bookmarks.PublishBookmarkBar
import com.ezfiles.reader.presentation.bookmarks.chapterLabel
import com.ezfiles.reader.presentation.bookmarks.migrateBookBookmarks
import com.ezfiles.reader.presentation.bookmarks.pageBookmarks
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.ezfiles.reader.presentation.theme.LocalReducedMotion
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import com.ezfiles.reader.presentation.theme.rememberHapticActivation
import com.ezfiles.reader.presentation.theme.rememberHapticChoice
import com.ezfiles.reader.presentation.theme.floatingGlass
import com.ezfiles.reader.presentation.components.ReaderPaneLayout
import com.ezfiles.reader.presentation.components.GlassIconButton
import com.ezfiles.reader.presentation.components.isWideLandscape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.presentation.PageFind
import com.ezfiles.reader.presentation.ReaderUiState
import com.ezfiles.reader.presentation.components.EzSheet
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

private const val BOOK_MANIFEST = "ezfiles:book.json"
private const val BOOK_READER = "ezfiles:book.html"

/** Colours of a reading theme; the reader page uses the same ones. */
internal data class BookTheme(val id: String, val name: String, val background: Color, val text: Color)

internal val BOOK_THEMES = listOf(
    BookTheme("white", "White", Color(0xFFFFFFFF), Color(0xFF1B1B1B)),
    BookTheme("sepia", "Sepia", Color(0xFFF4ECD8), Color(0xFF4A3B2A)),
    BookTheme("green", "Green", Color(0xFFCFE5CB), Color(0xFF1D2B1D)),
    BookTheme("black", "Black", Color(0xFF000000), Color(0xFFD2D2D2)),
)

private fun themeOf(settings: BookSettings) = BOOK_THEMES.firstOrNull { it.id == settings.theme } ?: BOOK_THEMES[0]

/** Scripts for the open reader page, run in order. */
internal class BookCommands {
    internal val scripts = MutableSharedFlow<String>(extraBufferCapacity = 32)

    fun run(script: String) {
        scripts.tryEmit(script)
    }
}

/**
 * The reader page in a web view: the book's own chapters laid out in pages.
 * [textZoom] is the text size in percent; [background] the theme's, as ARGB.
 * [onMessage] gets the page's messages as sent; check their token.
 */
@Composable
internal expect fun BookPage(
    page: String,
    resource: suspend (String) -> ByteArray?,
    textZoom: Int,
    background: Int,
    commands: BookCommands,
    query: String,
    step: Int,
    forward: Boolean,
    onFind: (PageFind) -> Unit,
    onMessage: (String) -> Unit,
    onFailure: (HtmlPageFailure) -> Unit,
    modifier: Modifier,
)

/** Remembers book settings and positions on this device. */
@Composable
internal expect fun rememberBookStore(): BookStore

private sealed interface BookLoad {
    data object Loading : BookLoad
    data object Failed : BookLoad
    data class Loaded(val manifest: BookManifest, val page: String) : BookLoad
}

private enum class BookSheet { Contents, Text }

/** The highlight being looked at or given a note; [fresh] when it was just made with the Note action. */
private data class Editing(val id: String, val fresh: Boolean)

/**
 * An e-book as an e-reader shows it: one page at a time in the book's own
 * layout, turned by tapping the sides or swiping, with the menu, contents and
 * text settings a tap in the middle away. Each book opens where it was left.
 * Chosen text can be highlighted or given a note, and pages bookmarked; the
 * Contents sheet lists them under Notebook.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookViewer(state: ReaderUiState.Ready, onFind: (PageFind) -> Unit, fallback: @Composable () -> Unit) {
    val immersive = LocalImmersive.current
    val landscape = isWideLandscape()
    key(state.openId) {
        val haptics = rememberEzHaptics()
        val reducedMotion = LocalReducedMotion.current
        val resource = LocalDocumentResource.current
        val store = rememberBookStore()
        val bookKey = remember(state.info) { "${state.info.name}|${state.info.byteSize}" }
        val token = remember { newBookToken() }
        var settings by remember { mutableStateOf(store.settings() ?: BookSettings()) }
        val commands = remember { BookCommands() }
        val scope = rememberCoroutineScope()
        var annotations by remember { mutableStateOf(BookAnnotations()) }
        // Bookmarks: this book's bookmarks live with every file's, not in its notes.
        val bookmarks = LocalBookmarks.current
        val allBookmarks by bookmarks.all.collectAsStateWithLifecycle()
        val myBookmarks = remember(allBookmarks) { pageBookmarks(BookmarkList.forDocument(allBookmarks, state.ref.identity)) }
        // Opened from a bookmark: its chapter loads first, then the page goes to the place.
        var jumpTo by remember { mutableStateOf(state.initialLocation as? BookmarkLocation.BookOffset) }
        val load by produceState<BookLoad>(BookLoad.Loading) {
            val manifest = resource(BOOK_MANIFEST)?.let(::decodeBookManifest)
            val shell = resource(BOOK_READER)
            value = if (manifest == null || shell == null || manifest.chapters.isEmpty()) {
                BookLoad.Failed
            } else {
                val jump = jumpTo?.takeIf { it.chapter < manifest.chapters.size }
                if (jump == null) jumpTo = null
                val start = jump?.let { BookPosition(it.chapter, 0.0) } ?: store.position(bookKey)?.takeIf { it.chapter < manifest.chapters.size }
                var saved = withContext(Dispatchers.Default) { store.annotations(bookKey) }
                if (saved.bookmarks.isNotEmpty()) {
                    // Bookmarks kept with the book by earlier versions move to the shared list.
                    val migration = migrateBookBookmarks(saved, state.ref, manifest, currentTimeMillis())
                    bookmarks.addAll(migration.moved)
                    withContext(Dispatchers.Default) { store.saveAnnotations(bookKey, migration.left) }
                    saved = migration.left
                }
                annotations = saved
                val marks = pageBookmarks(BookmarkList.forDocument(bookmarks.all.value, state.ref.identity))
                val page = withContext(Dispatchers.Default) {
                    secureHtml(bookReaderPage(shell.decodeToString(), token, start, settings, saved.copy(bookmarks = marks), reducedMotion), PageKind.Book.contentSecurityPolicy)
                }
                BookLoad.Loaded(manifest, page)
            }
        }
        var position by remember { mutableStateOf<BookMessage.Position?>(null) }
        var menu by remember { mutableStateOf(false) }
        var sheet by remember { mutableStateOf<BookSheet?>(null) }
        var failure by remember { mutableStateOf<HtmlPageFailure?>(null) }
        var prompt by remember { mutableStateOf<LinkPrompt?>(null) }
        var editing by remember { mutableStateOf<Editing?>(null) }
        var choosing by remember { mutableStateOf<BookMessage.Selection?>(null) }
        val theme = themeOf(settings)

        /** Keeps [new] and tells the page; saving happens off the main thread. */
        fun annotate(new: BookAnnotations, redraw: Boolean = true) {
            annotations = new
            scope.launch(Dispatchers.Default) { store.saveAnnotations(bookKey, new) }
            if (redraw) commands.run(setHighlightsScript(new.highlights))
        }

        fun change(new: BookSettings) {
            settings = new
            store.saveSettings(new)
            commands.run(applyBookSettingsScript(new))
        }

        Box(Modifier.fillMaxSize().background(theme.background)) {
            val current = load
            val shownFailure = failure
            when {
                current is BookLoad.Loading -> CircularProgressIndicator(color = EzColors.Primary, modifier = Modifier.align(Alignment.Center))
                // Only the text, on its own, when the book can't be laid out.
                current is BookLoad.Failed -> Column(Modifier.fillMaxSize().background(EzPaper.Background)) {
                    HtmlNote("This book couldn't be laid out as pages, so only its text is shown.")
                    Box(Modifier.weight(1f).fillMaxWidth()) { fallback() }
                }
                shownFailure != null -> BookNote(shownFailure.message, theme)
                current is BookLoad.Loaded -> {
                    val manifest = current.manifest
                    val entry = position?.let { manifest.entryAt(it.chapter) } ?: -1
                    val chapterTitle = manifest.contents.getOrNull(entry)?.title ?: manifest.title
                    val faint = theme.text.copy(alpha = 0.55f)
                    ReaderPaneLayout(
                        modifier = Modifier.fillMaxSize(),
                        landscape = landscape,
                        controlsVisible = landscape && menu,
                        sideWidth = 232.dp,
                        controls = {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                BookMenuTop(
                                    title = manifest.title,
                                    bookmarked = position?.bookmark != null,
                                    onContents = { sheet = BookSheet.Contents },
                                    onText = { sheet = BookSheet.Text },
                                    onBookmark = { haptics.deferFeedback { commands.run(TOGGLE_BOOKMARK_SCRIPT) } },
                                    onClose = { menu = false },
                                    compact = true,
                                )
                                BookMenuBottom(
                                    chapterTitle = chapterTitle,
                                    position = position,
                                    rtl = manifest.rtl,
                                    onGo = { commands.run(goToPercentScript(it)) },
                                    onPrevious = { haptics.deferFeedback { commands.run("window.ez&&ez.prev()") } },
                                    onNext = { haptics.deferFeedback { commands.run("window.ez&&ez.next()") } },
                                )
                            }
                        },
                    ) {
                    Column(Modifier.fillMaxSize()) {
                        AnimatedVisibility(menu && !landscape, enter = fadeIn(), exit = fadeOut()) {
                            BookMenuTop(
                                title = manifest.title,
                                bookmarked = position?.bookmark != null,
                                onContents = { sheet = BookSheet.Contents },
                                onText = { sheet = BookSheet.Text },
                                onBookmark = { haptics.deferFeedback { commands.run(TOGGLE_BOOKMARK_SCRIPT) } },
                                onClose = { menu = false },
                            )
                        }
                        if (!immersive && !menu && !landscape) Text(
                            chapterTitle,
                            color = faint,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 10.dp, bottom = 2.dp),
                        )
                        BookPage(
                            page = current.page,
                            resource = resource,
                            textZoom = settings.size,
                            background = theme.background.toArgb(),
                            commands = commands,
                            query = state.search?.results?.query.orEmpty(),
                            step = state.search?.pageStep ?: 0,
                            forward = state.search?.pageStepForward ?: true,
                            onFind = onFind,
                            onMessage = { raw ->
                                when (val message = parseBookMessage(raw, token)) {
                                    is BookMessage.Position -> {
                                        jumpTo?.let {
                                            jumpTo = null
                                            commands.run(goToOffsetScript(it.chapter, it.offset))
                                        }
                                        position = message
                                        store.savePosition(bookKey, BookPosition(message.chapter, message.fraction))
                                    }
                                    BookMessage.Turned -> haptics.pageTurn()
                                    BookMessage.Menu -> { haptics.activate(); menu = !menu }
                                    is BookMessage.Highlighted -> {
                                        haptics.confirm()
                                        val h = message.highlight.copy(created = currentTimeMillis())
                                        // The page has drawn it already.
                                        annotate(annotations.withHighlight(h), redraw = false)
                                        if (message.wantsNote) editing = Editing(h.id, fresh = true)
                                    }
                                    is BookMessage.HighlightTapped -> if (annotations.highlights.any { it.id == message.id }) editing = Editing(message.id, fresh = false)
                                    is BookMessage.Bookmarked -> {
                                        haptics.toggle(true)
                                        val b = message.bookmark
                                        val bookmark = Bookmark(
                                            b.id, state.ref, DocumentFormat.Epub, BookmarkLocation.BookOffset(b.chapter, b.offset),
                                            chapterLabel(manifest, b.chapter), b.text, currentTimeMillis(),
                                        )
                                        scope.launch { bookmarks.add(bookmark) }
                                    }
                                    is BookMessage.Unbookmarked -> { haptics.toggle(false); scope.launch { bookmarks.remove(message.id) } }
                                    is BookMessage.Selection -> choosing = message.takeIf { it.active }
                                    is BookMessage.Link -> prompt = LinkPrompt.Open(message.url)
                                    BookMessage.Failed -> failure = HtmlPageFailure.Stopped
                                    BookMessage.End, null -> {}
                                }
                            },
                            onFailure = { failure = it },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        if (!immersive && !menu) Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp)) {
                            val p = position
                            Text(
                                when {
                                    p == null -> ""
                                    manifest.fixed -> "Page ${p.chapter + 1} of ${manifest.chapters.size}"
                                    p.page >= p.pages - 1 -> "Last page in chapter"
                                    p.pages - p.page - 1 == 1 -> "1 page left in chapter"
                                    else -> "${p.pages - p.page - 1} pages left in chapter"
                                },
                                color = faint,
                                fontSize = 12.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            Text(p?.let { "${(it.percent * 100).toInt()}%" }.orEmpty(), color = faint, fontSize = 12.sp)
                        }
                        AnimatedVisibility(menu && !landscape, enter = fadeIn(), exit = fadeOut()) {
                            BookMenuBottom(
                                chapterTitle = chapterTitle,
                                position = position,
                                rtl = manifest.rtl,
                                onGo = { commands.run(goToPercentScript(it)) },
                                onPrevious = { haptics.deferFeedback { commands.run("window.ez&&ez.prev()") } },
                                onNext = { haptics.deferFeedback { commands.run("window.ez&&ez.next()") } },
                            )
                        }
                    }
                    }
                    val chosen = choosing
                    if (chosen != null && !menu) {
                        HighlightBar(
                            onColor = { color ->
                                commands.run(highlightSelectionScript(withNote = false, color = color))
                                choosing = null
                            },
                            onNote = {
                                commands.run(highlightSelectionScript(withNote = true))
                                choosing = null
                            },
                            modifier = Modifier.align(if (chosen.low) Alignment.TopCenter else Alignment.BottomCenter)
                                .padding(horizontal = 16.dp, vertical = 40.dp),
                        )
                    }
                    if (position?.bookmark != null && !menu) {
                        Icon(
                            EzIcons.Bookmark,
                            contentDescription = "This page is bookmarked",
                            tint = EzColors.Primary,
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 18.dp).size(width = 20.dp, height = 30.dp),
                        )
                    }
                    // Bookmarks: the reader's top bar toggles this page's bookmark and jumps to others.
                    PublishBookmarkBar(
                        bar = LocalBookmarkBar.current,
                        bookmarked = position?.bookmark != null,
                        onToggle = { haptics.deferFeedback { commands.run(TOGGLE_BOOKMARK_SCRIPT) } },
                        onGo = { (it as? BookmarkLocation.BookOffset)?.let { at -> commands.run(goToOffsetScript(at.chapter, at.offset)) } },
                    )
                    when (sheet) {
                        BookSheet.Contents -> EzSheet(
                            onDismissRequest = { sheet = null },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        ) {
                            ContentsSheet(
                                manifest = manifest,
                                current = entry,
                                annotations = annotations.copy(bookmarks = myBookmarks),
                                onOpen = { href ->
                                    commands.run(goToHrefScript(href))
                                    sheet = null
                                    menu = false
                                },
                                onOpenAt = { chapter, offset ->
                                    commands.run(goToOffsetScript(chapter, offset))
                                    sheet = null
                                    menu = false
                                },
                                onEditHighlight = { editing = Editing(it, fresh = false) },
                                onDeleteBookmark = { id -> scope.launch { bookmarks.remove(id) } },
                            )
                        }
                        BookSheet.Text -> EzSheet(
                            onDismissRequest = { sheet = null },
                            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        ) {
                            TextSheet(settings, fixed = manifest.fixed, onChange = ::change)
                        }
                        null -> {}
                    }
                }
            }
        }
        val edited = editing?.let { e -> annotations.highlights.firstOrNull { it.id == e.id }?.let { it to e.fresh } }
        if (edited != null) {
            EzSheet(
                onDismissRequest = { editing = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                HighlightSheet(
                    highlight = edited.first,
                    focusNote = edited.second,
                    onChange = { annotate(annotations.withHighlight(it)) },
                    onDelete = {
                        annotate(annotations.withoutHighlight(edited.first.id))
                        editing = null
                    },
                    onDone = { editing = null },
                )
            }
        }
        LinkDialog(prompt, onPrompt = { prompt = it })
        LaunchedEffect(Unit) { commands.run(applyBookSettingsScript(settings)) }
        LaunchedEffect(reducedMotion) { commands.run("window.ez&&ez.setReducedMotion($reducedMotion)") }
        // Bookmarks removed elsewhere (the reader's list, the Bookmarks tab) leave the page too.
        LaunchedEffect(myBookmarks) { if (load is BookLoad.Loaded) commands.run(setBookmarksScript(myBookmarks)) }
    }
}

private fun Color.toArgb(): Int {
    fun c(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (c(alpha) shl 24) or (c(red) shl 16) or (c(green) shl 8) or c(blue)
}

@Composable
private fun BookNote(text: String, theme: BookTheme) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = theme.text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

private val MenuShape = RoundedCornerShape(18.dp)

@Composable
private fun BookMenuTop(
    title: String,
    bookmarked: Boolean,
    onContents: () -> Unit,
    onText: () -> Unit,
    onBookmark: () -> Unit,
    onClose: () -> Unit,
    compact: Boolean = false,
) {
    if (compact) {
        Column(
            Modifier.fillMaxWidth().padding(10.dp).floatingGlass(MenuShape, 0.85f)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = EzColors.Text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                GlassIconButton(EzIcons.Close, "Close menu", onClose)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                GlassIconButton(EzIcons.ListLines, "Contents", onContents)
                GlassIconButton(EzIcons.TextT, "Reading settings", onText)
                GlassIconButton(if (bookmarked) EzIcons.Bookmark else EzIcons.BookmarkOutline,
                    if (bookmarked) "Remove bookmark" else "Bookmark this page", onBookmark)
            }
        }
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .floatingGlass(MenuShape, 0.85f)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuButton(EzIcons.ListLines, "Contents", onContents)
        MenuButton(EzIcons.TextT, "Aa", onText)
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = EzColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
        )
        MenuButton(
            if (bookmarked) EzIcons.Bookmark else EzIcons.BookmarkOutline,
            null,
            onBookmark,
            description = if (bookmarked) "Remove bookmark" else "Bookmark this page",
        )
        MenuButton(EzIcons.Close, null, onClose, description = "Close menu")
    }
}

@Composable
private fun MenuButton(icon: ImageVector, label: String?, onClick: () -> Unit, description: String? = label) {
    val activate = rememberHapticActivation(onClick)
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = activate)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = if (label == null) description else null, tint = EzColors.Text, modifier = Modifier.size(22.dp))
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = EzColors.Text)
        }
    }
}

@Composable
private fun BookMenuBottom(
    chapterTitle: String,
    position: BookMessage.Position?,
    rtl: Boolean,
    onGo: (Float) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    val haptics = rememberEzHaptics()
    var value by remember { mutableFloatStateOf(0f) }
    if (!dragging) value = position?.percent?.toFloat() ?: 0f
    Column(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .floatingGlass(MenuShape, 0.85f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(chapterTitle, style = MaterialTheme.typography.titleSmall, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // A right-to-left book starts at the right: the slider fills from the right, and "back" points right.
        CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (rtl) EzIcons.ChevronRight else EzIcons.ChevronLeft,
                contentDescription = "Previous page",
                tint = EzColors.Text,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onPrevious).padding(6.dp),
            )
            Slider(
                value = value,
                onValueChange = {
                    dragging = true
                    if ((it * 100).toInt() != (value * 100).toInt()) haptics.step()
                    value = it
                },
                onValueChangeFinished = {
                    dragging = false
                    haptics.navigate()
                    onGo(value)
                },
                colors = SliderDefaults.colors(thumbColor = EzColors.Primary, activeTrackColor = EzColors.Primary),
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (rtl) EzIcons.ChevronLeft else EzIcons.ChevronRight,
                contentDescription = "Next page",
                tint = EzColors.Text,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onNext).padding(6.dp),
            )
        }
        }
        Text(
            buildString {
                if (position != null) append("Page ${position.page + 1} of ${position.pages} in chapter · ")
                append("${(value * 100).toInt()}% of the book")
            },
            style = MaterialTheme.typography.labelMedium,
            color = EzColors.Text,
        )
    }
}

@Composable
private fun ContentsSheet(
    manifest: BookManifest,
    current: Int,
    annotations: BookAnnotations,
    onOpen: (String) -> Unit,
    onOpenAt: (chapter: Int, offset: Int) -> Unit,
    onEditHighlight: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
) {
    var notebook by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
            Text(manifest.title.ifEmpty { "Contents" }, style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
            if (manifest.author.isNotEmpty()) Text(manifest.author, style = MaterialTheme.typography.bodySmall, color = EzColors.TextSecondary)
        }
        val count = annotations.highlights.size + annotations.bookmarks.size
        Choices(
            label = null,
            options = listOf(false to "Contents", true to if (count > 0) "Notebook ($count)" else "Notebook"),
            selected = notebook,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        ) { notebook = it }
        if (!notebook) {
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
            LazyColumn(state = list, modifier = Modifier.heightIn(max = 560.dp)) {
                itemsIndexed(manifest.contents) { i, item ->
                    val selected = i == current
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else if (item.depth == 0) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) EzColors.Accent else EzColors.Text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) EzColors.Primary.copy(alpha = 0.08f) else Color.Transparent)
                            .clickable(role = Role.Button, onClick = rememberHapticActivation { onOpen(item.href) })
                            .padding(start = (24 + 18 * item.depth.coerceAtMost(4)).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
        } else if (count == 0) {
            Text(
                "Nothing here yet. Press and hold text to highlight it or add a note, and use the bookmark in the menu to mark a page.",
                style = MaterialTheme.typography.bodyMedium,
                color = EzColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            // Everything in reading order, bookmarks among the highlights.
            val items = remember(annotations) {
                (annotations.bookmarks.map { NotebookItem(it.chapter, it.offset, bookmark = it) } +
                    annotations.highlights.map { NotebookItem(it.chapter, it.start, highlight = it) })
                    .sortedWith(compareBy({ it.chapter }, { it.offset }))
            }
            LazyColumn(Modifier.heightIn(max = 560.dp)) {
                itemsIndexed(items) { _, item ->
                    NotebookRow(
                        item = item,
                        chapterTitle = manifest.contents.getOrNull(manifest.entryAt(item.chapter))?.title.orEmpty(),
                        onOpen = { onOpenAt(item.chapter, item.offset) },
                        onEdit = { item.highlight?.let { onEditHighlight(it.id) } },
                        onDelete = { item.bookmark?.let { onDeleteBookmark(it.id) } },
                    )
                }
            }
        }
    }
}

private class NotebookItem(val chapter: Int, val offset: Int, val bookmark: BookBookmark? = null, val highlight: BookHighlight? = null)

private val MARK_SWATCHES = mapOf(
    "yellow" to Color(0xFFFFD84D),
    "green" to Color(0xFF7FD99A),
    "blue" to Color(0xFF7FB8FF),
    "pink" to Color(0xFFFF8FBF),
)

@Composable
private fun NotebookRow(item: NotebookItem, chapterTitle: String, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Go there", onClick = rememberHapticActivation(onOpen)).padding(start = 24.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val h = item.highlight
        if (h != null) {
            Box(Modifier.padding(top = 4.dp).size(width = 4.dp, height = 36.dp).clip(RoundedCornerShape(2.dp)).background(MARK_SWATCHES[h.color] ?: MARK_SWATCHES.getValue("yellow")))
        } else {
            Icon(EzIcons.Bookmark, contentDescription = null, tint = EzColors.Primary, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                (if (h != null) (if (h.note.isNotBlank()) "Note" else "Highlight") else "Bookmark") + if (chapterTitle.isNotEmpty()) " · $chapterTitle" else "",
                style = MaterialTheme.typography.labelMedium,
                color = EzColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val quote = h?.text ?: item.bookmark?.text.orEmpty()
            if (quote.isNotBlank()) {
                Text(quote, style = MaterialTheme.typography.bodyMedium, color = EzColors.Text, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (h != null && h.note.isNotBlank()) {
                Text(h.note, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = EzColors.Accent, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        if (h != null) {
            MenuButton(EzIcons.Edit, null, onEdit, description = "Edit highlight")
        } else {
            MenuButton(EzIcons.Delete, null, onDelete, description = "Remove bookmark")
        }
    }
}

/** Shown while text is chosen: a colour highlights it at once; Note highlights it and asks for the note. */
@Composable
private fun HighlightBar(onColor: (String) -> Unit, onNote: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(EzColors.Scrim.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (c in HIGHLIGHT_COLORS) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MARK_SWATCHES.getValue(c))
                    .clickable(role = Role.Button, onClickLabel = "Highlight in $c") { onColor(c) },
            )
        }
        Text(
            "Note",
            color = EzColors.OnScrim,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onNote).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** A highlight's colour and note, and a way to take it away. */
@Composable
private fun HighlightSheet(
    highlight: BookHighlight,
    focusNote: Boolean,
    onChange: (BookHighlight) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    var note by remember(highlight.id) { mutableStateOf(highlight.note) }
    val focus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            highlight.text,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Serif,
            color = EzColors.Text,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background((MARK_SWATCHES[highlight.color] ?: MARK_SWATCHES.getValue("yellow")).copy(alpha = 0.35f)).padding(12.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            for (c in HIGHLIGHT_COLORS) {
                val selected = c == highlight.color
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MARK_SWATCHES.getValue(c))
                        .border(if (selected) 3.dp else 0.dp, if (selected) EzColors.Text else Color.Transparent, CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = c, onClick = rememberHapticChoice(selected) { onChange(highlight.copy(color = c, note = note)) }),
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(MAX_NOTE_CHARS) },
            label = { Text("Note") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            EzTextButton("Delete highlight", onDelete)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                if (note != highlight.note) onChange(highlight.copy(note = note.trim()))
                onDone()
            }) { Text("Done", color = EzColors.Accent, fontWeight = FontWeight.SemiBold) }
        }
    }
    if (focusNote) LaunchedEffect(Unit) { focus.requestFocus() }
}

private const val MAX_NOTE_CHARS = 4000

@Composable
private fun TextSheet(settings: BookSettings, fixed: Boolean, onChange: (BookSettings) -> Unit) {
    val haptics = rememberEzHaptics()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Theme", style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (t in BOOK_THEMES) {
                val selected = t.id == settings.theme
                Column(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton, onClickLabel = t.name, enabled = !selected) { haptics.select(); onChange(settings.copy(theme = t.id)) }
                        .padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(t.background)
                            .border(if (selected) 3.dp else 1.dp, if (selected) EzColors.Primary else EzColors.FieldOutline, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Aa", color = t.text, fontSize = 18.sp, fontFamily = FontFamily.Serif)
                    }
                    Text(t.name, style = MaterialTheme.typography.labelSmall, color = EzColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        if (!fixed) {
            Text("Text size", style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SizeButton("A", 15.sp, "Smaller text", enabled = settings.size > BookSettings.TEXT_SIZES.first()) { onChange(settings.smaller()) }
                Text("${settings.size}%", style = MaterialTheme.typography.titleMedium, color = EzColors.Text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                SizeButton("A", 24.sp, "Larger text", enabled = settings.size < BookSettings.TEXT_SIZES.last()) { onChange(settings.larger()) }
            }
            Choices(
                "Font",
                listOf("original" to "Book's own", "serif" to "Serif", "book" to "Caladea", "sans" to "Sans"),
                settings.font,
            ) { onChange(settings.copy(font = it)) }
            Choices(
                "Line spacing",
                listOf(0.0 to "Book's own", 1.3 to "Tight", 1.5 to "Normal", 1.8 to "Loose"),
                settings.spacing,
            ) { onChange(settings.copy(spacing = it)) }
            Choices("Margins", listOf(0 to "Narrow", 1 to "Normal", 2 to "Wide"), settings.margin) { onChange(settings.copy(margin = it)) }
            Choices("Alignment", listOf(false to "Book's own", true to "Justified"), settings.justify) { onChange(settings.copy(justify = it)) }
        }
    }
}

@Composable
private fun SizeButton(label: String, size: androidx.compose.ui.unit.TextUnit, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(1.dp, EzColors.FieldOutline, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = rememberHapticChoice(false, onClick)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = size, fontFamily = FontFamily.Serif, color = if (enabled) EzColors.Text else EzColors.TextSecondary.copy(alpha = 0.4f))
    }
}

@Composable
private fun <T> Choices(label: String?, options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, onSelect: (T) -> Unit) {
    val haptics = rememberEzHaptics()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, name) in options) {
                val on = value == selected
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) EzColors.OnPrimary else EzColors.Text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) EzColors.Primary else Color.Transparent)
                        .border(1.dp, if (on) EzColors.Primary else EzColors.FieldOutline, RoundedCornerShape(50))
                        .clickable(role = Role.RadioButton, enabled = !on) { haptics.select(); onSelect(value) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}
