package com.ezfiles.reader.presentation.library

import com.ezfiles.reader.presentation.components.EzDialog
import androidx.compose.ui.draw.clip
import com.ezfiles.reader.presentation.components.EzTextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.graphics.vector.ImageVector
import com.ezfiles.reader.presentation.components.EzFloatingAction
import com.ezfiles.reader.presentation.components.EzNavItem
import com.ezfiles.reader.presentation.components.EzNavigationBar
import com.ezfiles.reader.presentation.components.GlassIconButton
import com.ezfiles.reader.presentation.components.isWideLandscape
import com.ezfiles.reader.presentation.theme.glassCard
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.platform.rememberFolderPicker
import com.ezfiles.reader.presentation.rememberDocumentDeliveryActions
import com.ezfiles.reader.presentation.tidy.TidyIcons
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.components.EzDropdownMenu
import com.ezfiles.reader.presentation.components.EzMenuItem
import com.ezfiles.reader.presentation.components.FileCard
import com.ezfiles.reader.presentation.components.MediaPreviewTile
import com.ezfiles.reader.presentation.components.SecondaryActionButton
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.libraryBackground
import com.ezfiles.reader.presentation.theme.appSafeArea
import com.ezfiles.reader.presentation.theme.pressScale

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenFile: () -> Unit,
    onCreate: () -> Unit,
    onOpenRecent: (DocumentRef) -> Unit,
    /** The menu button (top left): the app opens its drawer. */
    /** The info button (top right); null hides it. */
    onOpenAbout: (() -> Unit)? = null,
    /** The mic button; null hides it. */
    onVoice: (() -> Unit)? = null,
    /** The Bookmarks tab's content; null shows a short explanation instead. */
    bookmarksContent: (@Composable () -> Unit)? = null,
    /** A file found by its text, with the PDF page (from 0) the match is on, if known. */
    onOpenHit: (DocumentRef, Int?) -> Unit = { ref, _ -> onOpenRecent(ref) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Echo edits in the same frame; background ranking must never feed stale text back to the IME.
    var searchInput by rememberSaveable { mutableStateOf(state.query) }
    // Re-list on every return: files change, and folder access can be revoked, while we're away.
    // The search index is built only while the library shows, so it never competes with a document.
    LifecycleResumeEffect(Unit) {
        viewModel.onQueryChange(searchInput)
        viewModel.refreshDevice()
        viewModel.onVisibleChange(true)
        onPauseOrDispose { viewModel.onVisibleChange(false) }
    }
    val pickFolder = rememberFolderPicker(onPicked = viewModel::addFolder, onUnavailable = viewModel::onFolderPickerUnavailable)
    LibraryScreen(
        state = state,
        onQueryChange = { searchInput = it; viewModel.onQueryChange(it) },
        onFilterSelected = viewModel::onFilterSelected,
        onOpenFile = onOpenFile,
        onCreate = onCreate,
        onOpen = onOpenRecent,
        onRemove = viewModel::remove,
        onClearAll = viewModel::clearAll,
        onPickFolder = pickFolder,
        onRemoveFolder = viewModel::removeFolder,
        onAddFolderFailureSeen = viewModel::onAddFolderFailureSeen,
        onOpenAbout = onOpenAbout,
        onVoice = onVoice,
        bookmarksContent = bookmarksContent,
        onOpenHit = { onOpenHit(it.item.ref, it.page) },
        searchInput = searchInput,
    )
}

