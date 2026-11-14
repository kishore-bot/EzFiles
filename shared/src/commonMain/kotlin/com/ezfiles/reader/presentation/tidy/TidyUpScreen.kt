package com.ezfiles.reader.presentation.tidy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import com.ezfiles.reader.presentation.theme.rememberHapticActivation
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.application.TidyService
import com.ezfiles.reader.domain.CollectionEntry
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.IdenticalGroup
import com.ezfiles.reader.domain.SimilarGroup
import com.ezfiles.reader.domain.TidyCollection
import com.ezfiles.reader.domain.TidyProgress
import com.ezfiles.reader.domain.TidyReport
import com.ezfiles.reader.platform.formatDate
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzActionPanel
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.components.EzDialog
import com.ezfiles.reader.presentation.components.EzDropdownMenu
import com.ezfiles.reader.presentation.components.EzMenuItem
import com.ezfiles.reader.presentation.components.EzNotice
import com.ezfiles.reader.presentation.components.EzProgressRow
import com.ezfiles.reader.presentation.components.EzSectionTitle
import com.ezfiles.reader.presentation.components.EzStatusCard
import com.ezfiles.reader.presentation.components.EzStatusText
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.components.FileTypeTile
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.create.CreateToolFrame
import com.ezfiles.reader.presentation.library.FileType
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzSpacing
import com.ezfiles.reader.presentation.theme.FileNameStyle
import com.ezfiles.reader.presentation.theme.glassCard
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import com.ezfiles.reader.presentation.viewers.formatBytes
import kotlinx.coroutines.launch

/** The Tidy up scanner; `null` where the platform has none. */
val LocalTidy = staticCompositionLocalOf<TidyService?> { null }

private enum class TidySection(val label: String) {
    All("All"),
    Identical("Identical"),
    Documents("Similar documents"),
    Photos("Similar photos"),
    Collections("Collections"),
}

/**
 * Tidy up: finds identical files, similar documents and photos, and sorts
 * receipts, tickets and statements into collections, across the folders the
 * user chose. Files open as usual; deleting asks first, every time.
 */
@Composable
fun TidyUpScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val service = LocalTidy.current
    if (service == null) {
        CreateToolFrame("Tidy up", onClose) {
            EzStatusCard { EzStatusText("Not available here", "Tidy up needs folders chosen in the library.", TidyIcons.Sparkles) }
        }
        return
    }
    LaunchedEffect(service) { service.load() }
    val report by service.report.collectAsStateWithLifecycle()
    val progress by service.progress.collectAsStateWithLifecycle()
    val failure by service.failure.collectAsStateWithLifecycle()
    val folders by service.folders.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val haptics = rememberEzHaptics()

    // One confirming tap when a scan this screen watched finishes; nothing per file.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(progress != null) { if (progress != null) armed = true }
    LaunchedEffect(report?.scannedAt) {
        if (armed && report != null) {
            haptics.confirm()
            armed = false
        }
    }

    var notice by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<DocumentRef?>(null) }
    val actions = FileActions(
        open = onOpen,
        share = { ref -> if (!service.share(ref)) notice = "No app on this phone can take this file." },
        delete = if (service.canDelete) ({ ref -> pendingDelete = ref }) else null,
    )

    CreateToolFrame(
        "Tidy up",
        onClose,
        actions = {
            if (report != null && progress == null) BarIconButton(TidyIcons.Refresh, "Scan again", service::scan)
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(progress != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                progress?.let { current ->
                    EzActionPanel { EzProgressRow(current.label(), current.fraction, onCancel = service::cancel) }
                }
            }
            failure?.let { EzNotice(it, onDismiss = null, warning = true, modifier = Modifier.padding(bottom = 8.dp)) }
            notice?.let { EzNotice(it, onDismiss = { notice = null }, modifier = Modifier.padding(bottom = 8.dp)) }
            val current = report
            when {
                current == null && progress == null -> Intro(hasFolders = folders.isNotEmpty(), onScan = service::scan)
                current == null -> EzStatusCard {
                    EzStatusText(
                        "Looking through your folders",
                        "The first scan reads each file once. Later scans only look at files that are new or changed.",
                        TidyIcons.Sparkles,
                    )
                }
                else -> Results(current, actions)
            }
        }
    }

    pendingDelete?.let { ref ->
        EzDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this duplicate?") },
            text = { Text("“${ref.displayName}” will be permanently deleted from its original folder. The first matching copy will be kept, and both files will be compared again before deletion. This can't be undone.") },
            dismissButton = { EzTextButton("Cancel", { pendingDelete = null }, color = EzColors.Text) },
            confirmButton = {
                EzTextButton("Delete", {
                    pendingDelete = null
                    scope.launch {
                        notice = if (service.delete(ref)) {
                            "Deleted ${ref.displayName}."
                        } else {
                            "Nothing was deleted. The copies may have changed, a scan may be running, or write access is missing. Select the folder again in Library to grant access, then scan again."
                        }
                    }
                })
            },
        )
    }
}

