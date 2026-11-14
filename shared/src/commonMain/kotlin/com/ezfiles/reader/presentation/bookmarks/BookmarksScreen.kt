package com.ezfiles.reader.presentation.bookmarks

import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.Bookmark
import com.ezfiles.reader.domain.BookmarkList
import com.ezfiles.reader.platform.formatDate
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzDropdownMenu
import com.ezfiles.reader.presentation.components.EzMenuItem
import com.ezfiles.reader.presentation.components.EzTopBar
import com.ezfiles.reader.presentation.components.FileTypeTile
import com.ezfiles.reader.presentation.components.GlassIconButton
import com.ezfiles.reader.presentation.library.FileType
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.FileNameStyle
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.libraryBackground
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import kotlinx.coroutines.launch

/**
 * Every file's bookmarks, grouped by file (the most recently marked first),
 * for the home screen's Bookmarks tab. It draws no background of its own.
 * [onOpen] opens the file at the bookmark; a file that can't be reached any
 * more shows the reader's error, which offers to remove the bookmark.
 * [header] shows the "Bookmarks" title over the list.
 */
@Composable
fun BookmarksScreen(onOpen: (Bookmark) -> Unit, modifier: Modifier = Modifier, header: Boolean = true) {
    val bookmarks = LocalBookmarks.current
    val all by bookmarks.all.collectAsStateWithLifecycle()
    val groups = remember(all) { BookmarkList.byDocument(all) }
    val scope = rememberCoroutineScope()
    val remove: (Bookmark) -> Unit = { b -> scope.launch { bookmarks.remove(b.id) } }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = EzSpacing.Gutter, end = EzSpacing.Gutter, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(EzSpacing.ItemGap),
    ) {
        if (header) {
            item(key = "title", contentType = "title") {
                Text(
                    "Bookmarks",
                    style = MaterialTheme.typography.headlineMedium,
                    color = EzColors.Text,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                )
            }
        }
        if (groups.isEmpty()) {
            item(key = "empty", contentType = "empty") { EmptyBookmarks() }
        }
        items(groups, key = { it.first.identity }, contentType = { "file" }) { (ref, marks) ->
            Column(Modifier.fillMaxWidth().glass(EzShapes.Card, 0.44f).padding(vertical = 6.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    FileTypeTile(FileType.of(ref.displayName, marks.first().format))
                    Column(Modifier.weight(1f)) {
                        Text(ref.displayName, style = FileNameStyle, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (marks.size == 1) "1 bookmark" else "${marks.size} bookmarks",
                            style = MaterialTheme.typography.bodySmall,
                            color = EzColors.TextSecondary,
                        )
                    }
                }
                for (b in marks) {
                    androidx.compose.runtime.key(b.id) { BookmarkRow(b, onOpen = { onOpen(b) }, onRemove = { remove(b) }) }
                }
            }
        }
    }
}

/** One bookmark: swipe it away, or remove it from its menu. */
@Composable
private fun BookmarkRow(bookmark: Bookmark, onOpen: () -> Unit, onRemove: () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState()
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = rememberEzHaptics()
    LaunchedEffect(swipe) {
        snapshotFlow { swipe.targetValue }.collect { target ->
            if (target == SwipeToDismissBoxValue.EndToStart) haptics.threshold()
        }
    }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() },
        backgroundContent = {
            // Only while swiping: at rest the row shows the glass card beneath it.
            if (swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(
                Modifier.fillMaxSize().background(EzColors.Selected).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(EzIcons.Delete, contentDescription = null, tint = EzColors.Accent)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = EzSpacing.Touch)
                .clickable(role = Role.Button, onClickLabel = "Open at ${bookmark.locationLabel}", onClick = rememberHapticActivation(onOpen))
                .semantics { customActions = listOf(CustomAccessibilityAction("Remove bookmark") { onRemove(); true }) }
                .padding(start = 18.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(EzIcons.Bookmark, null, tint = EzColors.Primary, modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        bookmark.locationLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = EzColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        " · ${formatDate(bookmark.createdAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                        maxLines = 1,
                    )
                }
                if (bookmark.snippet.isNotBlank()) {
                    Text(
                        bookmark.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                BarIconButton(EzIcons.More, "More options for ${bookmark.locationLabel}", { menuOpen = true }, tint = EzColors.TextSecondary)
                EzDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    EzMenuItem("Open", onClick = {
                        menuOpen = false
                        onOpen()
                    }, icon = EzIcons.BookmarkOutline)
                    EzMenuItem("Remove bookmark", onClick = {
                        menuOpen = false
                        onRemove()
                    }, icon = EzIcons.Delete, destructive = true)
                }
            }
        }
    }
}

@Composable
private fun EmptyBookmarks() {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp).glass(EzShapes.Card, 0.5f).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(EzIcons.BookmarkOutline, null, tint = EzColors.Primary, modifier = Modifier.size(36.dp))
        Text("No bookmarks yet", style = MaterialTheme.typography.titleMedium, color = EzColors.Text, textAlign = TextAlign.Center)
        Text(
            "Open a PDF or an e-book and tap the bookmark at the top to mark the page you're on. Your bookmarks from every file gather here.",
            style = MaterialTheme.typography.bodyMedium,
            color = EzColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