/** The home screen's bottom tabs; Tools is an action, not a tab. */
private enum class HomeTab { Library, Recent, Bookmarks }

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit,
    onFilterSelected: (FileFilter) -> Unit,
    onOpenFile: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (DocumentRef) -> Unit,
    onRemove: (DocumentRef) -> Unit,
    onClearAll: () -> Unit,
    onPickFolder: (() -> Unit)?,
    onRemoveFolder: (String) -> Unit,
    onAddFolderFailureSeen: () -> Unit,
    onOpenAbout: (() -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
    bookmarksContent: (@Composable () -> Unit)? = null,
    onOpenHit: (ContentItem) -> Unit = { onOpen(it.item.ref) },
    searchInput: String = state.query,
) {
    var bookmarksShown by rememberSaveable { mutableStateOf(false) }
    val playback = com.ezfiles.reader.presentation.viewers.LocalPlaybackMemory.current
    val landscape = isWideLandscape()
    val delivery = rememberDocumentDeliveryActions()
    var shareSelection by remember { mutableStateOf<List<DocumentRef>>(emptyList()) }
    val tab = when {
        bookmarksShown -> HomeTab.Bookmarks
        state.filter == FileFilter.Recent -> HomeTab.Recent
        else -> HomeTab.Library
    }
    val navItems = listOf(
        EzNavItem("Library", EzIcons.Folder, tab == HomeTab.Library) {
            bookmarksShown = false
            if (state.filter == FileFilter.Recent) onFilterSelected(FileFilter.All)
        },
        EzNavItem("Recent", EzIcons.History, tab == HomeTab.Recent) {
            bookmarksShown = false
            onFilterSelected(FileFilter.Recent)
        },
        EzNavItem("Bookmarks", EzIcons.BookmarkOutline, tab == HomeTab.Bookmarks) { bookmarksShown = true },
        EzNavItem("Tools", EzIcons.Apps, false, onCreate),
    )
    // The list scrolls under the floating button and the tab bar, and clears them at the end.
    val bottomClearance = if (landscape) 24.dp else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 172.dp
    val startClearance = if (landscape) 112.dp else 0.dp
    var explainingAddFolder by rememberSaveable { mutableStateOf(false) }
    var removingFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    val askToClear = { confirmingClear = true }
    val noFolders = state.folders.isEmpty()
    // Typing shows two sections: matching names, then matching text.
    val searching = state.searchesContent && state.query.length >= 2
    val showsContent = searching && (state.contentHits.isNotEmpty() || state.indexing != null)
    Box(Modifier.fillMaxSize().libraryBackground().appSafeArea()
        .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)) else Modifier)
        .imePadding()) {
        if (tab == HomeTab.Bookmarks) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(start = startClearance, bottom = if (landscape) 0.dp else bottomClearance - 72.dp)) {
                Header(onOpenAbout.takeUnless { landscape }, onVoice = onVoice, compact = landscape)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (bookmarksContent != null) {
                        bookmarksContent()
                    } else {
                        EmptyMessage("No bookmarks yet", "Bookmarks you add in documents appear here.", EzIcons.BookmarkOutline)
                    }
                }
            }
        } else LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(start = startClearance),
            contentPadding = PaddingValues(bottom = bottomClearance),
            verticalArrangement = Arrangement.spacedBy(if (landscape) 4.dp else 8.dp),
        ) {
            if (!landscape) item(key = "header", contentType = "header") {
                Header(onOpenAbout, onVoice = onVoice, compact = landscape)
            }
            item(key = "search", contentType = "search") {
                SearchField(searchInput, onQueryChange, Modifier.padding(horizontal = EzSpacing.Gutter, vertical = 4.dp), compact = landscape,
                    onVoice = onVoice.takeIf { landscape })
            }
            if (shareSelection.isNotEmpty()) item(key = "share-selection", contentType = "actions") {
                Row(Modifier.fillMaxWidth().padding(horizontal = EzSpacing.Gutter), verticalAlignment = Alignment.CenterVertically) {
                    Text("${shareSelection.size} selected", modifier = Modifier.weight(1f), color = EzColors.TextSecondary)
                    EzTextButton("Share", {
                        delivery.share(shareSelection)
                        shareSelection = emptyList()
                    }, enabled = !delivery.busy)
                    EzTextButton("Clear", { shareSelection = emptyList() }, enabled = !delivery.busy)
                }
            }
            if (tab == HomeTab.Library) {
                item(key = "chips", contentType = "chips") {
                    FilterRow(state.filter, onFilterSelected, compact = landscape)
                }
            }
            val noFoldersCard = noFolders && state.showsFolderControls && onPickFolder != null
            if (noFoldersCard && state.items.isEmpty()) {
                item(key = "no-folders", contentType = "card") { NoFoldersCard(onPickFolder) }
            }
            if (!noFolders && state.showsFolderControls && onPickFolder != null) {
                if (!landscape) item(key = "folders-title", contentType = "section") { SectionTitle("Your folders", null) }
                item(key = "folders", contentType = "strip") {
                    FolderRow(state.folders, onAdd = { explainingAddFolder = true }, onRemove = { removingFolder = it })
                }
            }
            if (!noFoldersCard || state.items.isNotEmpty()) {
                item(key = "list-title", contentType = "section") {
                    val title = when {
                        searching -> "File names"
                        state.filter == FileFilter.Recent -> "Recent files"
                        state.showsHistory -> "Files you opened"
                        state.filter == FileFilter.All -> "In your folders"
                        else -> state.filter.label
                    }
                    SectionTitle(
                        title,
                        if (state.items.isEmpty()) null else state.items.size.toString(),
                        state.scanning,
                        onClear = askToClear.takeIf { state.filter == FileFilter.Recent && state.items.isNotEmpty() },
                        compact = landscape,
                    )
                }
            }
            if (state.limitReached && !state.showsHistory) {
                item(key = "limit", contentType = "note") {
                    Text(
                        "Your folders are very large, so some files may not be listed. Open file still reaches them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EzColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = SectionInset),
                    )
                }
            }
            when {
                state.items.isEmpty() && searching && showsContent -> item(key = "no-names", contentType = "note") {
                    SearchNote("No file names match.")
                }
                state.items.isEmpty() && searching && !state.scanning -> item(key = "no-matches", contentType = "empty") {
                    EmptyMessage("No matches", "Try other words, or check the spelling.", EzIcons.Search)
                }
                state.items.isEmpty() && (state.scanning || noFoldersCard) -> {}
                state.items.isEmpty() && !state.showsHistory && state.noFolderFiles -> item(key = "no-files", contentType = "empty") {
                    EmptyMessage(
                        "No files found",
                        "These folders and their subfolders have no supported files. Add another folder, or open a file from anywhere with the button below.",
                    )
                }
                state.items.isEmpty() && state.libraryEmpty -> item(key = "empty", contentType = "empty") {
                    EmptyMessage(
                        "No files yet",
                        "Open a PDF, Office document, slide deck, spreadsheet or e-book. Everything stays on this device.",
                    )
                }
                state.items.isEmpty() -> item(key = "none", contentType = "empty") {
                    EmptyMessage("No matching files", "Try another search or category.")
                }
                else -> items(state.items, key = { it.ref.identity + "|" + it.ref.uri }, contentType = { "file" }) { item ->
                    var menuOpen by remember { mutableStateOf(false) }
                    FileCard(
                        type = item.type,
                        name = item.ref.displayName,
                        meta = run {
                            // A part-watched video says how much is left ("1h 12m left").
                            val left = playback.remainingMs(item.ref)?.let { "${durationWords(it)} left" }
                            val base = listOfNotNull(left, item.meta.takeIf(String::isNotEmpty)).joinToString("  •  ")
                            if (shareSelection.any { it.uri == item.ref.uri }) "Selected · $base" else base
                        },
                        onClick = { onOpen(item.ref) },
                        onMore = { menuOpen = true },
                        modifier = Modifier.padding(horizontal = EzSpacing.Gutter),
                        leading = { MediaPreviewTile(item.ref, item.type) },
                    ) {
                        EzDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            EzMenuItem("Open", icon = EzIcons.Document, onClick = {
                                menuOpen = false
                                onOpen(item.ref)
                            })
                            EzMenuItem("Share original", icon = TidyIcons.Share, enabled = !delivery.busy, onClick = {
                                menuOpen = false
                                delivery.share(listOf(item.ref))
                            })
                            EzMenuItem("Print", icon = EzIcons.PdfFile, enabled = !delivery.busy && delivery.canPrint(item.ref), onClick = {
                                menuOpen = false
                                delivery.print(item.ref)
                            })
                            val selected = shareSelection.any { it.uri == item.ref.uri }
                            EzMenuItem(if (selected) "Unselect" else "Select for sharing", icon = TidyIcons.Share,
                                enabled = !delivery.busy && (selected || shareSelection.size < 32), onClick = {
                                    menuOpen = false
                                    shareSelection = if (selected) shareSelection.filterNot { it.uri == item.ref.uri } else shareSelection + item.ref
                                })
                            if (state.showsHistory) {
                                // Only forgets it here: the file itself stays, so no confirmation.
                                EzMenuItem("Remove from recents", icon = EzIcons.Close, onClick = {
                                    menuOpen = false
                                    onRemove(item.ref)
                                })
                            }
                        }
                    }
                }
            }
            if (showsContent) {
                item(key = "content-title", contentType = "section") {
                    SectionTitle(
                        "Inside files",
                        state.contentHits.size.takeIf { it > 0 }?.toString(),
                        busy = state.indexing != null,
                        compact = landscape,
                    )
                }
                state.indexing?.let { progress ->
                    item(key = "indexing", contentType = "note") { SearchNote(indexingLabel(progress)) }
                }
                items(state.contentHits, key = { "text|" + it.item.ref.identity + "|" + it.item.ref.uri }, contentType = { "hit" }) { hit ->
                    SnippetCard(hit, onClick = { onOpenHit(hit) }, modifier = Modifier.padding(horizontal = EzSpacing.Gutter))
                }
            }
            if (noFoldersCard && state.items.isNotEmpty()) {
                item(key = "no-folders", contentType = "card") { NoFoldersCard(onPickFolder) }
            }
        }
        if (landscape) {
            LandscapeLibraryRail(navItems, onOpenFile, onOpenAbout, Modifier.align(Alignment.CenterStart))
        } else Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = EzSpacing.Gutter, end = EzSpacing.Gutter, bottom = 10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (tab != HomeTab.Bookmarks) EzFloatingAction("Open file", EzIcons.Folder, onOpenFile)
            EzNavigationBar(navItems)
        }
    }
    if (explainingAddFolder && onPickFolder != null) {
        EzDialog(
            onDismissRequest = { explainingAddFolder = false },
            title = { Text("Add a folder") },
            text = { Text(ADD_FOLDER_EXPLANATION) },
            confirmButton = {
                EzTextButton("Choose folder", {
                    explainingAddFolder = false
                    onPickFolder()
                })
            },
            dismissButton = {
                EzTextButton("Not now", { explainingAddFolder = false }, color = EzColors.TextSecondary)
            },
        )
    }
    state.folders.firstOrNull { it.uri == removingFolder }?.let { folder ->
        EzDialog(
            onDismissRequest = { removingFolder = null },
            title = { Text("Remove ${folder.name}?") },
            text = { Text("EzFiles will stop listing this folder and give up access to it. Your files stay where they are.") },
            confirmButton = {
                EzTextButton("Remove", {
                    removingFolder = null
                    onRemoveFolder(folder.uri)
                })
            },
            dismissButton = {
                EzTextButton("Keep", { removingFolder = null }, color = EzColors.TextSecondary)
            },
        )
    }
    if (confirmingClear) {
        EzDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("Clear recent files?") },
            text = { Text("EzFiles forgets which files you opened. The files themselves stay where they are.") },
            confirmButton = {
                EzTextButton("Clear", {
                    confirmingClear = false
                    onClearAll()
                })
            },
            dismissButton = { EzTextButton("Cancel", { confirmingClear = false }, color = EzColors.TextSecondary) },
        )
    }
    if (state.addFolderFailed) {
        EzDialog(
            onDismissRequest = onAddFolderFailureSeen,
            title = { Text("Couldn't add that folder") },
            text = { Text("This device or the app that provides the folder doesn't allow lasting access. You can still use Open file.") },
            confirmButton = {
                EzTextButton("OK", onAddFolderFailureSeen)
            },
        )
    }
}