private class FileActions(
    val open: (DocumentRef) -> Unit,
    val share: (DocumentRef) -> Unit,
    /** `null` where files can't be deleted from here. */
    val delete: ((DocumentRef) -> Unit)?,
)

private fun TidyProgress.label(): String = if (total > 0) "${phase.label} · $done of $total" else "${phase.label}…"

@Composable
private fun Intro(hasFolders: Boolean, onScan: () -> Unit) {
    EzStatusCard {
        EzStatusText(
            "Tidy up your folders",
            if (hasFolders) {
                "Find identical files, similar documents and photos, and gather resumes, banking documents, receipts and tickets " +
                    "into collections. Everything happens on this phone; nothing is changed unless you choose to."
            } else {
                "Add folders in the library first. Tidy up looks through the folders you chose there."
            },
            TidyIcons.Sparkles,
        )
        if (hasFolders) PrimaryActionButton("Scan folders", TidyIcons.Sparkles, onScan)
    }
}

@Composable
private fun Results(report: TidyReport, actions: FileActions) {
    var section by rememberSaveable { mutableStateOf(TidySection.All) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val counts = mapOf(
        TidySection.Identical to report.identical.size,
        TidySection.Documents to report.similarDocuments.size,
        TidySection.Photos to report.similarPhotos.size,
        TidySection.Collections to report.collections.size,
    )
    fun shows(s: TidySection) = (section == TidySection.All || section == s) && (counts[s] ?: 0) > 0

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(EzSpacing.ItemGap),
    ) {
        item(key = "summary") { Summary(report) }
        if (!report.isEmpty) {
            item(key = "chips") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TidySection.entries.filter { it == TidySection.All || (counts[it] ?: 0) > 0 }, key = { it.name }) { s ->
                        EzChip(
                            label = if (s == TidySection.All) s.label else "${s.label} ${counts[s]}",
                            selected = section == s,
                            onClick = { section = s },
                        )
                    }
                }
            }
        }
        if (shows(TidySection.Identical)) {
            sectionTitle("identical", "Identical files")
            items(report.identical, key = { "i:" + it.files.first().identity }) { group -> IdenticalCard(group, actions) }
        }
        if (shows(TidySection.Documents)) {
            sectionTitle("documents", "Similar documents")
            items(report.similarDocuments, key = { "d:" + it.files.first().identity }) { group ->
                SimilarCard("${group.files.size} versions of one document", group, actions)
            }
        }
        if (shows(TidySection.Photos)) {
            sectionTitle("photos", "Similar photos")
            items(report.similarPhotos, key = { "p:" + it.files.first().identity }) { group ->
                SimilarCard("${group.files.size} photos that look alike", group, actions)
            }
        }
        if (shows(TidySection.Collections)) {
            sectionTitle("collections", "Collections")
            items(report.collections, key = { "c:" + it.kind.name }) { collection ->
                CollectionCard(
                    collection,
                    expanded = expanded[collection.kind.name] == true,
                    onToggle = { expanded[collection.kind.name] = expanded[collection.kind.name] != true },
                    actions = actions,
                )
            }
        }
    }
}

