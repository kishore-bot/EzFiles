package com.ezfiles.reader.presentation.pdftools

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfTools
import com.ezfiles.reader.platform.DocumentOutputs
import com.ezfiles.reader.platform.LocalPdfOpener
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.create.TextAction
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

private const val THUMBNAIL_SIDE_PX = 360

/** Thumbnails kept while they scroll in and out of view; at most 0.5 MB each. */
private const val CACHED_THUMBNAILS = 48

private class OrganizeViewModel(tools: PdfTools, outputs: DocumentOutputs) : PdfToolViewModel(tools, outputs) {
    private var boundTo by mutableStateOf<Long?>(null)
    var pages by mutableStateOf<OrganizeState?>(null)

    /** Starts over when the file changes, and only then. */
    fun bind(pdf: PickedPdf) {
        if (boundTo != pdf.id) {
            boundTo = pdf.id
            pages = OrganizeState(pdf.info.pageCount)
        }
    }

    fun pagesOf(pdf: PickedPdf): OrganizeState? = pages.takeIf { boundTo == pdf.id }

    override fun onLeave() {
        boundTo = null
        pages = null
        super.onLeave()
    }
}

@Composable
fun OrganizePdfScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val tool = rememberPdfTool("organize") { tools, outputs -> OrganizeViewModel(tools, outputs) }
    val flow = tool.flow
    val state by flow.state.collectAsStateWithLifecycle()
    val pdf = state.sources.items.firstOrNull()
    LaunchedEffect(pdf?.id) { pdf?.let(tool::bind) }
    val pages = pdf?.let(tool::pagesOf)

    PdfToolHost(
        title = PdfTool.Organize.title,
        flow = flow,
        onClose = onClose,
        onOpen = onOpen,
        pickHint = "Choose the PDF to arrange. The original stays as it is.",
        actions = {
            PdfSaveRow(
                hint = if (pages?.hasChanges == true) null else "Make a change to save.",
                saveLabel = "Save PDF",
                leading = { TextAction("Change PDF", pickPdf) },
                onSave = {
                    val name = pdf?.ref?.displayName ?: return@PdfSaveRow
                    val edits = pages?.edits ?: return@PdfSaveRow
                    flow.requestRun(
                        PdfToolJob(
                            outputs = listOf(
                                PdfToolOutput(suggestedPdfName(name, "edited")) { pdfs, target, onProgress ->
                                    flow.tools.rebuild(pdfs.first(), edits, target, onProgress)
                                },
                            ),
                            savedMessage = { "Saved a PDF with ${pageCountLabel(edits.size)}." },
                        ),
                    )
                },
            )
        },
    ) {
        if (pdf != null && pages != null) OrganizeGrid(pdf, pages) { tool.pages = it }
    }
}

@Composable
private fun OrganizeGrid(pdf: PickedPdf, state: OrganizeState, onChange: (OrganizeState) -> Unit) {
    val previews by rememberPdfPreview(pdf)
    LazyVerticalGrid(
        // Narrow enough for two columns on the smallest phones.
        columns = GridCells.Adaptive(148.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { SelectionBar(state, onChange) }
        if (previews is PdfPreview.Unavailable) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PdfToolNote("Page previews aren't available for this file on this device, but you can still arrange its pages by number.")
            }
        }
        itemsIndexed(state.pages, key = { _, page -> page.sourcePage }) { index, page ->
            PageTile(
                page = page,
                position = index + 1,
                state = state,
                thumbnails = (previews as? PdfPreview.Ready)?.thumbnails,
                onChange = onChange,
            )
        }
    }
}

@Composable
private fun SelectionBar(state: OrganizeState, onChange: (OrganizeState) -> Unit) {
    Column(Modifier.fillMaxWidth().glass(EzShapes.Medium, 0.45f).padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val selectedCount = state.selected.size
            Text(
                if (selectedCount > 0) "$selectedCount of ${state.pages.size} selected" else pageCountLabel(state.pages.size),
                style = MaterialTheme.typography.labelLarge,
                color = EzColors.Text,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            BarIconButton(PdfToolIcons.Undo, "Undo", { onChange(state.undone()) }, enabled = state.canUndo)
            if (state.allSelected) {
                TextAction("Clear") { onChange(state.selectionCleared()) }
            } else {
                TextAction("Select all") { onChange(state.selectedAll()) }
            }
        }
        if (state.selected.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BarIconButton(EzIcons.ChevronLeft, "Move selected pages earlier", { onChange(state.selectionMoved(later = false)) })
                BarIconButton(EzIcons.ChevronRight, "Move selected pages later", { onChange(state.selectionMoved(later = true)) })
                BarIconButton(EzIcons.Rotate, "Rotate selected pages", { onChange(state.selectionRotated()) })
                BarIconButton(PdfToolIcons.Delete, "Delete selected pages", { onChange(state.selectionDeleted()) }, enabled = state.canDeleteSelection)
            }
            if (!state.canDeleteSelection) {
                Text(
                    "A PDF needs at least one page, so one has to stay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EzColors.TextSecondary,
                    modifier = Modifier.padding(start = 6.dp, bottom = 4.dp),
                )
            }
        }
    }
}