/** Headings sit a little inside the edge of the cards, in line with their content. */
private val SectionInset = EzSpacing.Gutter + 4.dp

/** A quiet line under a section title. */
@Composable
private fun SearchNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = EzColors.TextSecondary,
        modifier = Modifier.padding(horizontal = SectionInset, vertical = 2.dp),
    )
}

private const val ADD_FOLDER_EXPLANATION =
    "Choose a folder, such as Documents, and EzFiles lists the files in it and its subfolders. Files stay on this device.\n\n" +
        "On Android, folder access can also allow Tidy up to delete an identical extra copy after you confirm. Read-only folders still work for reading.\n\n" +
        "Since Android 11, Android doesn't let apps choose the main storage folder, the Download folder or Android/data. " +
        "For files in Downloads, use Open file, which opens them one at a time."

@Composable
private fun NoFoldersCard(onAddFolder: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = EzSpacing.Gutter, vertical = 6.dp).glass(EzShapes.Card, 0.6f).padding(EzSpacing.Panel),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Bring in your documents", style = MaterialTheme.typography.titleMedium, color = EzColors.Text)
        Text(
            "Add a folder, such as Documents, and EzFiles lists every file in it. Files stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = EzColors.TextSecondary,
        )
        Text(
            "Android doesn't allow adding Download as a folder. Use Open file for those files.",
            style = MaterialTheme.typography.bodySmall,
            color = EzColors.TextSecondary,
        )
        PrimaryActionButton("Add folder", EzIcons.Plus, onAddFolder, Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun FolderRow(folders: List<LibraryFolder>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = EzSpacing.Gutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "add") {
            EzChip(label = "Add folder", selected = false, onClick = onAdd, icon = EzIcons.Plus, iconTint = EzColors.Primary)
        }
        items(folders, key = { it.uri }) { folder ->
            Row(
                Modifier.widthIn(max = 240.dp).glass(EzShapes.Pill, 0.5f).padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(EzIcons.Folder, null, tint = EzColors.Primary, modifier = Modifier.size(18.dp))
                Text(
                    if (folder.available) folder.name else "${folder.name} (not available)",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (folder.available) EzColors.Text else EzColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                BarIconButton(EzIcons.Close, "Remove ${folder.name}", { onRemove(folder.uri) }, tint = EzColors.TextSecondary)
            }
        }
    }
}

