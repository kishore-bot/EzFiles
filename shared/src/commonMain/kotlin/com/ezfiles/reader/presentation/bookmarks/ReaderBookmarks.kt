package com.ezfiles.reader.presentation.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.app.currentTimeMillis
import com.ezfiles.reader.data.memoryBookmarks
import com.ezfiles.reader.domain.Bookmark
import com.ezfiles.reader.domain.BookmarkList
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.BookmarkSpot
import com.ezfiles.reader.domain.Bookmarks
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.formatDate
import com.ezfiles.reader.presentation.ReaderUiState
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzSheet
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import com.ezfiles.reader.presentation.theme.selectionPop
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Every file's bookmarks; the app provides the stored ones. */
val LocalBookmarks = staticCompositionLocalOf<Bookmarks> { memoryBookmarks() }

/**
 * The open viewer's bookmark controls in the reader's chrome: the viewer sets
 * them, the top bar and its menu draw them. Null [onToggle] hides the toggle,
 * null [onGo] hides the list; a viewer sets them only where they work.
 */
@Stable
class BookmarkBar {
    var bookmarked by mutableStateOf(false)
    var onToggle by mutableStateOf<(() -> Unit)?>(null)
    var onGo by mutableStateOf<((BookmarkLocation) -> Unit)?>(null)

    internal fun clear() {
        bookmarked = false
        onToggle = null
        onGo = null
    }
}

/** The bar of the reader on screen; viewers outside a reader get one nobody draws. */
val LocalBookmarkBar = staticCompositionLocalOf { BookmarkBar() }

/** Hands [bar] the viewer's controls while it's on screen, and takes them back when it leaves. */
@Composable
internal fun PublishBookmarkBar(
    bar: BookmarkBar,
    bookmarked: Boolean,
    onToggle: () -> Unit,
    onGo: (BookmarkLocation) -> Unit,
) {
    SideEffect {
        bar.bookmarked = bookmarked
        bar.onToggle = onToggle
        bar.onGo = onGo
    }
    DisposableEffect(bar) { onDispose { bar.clear() } }
}

/** Bookmarks for a PDF: the current [page] toggles, and the list jumps with [goTo]. */
@Composable
fun PdfBookmarks(ref: DocumentRef, page: Int, goTo: (Int) -> Unit) {
    val bookmarks = LocalBookmarks.current
    val all by bookmarks.all.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val location = BookmarkLocation.Page(page)
    PublishBookmarkBar(
        bar = LocalBookmarkBar.current,
        bookmarked = BookmarkList.find(all, BookmarkSpot(ref.identity, location)) != null,
        onToggle = {
            val bookmark = newBookmark(ref, DocumentFormat.Pdf, location)
            scope.launch { bookmarks.toggle(bookmark) }
        },
        onGo = { (it as? BookmarkLocation.Page)?.let { p -> goTo(p.index) } },
    )
}

@OptIn(ExperimentalUuidApi::class)
internal fun newBookmark(ref: DocumentRef, format: DocumentFormat?, location: BookmarkLocation, label: String = "", snippet: String = "") =
    Bookmark(Uuid.random().toHexString(), ref, format, location, label, snippet, currentTimeMillis())

/** The reader top bar's toggle: an outline, filled red once the place is bookmarked. Nothing where bookmarks don't work. */
@Composable
fun BookmarkToggleButton(bar: BookmarkBar) {
    val toggle = bar.onToggle ?: return
    val haptics = rememberEzHaptics()
    Box(Modifier.selectionPop(bar.bookmarked, dip = 0.7f)) {
        BarIconButton(
            if (bar.bookmarked) EzIcons.Bookmark else EzIcons.BookmarkOutline,
            if (bar.bookmarked) "Remove bookmark" else "Bookmark this page",
            {
                haptics.toggleIfNoFeedback(!bar.bookmarked, toggle)
            },
            tint = if (bar.bookmarked) EzColors.Primary else EzColors.Text,
        )
    }
}

/** This file's bookmarks, from the reader's menu; picking one jumps to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderBookmarksSheet(ref: DocumentRef, bar: BookmarkBar, onDismiss: () -> Unit) {
    val haptics = rememberEzHaptics()
    val bookmarks = LocalBookmarks.current
    val all by bookmarks.all.collectAsStateWithLifecycle()
    val mine = remember(all, ref.identity) { BookmarkList.forDocument(all, ref.identity).sortedWith(locationOrder) }
    val scope = rememberCoroutineScope()
    EzSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), title = "Bookmarks") {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            if (mine.isEmpty()) {
                Text(
                    "No bookmarks in this file yet. Tap the bookmark at the top to mark the page you're on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(mine, key = { it.id }) { b ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = EzSpacing.Touch)
                            .clickable(role = Role.Button, onClickLabel = "Go to ${b.locationLabel}") {
                                haptics.navigate()
                                bar.onGo?.invoke(b.location)
                                onDismiss()
                            }
                            .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Icon(EzIcons.Bookmark, null, tint = EzColors.Primary, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.locationLabel, style = MaterialTheme.typography.bodyLarge, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                b.snippet.ifBlank { formatDate(b.createdAt) },
                                style = MaterialTheme.typography.bodySmall,
                                color = EzColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BarIconButton(EzIcons.Delete, "Remove bookmark at ${b.locationLabel}", { scope.launch { bookmarks.remove(b.id) } }, tint = EzColors.TextSecondary)
                    }
                }
            }
        }
    }
}

/** In reading order: by page, or by chapter and place in it. */
internal val locationOrder: Comparator<Bookmark> = compareBy<Bookmark>(
    {
        when (val l = it.location) {
            is BookmarkLocation.Page -> l.index
            is BookmarkLocation.BookOffset -> l.chapter
        }
    },
    { (it.location as? BookmarkLocation.BookOffset)?.offset ?: 0 },
)

/**
 * The bookmark to offer removing when a file opened from it can't be reached
 * any more (access withdrawn, or the file gone); `null` otherwise.
 */
fun lostBookmark(state: ReaderUiState): BookmarkSpot? {
    val failed = state as? ReaderUiState.Failed ?: return null
    return failed.fromBookmark?.takeIf { failed.error is DocumentError.FileAccess }
}