/** Tapping the picture selects. Moving is by buttons: they work with a screen reader and don't fight the grid's scrolling. */
@Composable
private fun PageTile(page: OrganizePage, position: Int, state: OrganizeState, thumbnails: PdfThumbnails?, onChange: (OrganizeState) -> Unit) {
    val selected = page.sourcePage in state.selected
    val sourceNumber = page.sourcePage + 1
    Column(
        Modifier
            .border(if (selected) 2.dp else 0.dp, if (selected) EzColors.Primary else Color.Transparent, EzShapes.Medium)
            .glass(EzShapes.Medium, 0.45f)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(EzPaper.Background)
                .toggleable(value = selected, role = Role.Checkbox) { onChange(state.toggled(page.sourcePage)) },
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnails != null) {
                val bitmap by produceState<ImageBitmap?>(null, thumbnails, page.sourcePage) { value = thumbnails.load(page.sourcePage) }
                bitmap?.let {
                    Image(
                        it,
                        "Page $position, from page $sourceNumber of the original",
                        contentScale = ContentScale.Fit,
                        // Any quarter turn of a picture fitted in a square still fits in that square.
                        modifier = Modifier.fillMaxSize().padding(4.dp).graphicsLayer { rotationZ = page.rotationDegrees.toFloat() },
                    )
                }
            }
            if (thumbnails == null) {
                Text(
                    "Page $sourceNumber",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EzColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                "$position",
                style = MaterialTheme.typography.labelLarge,
                color = EzColors.OnScrim,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(EzColors.Scrim, EzShapes.Pill)
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            )
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).background(EzColors.Primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(PdfToolIcons.Check, null, tint = EzColors.OnPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
        // Equal shares: four 40 dp buttons don't fit a tile on a small phone.
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val slot = Modifier.weight(1f)
            Box(slot, contentAlignment = Alignment.Center) {
                BarIconButton(EzIcons.ChevronLeft, "Move page $position earlier", { onChange(state.moved(page.sourcePage, -1)) }, enabled = position > 1)
            }
            Box(slot, contentAlignment = Alignment.Center) {
                BarIconButton(EzIcons.ChevronRight, "Move page $position later", { onChange(state.moved(page.sourcePage, 1)) }, enabled = position < state.pages.size)
            }
            Box(slot, contentAlignment = Alignment.Center) {
                BarIconButton(EzIcons.Rotate, "Rotate page $position", { onChange(state.rotated(page.sourcePage)) })
            }
            Box(slot, contentAlignment = Alignment.Center) {
                BarIconButton(PdfToolIcons.Delete, "Delete page $position", { onChange(state.deleted(page.sourcePage)) }, enabled = state.canDelete)
            }
        }
    }
}

internal sealed interface PdfPreview {
    data object Loading : PdfPreview

    /** The renderer can't open it: on older Android, any protected PDF. The tool still works without. */
    data object Unavailable : PdfPreview

    data class Ready(val thumbnails: PdfThumbnails) : PdfPreview
}

/** Opens [pdf] in the platform renderer for as long as this is composed. */
@Composable
internal fun rememberPdfPreview(pdf: PickedPdf) = LocalPdfOpener.current.let { opener ->
    produceState<PdfPreview>(PdfPreview.Loading, pdf.id, pdf.password) {
        var pages: PdfPages? = null
        try {
            withContext(Dispatchers.IO) { pages = opener.open(pdf.ref, pdf.password) }
            value = PdfPreview.Ready(PdfThumbnails(checkNotNull(pages)))
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            value = PdfPreview.Unavailable
        } finally {
            withContext(NonCancellable) { pages?.close() }
        }
    }
}

/**
 * Renders small pages one at a time, as the grid asks, and keeps the latest few.
 * Used from the composition's coroutines, which all run on the main thread.
 */
internal class PdfThumbnails(val pages: PdfPages) {
    private val cache = LinkedHashMap<Int, ImageBitmap>()

    /** `null` if the page can't be drawn. */
    suspend fun load(index: Int): ImageBitmap? {
        cache.remove(index)?.let { bitmap ->
            cache[index] = bitmap
            return bitmap
        }
        val bitmap = try {
            pages.render(index, thumbnailWidthPx(pages.aspectRatio(index), THUMBNAIL_SIDE_PX))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        cache[index] = bitmap
        if (cache.size > CACHED_THUMBNAILS) cache.remove(cache.keys.first())
        return bitmap
    }
}