/** The type filters in one row that scrolls sideways. Recent is a tab of its own. */
@Composable
private fun FilterRow(selected: FileFilter, onSelect: (FileFilter) -> Unit, compact: Boolean = false) {
    val filters = remember { FileFilter.entries.filter { it != FileFilter.Recent } }
    LazyRow(
        Modifier.fillMaxWidth(),
        // Room above and below for the chips' shadows, which the row would otherwise clip.
        contentPadding = PaddingValues(horizontal = EzSpacing.Gutter, vertical = if (compact) 2.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters, key = { it.name }) { filter ->
            EzChip(
                label = filter.label,
                selected = selected == filter,
                onClick = { onSelect(filter) },
                icon = filter.icon(),
                iconTint = filter.iconTint(),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, count: String?, busy: Boolean = false, onClear: (() -> Unit)? = null, compact: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = EzSpacing.Gutter, end = EzSpacing.Gutter, top = if (compact) 4.dp else 12.dp, bottom = 2.dp)
            .then(if (compact) Modifier.heightIn(min = 28.dp) else Modifier
                .glassCard(EzShapes.Pill, 0.55f)
                .padding(start = 18.dp, end = 6.dp)
                .heightIn(min = 48.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = EzColors.Text, modifier = Modifier.semantics { heading() })
        if (count != null) Text(count, style = MaterialTheme.typography.labelLarge, color = EzColors.TextSecondary)
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = EzColors.Primary)
        if (onClear != null) {
            Box(Modifier.weight(1f))
            EzTextButton("Clear", onClear)
        }
    }
}

/** Menu, the app's name, and About (when there is one). */
@Composable
private fun Header(onOpenAbout: (() -> Unit)?, onVoice: (() -> Unit)? = null, compact: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(start = EzSpacing.Gutter, end = EzSpacing.Gutter, top = if (compact) 4.dp else 12.dp, bottom = if (compact) 2.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "EzFiles",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            color = EzColors.Text,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onVoice != null) GlassIconButton(EzIcons.Mic, "Voice assistant", onVoice)
        if (onOpenAbout != null) GlassIconButton(EzIcons.Info, "About EzFiles", onOpenAbout)
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** Landscape has no header row, so Fily's mic sits at the end of the search row. */
    onVoice: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (compact) Text(
            "EzFiles",
            style = MaterialTheme.typography.titleLarge,
            color = EzColors.Text,
            modifier = Modifier.semantics { heading() },
        )
        Row(
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).glassCard(EzShapes.Pill, 0.62f).padding(horizontal = 18.dp, vertical = if (compact) 8.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(EzIcons.Search, null, tint = EzColors.Primary, modifier = Modifier.size(EzSpacing.BarIcon))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Search files, docs, sheets, and more", color = EzColors.TextSecondary, fontSize = 16.sp, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = EzColors.Text, fontSize = 16.sp),
                    cursorBrush = SolidColor(EzColors.Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                BarIconButton(EzIcons.Close, "Clear search", { onQueryChange("") }, tint = EzColors.TextSecondary)
            }
        }
        if (compact && onVoice != null) GlassIconButton(EzIcons.Mic, "Voice assistant", onVoice)
    }
}

