package com.ezfiles.reader.presentation.create

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfCreator
import com.ezfiles.reader.domain.PdfImageQuality
import com.ezfiles.reader.domain.PdfPageImage
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.platform.LocalDocumentFiles
import com.ezfiles.reader.platform.decodeImage
import com.ezfiles.reader.platform.rememberDocumentSaver
import com.ezfiles.reader.platform.rememberImagePicker
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.components.EzChip
import com.ezfiles.reader.presentation.components.PrimaryActionButton
import com.ezfiles.reader.presentation.components.SecondaryActionButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private const val THUMBNAIL_SIDE_PX = 360

/** Thumbnails kept while they scroll in and out of view; at about 0.5 MB each. */
private const val CACHED_THUMBNAILS = 48

private val MARGIN_CHOICES = listOf("None" to 0f, "Small" to 18f, "Medium" to 36f, "Large" to 54f)

private val PageListSaver = listSaver<ImagePageList, String>({ it.toSaved() }, { ImagePageList.fromSaved(it) })

@Composable
fun ImagesToPdfScreen(onClose: () -> Unit, onOpen: (DocumentRef) -> Unit) {
    val creator = LocalPdfCreator.current
    val files = LocalDocumentFiles.current
    val scope = rememberCoroutineScope()
    val runner = remember(scope) { ExportRunner(scope) }
    val thumbnails = remember(creator) { ThumbnailLoader(creator) }
    var list by rememberSaveable(stateSaver = PageListSaver) { mutableStateOf(ImagePageList()) }
    var pageSize by rememberSaveable { mutableStateOf(PdfPageSize.A4) }
    var marginPt by rememberSaveable { mutableStateOf(18f) }
    var quality by rememberSaveable { mutableStateOf(PdfImageQuality.Balanced) }

    val picker = rememberImagePicker(multiple = true) { uris ->
        if (uris.isNotEmpty()) {
            list = list.added(uris)
            runner.clear()
        }
    }
    val saver = rememberDocumentSaver { target ->
        if (target != null) {
            val pages = list.pages.map { PdfPageImage(it.uri, it.rotationDegrees) }
            runner.run(target) { onProgress ->
                creator.imagesToPdf(pages, pageSize, marginPt, target, quality = quality, onProgress = onProgress)
                "Saved ${pages.size} ${if (pages.size == 1) "page" else "pages"}."
            }
        }
    }

    CreateToolFrame("Images to PDF", onClose) {
        Column(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(164.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ImageSettings(pageSize, { pageSize = it }, marginPt, { marginPt = it }, quality, { quality = it })
                }
                if (list.pages.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "Add pictures to start. Each one becomes a page.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = EzColors.TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        )
                    }
                }
                items(list.pages, key = { it.id }) { page ->
                    val position = list.pages.indexOf(page)
                    PageTile(
                        page = page,
                        number = position + 1,
                        count = list.pages.size,
                        thumbnails = thumbnails,
                        onMove = { by -> list = list.moved(page.id, by) },
                        onRotate = { list = list.rotated(page.id) },
                        onRemove = { list = list.removed(page.id) },
                    )
                }
            }
            ExportBar(
                status = runner.status,
                onCancel = runner::cancel,
                onOpen = { uri ->
                    scope.launch {
                        try {
                            onOpen(files.refOf(uri))
                        } catch (e: DocumentException) {
                            runner.report(e.error.detail)
                        }
                    }
                },
            ) {
                // Adding is the main action until there's something to save.
                if (list.pages.isEmpty()) {
                    PrimaryActionButton("Add pictures", EzIcons.Image, { picker.launch() }, Modifier.fillMaxWidth())
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecondaryActionButton("Add", EzIcons.Plus, { picker.launch() })
                        PrimaryActionButton("Save PDF", EzIcons.Document, { saver.launch("Images.pdf", "application/pdf") }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageSettings(
    pageSize: PdfPageSize,
    onPageSize: (PdfPageSize) -> Unit,
    marginPt: Float,
    onMargin: (Float) -> Unit,
    quality: PdfImageQuality,
    onQuality: (PdfImageQuality) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        SettingLabel("Page size")
        ChipRow {
            PdfPageSize.entries.forEach { EzChip(it.label, it == pageSize, { onPageSize(it) }) }
        }
        SettingLabel("Margin")
        ChipRow {
            MARGIN_CHOICES.forEach { (label, value) -> EzChip(label, value == marginPt, { onMargin(value) }) }
        }
        SettingLabel("Compression")
        ChipRow {
            PdfImageQuality.entries.forEach { EzChip(it.label, it == quality, { onQuality(it) }) }
        }
    }
}

@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        content()
    }
}

/** Moving is by buttons: they work with a screen reader and don't fight the grid's scrolling. */
@Composable
private fun PageTile(
    page: ImagePage,
    number: Int,
    count: Int,
    thumbnails: ThumbnailLoader,
    onMove: (Int) -> Unit,
    onRotate: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(Modifier.glass(EzShapes.Medium, 0.45f).padding(8.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(EzPaper.Background),
            contentAlignment = Alignment.Center,
        ) {
            val thumbnail by produceState<ThumbnailState>(ThumbnailState.Loading, page.uri) { value = thumbnails.load(page.uri) }
            when (val state = thumbnail) {
                ThumbnailState.Loading -> Unit
                ThumbnailState.Failed -> Text(
                    "Can't read this picture",
                    style = MaterialTheme.typography.bodySmall,
                    color = EzColors.Accent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(8.dp),
                )
                is ThumbnailState.Ready -> Image(
                    state.bitmap,
                    "Page $number",
                    contentScale = ContentScale.Fit,
                    // Any quarter turn of a picture fitted in a square still fits in that square.
                    modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = page.rotationDegrees.toFloat() },
                )
            }
            Text(
                "$number",
                style = MaterialTheme.typography.labelLarge,
                color = EzColors.OnScrim,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(EzColors.Scrim, EzShapes.Pill)
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            BarIconButton(EzIcons.ChevronLeft, "Move page $number earlier", { onMove(-1) }, enabled = number > 1)
            BarIconButton(EzIcons.ChevronRight, "Move page $number later", { onMove(1) }, enabled = number < count)
            BarIconButton(EzIcons.Rotate, "Rotate page $number", onRotate)
            BarIconButton(EzIcons.Close, "Remove page $number", onRemove)
        }
    }
}

private sealed interface ThumbnailState {
    data object Loading : ThumbnailState

    data object Failed : ThumbnailState

    data class Ready(val bitmap: ImageBitmap) : ThumbnailState
}

/**
 * Decodes thumbnails a couple at a time and keeps the latest few, so a long
 * list costs neither a burst of full-size decodes nor a growing cache. Used
 * from the composition's coroutines, which all run on the main thread.
 */
private class ThumbnailLoader(private val creator: PdfCreator) {
    private val decoding = Semaphore(2)
    private val cache = LinkedHashMap<String, ImageBitmap>()

    suspend fun load(uri: String): ThumbnailState {
        cache.remove(uri)?.let { bitmap ->
            cache[uri] = bitmap
            return ThumbnailState.Ready(bitmap)
        }
        val bitmap = try {
            decoding.withPermit {
                val encoded = creator.imageThumbnail(uri, THUMBNAIL_SIDE_PX)
                withContext(Dispatchers.Default) { decodeImage(encoded, THUMBNAIL_SIDE_PX) }
            }
        } catch (_: DocumentException) {
            null
        } ?: return ThumbnailState.Failed
        cache[uri] = bitmap
        if (cache.size > CACHED_THUMBNAILS) cache.remove(cache.keys.first())
        return ThumbnailState.Ready(bitmap)
    }
}