private fun LazyListScope.sectionTitle(key: String, title: String) {
    item(key = "t:$key") { EzSectionTitle(title, Modifier.padding(top = 8.dp)) }
}

@Composable
private fun Summary(report: TidyReport) {
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).glassCard(EzShapes.Card, 0.55f).padding(EzSpacing.Panel),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (report.isEmpty) {
            Text("All tidy", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
            Text("No copies, look-alikes or collections turned up.", style = MaterialTheme.typography.bodyMedium, color = EzColors.TextSecondary)
        } else if (report.reclaimableBytes > 0) {
            Text("${formatBytes(report.reclaimableBytes)} to free", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
            Text(
                "by keeping one of each identical file",
                style = MaterialTheme.typography.bodyMedium,
                color = EzColors.TextSecondary,
            )
        } else {
            Text("Here's what turned up", style = MaterialTheme.typography.titleLarge, color = EzColors.Text)
        }
        Text(
            "${report.fileCount} files looked at · ${formatDate(report.scannedAt)}",
            fontSize = 12.sp,
            color = EzColors.TextSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun IdenticalCard(group: IdenticalGroup, actions: FileActions) {
    GroupCard(
        title = "${group.files.size} identical copies",
        subtitle = "${formatBytes(group.sizeBytes)} each · ${formatBytes(group.reclaimableBytes)} to free",
    ) {
        group.files.forEachIndexed { index, ref ->
            FileRow(ref, if (index == 0) "Kept copy · ${meta(ref)}" else meta(ref), actions, allowDelete = index > 0)
        }
    }
}

@Composable
private fun SimilarCard(title: String, group: SimilarGroup, actions: FileActions) {
    GroupCard(title = title, subtitle = null) {
        group.files.forEach { FileRow(it, meta(it), actions) }
    }
}

@Composable
private fun CollectionCard(collection: TidyCollection, expanded: Boolean, onToggle: () -> Unit, actions: FileActions) {
    val shown: List<CollectionEntry> = if (expanded) collection.entries else collection.entries.take(COLLAPSED_ENTRIES)
    GroupCard(
        title = collection.kind.title,
        subtitle = if (collection.entries.size == 1) "1 file" else "${collection.entries.size} files",
    ) {
        shown.forEach { entry -> FileRow(entry.file, entry.reason.describe(), actions) }
        if (collection.entries.size > COLLAPSED_ENTRIES) {
            EzTextButton(
                if (expanded) "Show fewer" else "Show all ${collection.entries.size}",
                onToggle,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun GroupCard(title: String, subtitle: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard(EzShapes.Card, 0.5f).padding(vertical = 10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = EzColors.Text)
            if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = EzColors.TextSecondary)
        }
        content()
    }
}

/** A file in a group, like a library entry: tap to open, the menu to share or delete. */
@Composable
private fun FileRow(ref: DocumentRef, meta: String, actions: FileActions, allowDelete: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(role = Role.Button, onClickLabel = "Open", onClick = rememberHapticActivation { actions.open(ref) })
            .padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FileTypeTile(FileType.of(ref.displayName, null))
        Column(Modifier.weight(1f)) {
            Text(ref.displayName, style = FileNameStyle, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                meta,
                fontSize = 12.sp,
                color = EzColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Box {
            BarIconButton(EzIcons.More, "More options for ${ref.displayName}", { menu = true }, tint = EzColors.TextSecondary)
            EzDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                EzMenuItem("Open", { menu = false; actions.open(ref) }, icon = EzIcons.Document)
                EzMenuItem("Share", { menu = false; actions.share(ref) }, icon = TidyIcons.Share)
                actions.delete?.takeIf { allowDelete }?.let { delete ->
                    EzMenuItem("Delete…", { menu = false; delete(ref) }, icon = EzIcons.Delete, destructive = true)
                }
            }
        }
    }
}

private fun meta(ref: DocumentRef): String =
    listOfNotNull(ref.sizeBytes?.let(::formatBytes), ref.modifiedAt?.let(::formatDate)).joinToString(" · ")

private const val COLLAPSED_ENTRIES = 4