@Composable
private fun EmptyMessage(title: String, body: String, icon: ImageVector = EzIcons.Folder) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = EzSpacing.Gutter, vertical = 12.dp).glass(EzShapes.Card, 0.5f).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, tint = EzColors.Primary, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = EzColors.Text, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary, textAlign = TextAlign.Center)
    }
}

private fun FileFilter.icon() = when (this) {
    FileFilter.All, FileFilter.Recent -> null
    FileFilter.Pdf -> EzIcons.Document
    FileFilter.Docs -> EzIcons.Lines
    FileFilter.Slides -> EzIcons.Play
    FileFilter.Sheets -> EzIcons.Table
    FileFilter.Books -> EzIcons.Book
    FileFilter.Images -> EzIcons.Image
    FileFilter.Media -> EzIcons.Movie
    FileFilter.Text -> EzIcons.TextT
    FileFilter.Code -> EzIcons.Code
    FileFilter.Data -> EzIcons.Database
    FileFilter.Archives -> EzIcons.Archive
}

@Composable
private fun FileFilter.iconTint() = when (this) {
    FileFilter.Pdf -> FileType.Pdf.bottom
    FileFilter.Docs -> FileType.Docx.bottom
    FileFilter.Slides -> FileType.Pptx.bottom
    FileFilter.Sheets -> FileType.Xlsx.bottom
    FileFilter.Images -> FileType.Image.bottom
    FileFilter.Media -> FileType.Video.bottom
    FileFilter.Books -> FileType.Epub.bottom
    FileFilter.Code -> FileType.Code.top
    FileFilter.Archives -> FileType.Zip.top
    else -> EzColors.Text
}

/** "report.final.pdf" -> "report.final"; a name with no extension, or only one, stays as it is. */
internal fun String.withoutExtension(): String {
    val dot = lastIndexOf('.')
    return if (dot <= 0 || length - dot > 6) this else substring(0, dot)
}

/** "1h 12m", "25m", "40s": how a person says time left. */
internal fun durationWords(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes >= 1 -> "${minutes}m"
        else -> "${(ms / 1000).coerceAtLeast(1)}s"
    }
}
