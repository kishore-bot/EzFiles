package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.platform.PdfPages
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.glassBar
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import kotlin.math.roundToInt

/** The slider's range for a document of [pageCount] pages (0-based pages; never empty). */
internal fun pageSliderRange(pageCount: Int): ClosedFloatingPointRange<Float> = 0f..(pageCount - 1).coerceAtLeast(1).toFloat()

/** The 0-based page a slider [value] stands for, kept inside the document. */
internal fun pageForSlider(value: Float, pageCount: Int): Int =
    if (value.isNaN()) 0 else value.roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))

/**
 * The pages in the thumbnail strip: up to [size] pages around [current],
 * which comes second where it can, so the next pages are in reach. Empty for
 * an empty document.
 */
internal fun thumbnailWindow(current: Int, pageCount: Int, size: Int): IntRange {
    val shown = minOf(size, pageCount)
    if (shown <= 0) return IntRange.EMPTY
    val start = (current - 1).coerceIn(0, pageCount - shown)
    return start until start + shown
}

/** How many thumbnails fit next to the slider in [widthDp]. */
internal fun thumbnailsFor(widthDp: Float): Int = when {
    widthDp >= 380f -> 4
    widthDp >= 330f -> 3
    else -> 0
}

/**
 * The glass row above the PDF toolbar: "n / N", a page slider with a red
 * thumb, and thumbnails of the nearby pages with the current one outlined.
 * Only the few visible thumbnails are rendered, at thumbnail size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfPageScrubber(pages: PdfPages, current: Int, onSeek: (Int) -> Unit, modifier: Modifier = Modifier) {
    val count = pages.pageCount
    val haptics = rememberEzHaptics()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging?.let { pageForSlider(it, count) } ?: current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val thumbs = thumbnailsFor(maxWidth.value)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).glassBar(EzShapes.Pill).padding(start = 6.dp, end = 10.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${shown + 1} / $count",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EzColors.Text,
                    maxLines = 1,
                    modifier = Modifier.glass(EzShapes.Pill, 0.85f).padding(horizontal = 10.dp, vertical = 6.dp),
                )
                val colors = SliderDefaults.colors(
                    thumbColor = EzColors.Primary,
                    activeTrackColor = EzColors.Primary,
                    inactiveTrackColor = EzColors.SliderTrack,
                )
                Slider(
                    value = dragging ?: current.toFloat(),
                    onValueChange = { value ->
                        if (pageForSlider(value, count) != pageForSlider(dragging ?: current.toFloat(), count)) haptics.step()
                        dragging = value
                    },
                    onValueChangeFinished = {
                        dragging?.let { onSeek(pageForSlider(it, count)) }
                        dragging = null
                    },
                    valueRange = pageSliderRange(count),
                    colors = colors,
                    // A round red thumb on a thin track, as in the design, not Material's tall bar.
                    thumb = {
                        Box(
                            Modifier.size(18.dp)
                                .background(EzColors.Primary, CircleShape)
                                .border(2.dp, EzColors.OnPrimary, CircleShape),
                        )
                    },
                    track = { state ->
                        SliderDefaults.Track(
                            sliderState = state,
                            modifier = Modifier.height(4.dp),
                            colors = colors,
                            drawStopIndicator = null,
                            thumbTrackGapSize = 0.dp,
                        )
                    },
                    modifier = Modifier.weight(1f).padding(start = 8.dp).height(32.dp).semantics { contentDescription = "Page slider" },
                )
            }
            if (thumbs > 0) ThumbnailStrip(pages, thumbnailWindow(shown, count, thumbs), shown, onSeek)
        }
    }
}

@Composable
internal fun LandscapePageScrubber(pages: PdfPages, current: Int, onSeek: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PdfPageScrubber(pages, current, onSeek)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ThumbnailStrip(pages, thumbnailWindow(current, pages.pageCount, 3), current, onSeek, comfortableTargets = true)
        }
    }
}

@Composable
private fun ThumbnailStrip(pages: PdfPages, window: IntRange, current: Int, onSelect: (Int) -> Unit, comfortableTargets: Boolean = false) {
    val haptics = rememberEzHaptics()
    val width = if (comfortableTargets) 48.dp else ThumbWidth
    val height = if (comfortableTargets) 64.dp else ThumbHeight
    val widthPx = with(LocalDensity.current) { width.roundToPx() * 2 }
    Row(
        Modifier.glassBar(ThumbStripShape).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Keyed by page, so moving the window keeps the thumbnails already rendered.
        for (index in window) key(index) {
            val active = index == current
            Box(
                Modifier
                    .size(width = width, height = height)
                    .clip(ThumbShape)
                    .background(EzPaper.Background)
                    .border(if (active) 2.dp else 1.dp, if (active) EzColors.Primary else EzColors.PageOutline, ThumbShape)
                    .clickable(role = Role.Button, onClickLabel = "Go to page ${index + 1}") { if (!active) haptics.pageTurn(); onSelect(index) },
            ) {
                val bitmap = rememberThumbnail(pages, index, widthPx)
                bitmap?.let { Image(it, contentDescription = "Page ${index + 1}", modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

private val ThumbWidth = 30.dp
private val ThumbHeight = 42.dp
private val ThumbShape = RoundedCornerShape(6.dp)
private val ThumbStripShape = RoundedCornerShape(18.dp)
