package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import com.ezfiles.reader.presentation.theme.rememberEzHaptics

import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.abs
import androidx.compose.foundation.layout.aspectRatio
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.application.assistant.ReaderPosition
import com.ezfiles.reader.domain.SlideShape
import com.ezfiles.reader.platform.PresentationMode
import com.ezfiles.reader.presentation.ReaderUiState
import com.ezfiles.reader.presentation.components.BarIconButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.PrimaryGradient
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.glassBar
import com.ezfiles.reader.presentation.theme.raised
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

private val SLIDE_VOICE_OPERATIONS = setOf("next_page", "previous_page", "go_to_page", "scroll_top", "scroll_bottom")

/**
 * One slide. PPTX slides carry a [canvas] and positioned [shapes]; formats
 * without layout (ODP) fall back to their text [content].
 */
private data class Slide(
    val number: Int,
    val canvas: ContentBlock.Slide?,
    val shapes: List<SlideShape>,
    val content: List<ContentBlock>,
    val notes: List<ContentBlock.Quote>,
) {
    /** The last click step of this slide's entrance builds. */
    val lastStep = shapes.maxOfOrNull { it.build?.step ?: 0 } ?: 0
}

/** [fresh], with each slide equal to the one at its place in [old] replaced by that one. */
private fun reuseUnchanged(old: List<Slide>, fresh: List<Slide>): List<Slide> =
    fresh.mapIndexed { i, slide -> old.getOrNull(i)?.takeIf { it == slide } ?: slide }

/** Splits the block stream at each `Section` ("Slide N"); quotes are speaker notes. */
private fun slidesOf(pages: List<List<ContentBlock>>): List<Slide> {
    val slides = mutableListOf<Slide>()
    var canvas: ContentBlock.Slide? = null
    var shapes = mutableListOf<SlideShape>()
    var content = mutableListOf<ContentBlock>()
    var notes = mutableListOf<ContentBlock.Quote>()
    var started = false
    fun finish() {
        if (started) slides += Slide(slides.size + 1, canvas, shapes, content, notes)
        canvas = null
        shapes = mutableListOf()
        content = mutableListOf()
        notes = mutableListOf()
    }
    for (page in pages) {
        for (block in page) {
            when (block) {
                is ContentBlock.Section -> {
                    finish()
                    started = true
                }
                is ContentBlock.Slide -> canvas = block
                is ContentBlock.Shape -> shapes += block.shape
                is ContentBlock.Quote -> notes += block
                else -> content += block
            }
        }
    }
    finish()
    return slides
}

private sealed interface SlidePart {
    data class Single(val block: ContentBlock) : SlidePart

    data class Table(val rows: List<ContentBlock.TableRow>) : SlidePart
}

private fun slideParts(content: List<ContentBlock>): List<SlidePart> {
    val parts = mutableListOf<SlidePart>()
    for (block in content) {
        val last = parts.lastOrNull()
        when {
            block is ContentBlock.TableRow && last is SlidePart.Table -> parts[parts.lastIndex] = SlidePart.Table(last.rows + block)
            block is ContentBlock.TableRow -> parts += SlidePart.Table(listOf(block))
            else -> parts += SlidePart.Single(block)
        }
    }
    return parts
}

/**
 * Presentation viewer: a swipeable deck of slides drawn as designed, a
 * thumbnail strip, speaker notes, and a full-screen presenter that plays
 * transitions and click builds like PowerPoint.
 */
@Composable
fun SlideshowViewer(state: ReaderUiState.Ready, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    // Slides unchanged by a new batch keep their objects, so pages and thumbnails
    // already shown aren't rebuilt each time more of the deck arrives.
    val previous = remember { arrayOf<List<Slide>>(emptyList()) }
    val slides = remember(state.pages) { reuseUnchanged(previous[0], slidesOf(state.pages)).also { previous[0] = it } }
    // Pager count can change before its cached item provider is replaced. Both count and
    // item content read this snapshot; cached keys must not index an older batch of slides.
    val currentSlides by rememberUpdatedState(slides)
    var presenting by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState { currentSlides.size }
    val haptics = rememberEzHaptics()
    LaunchedEffect(pager, haptics) {
        snapshotFlow { pager.settledPage }.drop(1).collect { haptics.pageTurn() }
    }
    // The deck's own count, known before its later slides load; never fewer than are shown.
    val announced = remember(state.pages.size) {
        state.pages.firstNotNullOfOrNull { page -> page.firstNotNullOfOrNull { it as? ContentBlock.Count } }?.total ?: 0
    }
    val totalSlides = maxOf(slides.size, if (state.endReached) slides.size else announced)
    val scope = rememberCoroutineScope()

    // A slide not loaded yet can't be shown; the reply says so instead of jumping short.
    ReaderControlTarget(SLIDE_VOICE_OPERATIONS, ReaderPosition(page = pager.currentPage + 1, pageCount = totalSlides)) { operation, value ->
        val target = when (operation) {
            "next_page" -> pager.currentPage + 1
            "previous_page" -> pager.currentPage - 1
            "scroll_top" -> 0
            "scroll_bottom" -> slides.size - 1
            "go_to_page" -> spokenNumber(value)?.minus(1) ?: return@ReaderControlTarget "missing_page"
            else -> return@ReaderControlTarget "unsupported_here"
        }
        when {
            target < 0 -> "at_first_page"
            target >= totalSlides -> if (operation == "next_page") "at_last_page" else "out_of_range"
            target >= slides.size -> "still_loading"
            else -> null.also { pager.animateScrollToPage(target) }
        }
    }

    // Load further slides before the user reaches the last one loaded.
    LaunchedEffect(pager.currentPage, slides.size, state.endReached, state.loadingMore) {
        if (!state.endReached && !state.loadingMore && state.loadMoreError == null && pager.currentPage >= slides.size - 3) {
            onLoadMore()
        }
    }

    // Slides are always shown as their author designed them; there is no text-only view.
    run {
        Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
            if (slides.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.endReached) Text("This presentation has no slides.", color = EzColors.TextSecondary)
                }
            } else {
                // Measured once out here, so each slide can sit in the middle of the space without
                // measuring itself twice.
                BoxWithConstraints(Modifier.weight(1f)) {
                val pageHeight = maxHeight
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                    pageSpacing = 12.dp,
                    // Two ready on each side: the slide a swipe lands on is built already, and the
                    // next ones are built after it settles, not while it moves.
                    beyondViewportPageCount = 2,
                    // slidesOf assigns consecutive 1-based numbers, so identity survives appends.
                    key = { it + 1 },
                ) { page ->
                    val slide = currentSlides.getOrNull(page)
                    // A previously scheduled item can outlive a deck replacement/shrink.
                    if (slide == null) {
                        Box(Modifier.fillMaxSize())
                        return@HorizontalPager
                    }
                    // Only slides within two of the settled one are built; one further off is
                    // built once a swipe ends, never during one.
                    if (abs(page - pager.settledPage) > 2) {
                        Box(Modifier.fillMaxSize())
                        return@HorizontalPager
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .deckTransform(pager, page)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // A slide shorter than the screen sits in its middle, not under the top bar.
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = pageHeight),
                            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                        ) {
                            SlideFrame(slide, Modifier.fillMaxWidth())
                            if (slide.notes.isNotEmpty()) NotesPanel(slide.notes)
                        }
                    }
                }
                }
                // Full screen keeps only the slides; swiping still moves between them.
                if (!LocalImmersive.current) Thumbnails(slides, pager.settledPage, { pager.isScrollInProgress }) { scope.launch { pager.animateScrollToPage(it) } }
            }
            if (!LocalImmersive.current) {
                SlideControls(
                    current = pager.currentPage,
                    count = totalSlides,
                    onPrevious = { haptics.deferFeedback { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } } },
                    onNext = { haptics.deferFeedback { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } } },
                    onPresent = { presenting = true },
                )
            }
        }
    }

    if (presenting && slides.isNotEmpty()) {
        Presenter(
            slides = slides,
            start = pager.currentPage.coerceIn(0, slides.lastIndex),
            moreComing = !state.endReached,
            onLoadMore = onLoadMore,
            onClose = { page ->
                presenting = false
                scope.launch { pager.scrollToPage(page) }
            },
        )
    }
}

/**
 * Slides away like cards in a deck while swiping: the outgoing slide shrinks,
 * tilts and dims, the incoming one rises to full size. Runs in the graphics
 * layer only, so scrolling never recomposes the slide.
 */
private fun Modifier.deckTransform(pager: PagerState, page: Int) = graphicsLayer {
    val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
    val distance = offset.absoluteValue
    val scale = lerp(1f, 0.86f, distance)
    scaleX = scale
    scaleY = scale
    alpha = lerp(1f, 0.55f, distance)
    rotationY = offset * 18f
    cameraDistance = 12f * density
}

/** A slide as a raised card: its designed canvas, or its text for layout-less formats. */
@Composable
private fun SlideFrame(slide: Slide, modifier: Modifier = Modifier, step: Int = Int.MAX_VALUE, animate: Boolean = false, elevated: Boolean = true) {
    val canvas = slide.canvas
    if (canvas == null) {
        SlideCard(slide, modifier, presenting = !elevated)
        return
    }
    val shape = RoundedCornerShape(if (elevated) 14.dp else 0.dp)
    Box(modifier.then(if (elevated) Modifier.raised(shape, 12.dp) else Modifier).clip(shape)) {
        SlideCanvas(canvas, slide.shapes, Modifier.fillMaxWidth(), step = step, animate = animate)
    }
}


/** A text slide drawn as a 16:9 card (taller when its text needs room). */
@Composable
private fun SlideCard(slide: Slide, modifier: Modifier = Modifier, presenting: Boolean = false) {
    BoxWithConstraints(modifier) {
        val minHeight = maxWidth * 9f / 16f
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .raised(RoundedCornerShape(18.dp), if (presenting) 0.dp else 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(EzPaper.Background)
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // A red accent bar, as in the reader design.
            Box(Modifier.size(width = 42.dp, height = 4.dp).clip(EzShapes.Pill).background(PrimaryGradient))
            val parts = remember(slide) { slideParts(slide.content) }
            for (part in parts) {
                when (part) {
                    is SlidePart.Single -> BlockView(part.block, compact = true)
                    is SlidePart.Table -> TableCard(part.rows)
                }
            }
            if (slide.content.isEmpty()) {
                Text("(This slide has no text)", color = EzPaper.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "${slide.number}",
                style = MaterialTheme.typography.labelMedium,
                color = EzPaper.TextSecondary,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/** A strip of miniature slides; tapping one jumps to it. */
@Composable
private fun Thumbnails(slides: List<Slide>, current: Int, pagerMoving: () -> Boolean, onSelect: (Int) -> Unit) {
    val list = rememberLazyListState()
    LaunchedEffect(current) {
        val visible = list.layoutInfo.visibleItemsInfo
        if (visible.none { it.index == current } || visible.first().index == current || visible.last().index == current) {
            list.animateScrollToItem((current - 1).coerceAtLeast(0))
        }
    }
    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(slides, key = { _, slide -> slide.number }) { index, slide ->
            val selected = index == current
            // A thumbnail is a whole slide in miniature, costly to build. Built while the strip
            // moves (it follows every page turn), it would cost frames of the turn itself.
            // Waits for the slide above to stop too: the strip settles before the page does.
            var built by remember { mutableStateOf(!list.isScrollInProgress && !pagerMoving()) }
            if (!built) LaunchedEffect(Unit) { snapshotFlow { list.isScrollInProgress || pagerMoving() }.first { !it }; built = true }
            val shape = RoundedCornerShape(8.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .width(92.dp)
                        .clip(shape)
                        .border(if (selected) 2.dp else 1.dp, if (selected) EzColors.Primary else EzColors.PageOutline, shape)
                        .clickable(role = Role.Tab) { onSelect(index) },
                ) {
                    val canvas = slide.canvas
                    if (canvas != null && !built) {
                        Box(Modifier.fillMaxWidth().aspectRatio(canvas.width / canvas.height.coerceAtLeast(1f)).background(EzPaper.Background))
                    } else if (canvas != null) {
                        // A tap on a thumbnail selects the slide; media plays from the slide itself.
                        // Its own layer: drawn once, then reused while the strip scrolls.
                        CompositionLocalProvider(LocalPlayMedia provides null) {
                            SlideCanvas(canvas, slide.shapes, Modifier.fillMaxWidth().graphicsLayer())
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(52.dp).background(EzPaper.Background), contentAlignment = Alignment.Center) {
                            Text(slide.content.firstText(), fontSize = 8.sp, maxLines = 3, color = EzPaper.Text, modifier = Modifier.padding(4.dp))
                        }
                    }
                }
                Text(
                    "${slide.number}",
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) EzColors.Primary else EzColors.TextSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

private fun List<ContentBlock>.firstText(): String = firstNotNullOfOrNull {
    when (it) {
        is ContentBlock.Heading -> it.text
        is ContentBlock.Paragraph -> it.text
        is ContentBlock.ListItem -> it.text
        else -> null
    }
} ?: ""

@Composable
private fun NotesPanel(notes: List<ContentBlock.Quote>) {
    Column(
        Modifier.fillMaxWidth().glass(EzShapes.Medium, 0.55f).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Speaker notes", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = EzColors.Accent)
        notes.forEach { note ->
            Text(richText(note.text, note.spans), style = MaterialTheme.typography.bodyMedium, color = EzColors.Text)
        }
    }
}

@Composable
private fun SlideControls(
    current: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPresent: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).glassBar().padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.glass(EzShapes.Pill, 0.6f), verticalAlignment = Alignment.CenterVertically) {
            BarIconButton(EzIcons.ChevronLeft, "Previous slide", onPrevious, enabled = current > 0)
            Text(
                if (count == 0) "–" else "${current + 1} / $count",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = EzColors.Text,
            )
            BarIconButton(EzIcons.ChevronRight, "Next slide", onNext, enabled = current < count - 1)
        }
        val interaction = remember { MutableInteractionSource() }
        Row(
            Modifier
                .clip(EzShapes.Pill)
                .background(PrimaryGradient)
                .clickable(interaction, indication = null, role = Role.Button, enabled = count > 0, onClick = rememberHapticActivation(onPresent))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(EzIcons.Play, null, tint = EzColors.OnPrimary, modifier = Modifier.size(18.dp))
            Text("Present", color = EzColors.OnPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}


/** Where the presenter is: a slide and how many of its click builds have played. */
private data class Position(val index: Int, val step: Int)

/**
 * Full-screen presenter, landscape and immersive. A tap on the right or a
 * swipe left plays the next build, then the next slide with its transition;
 * a tap on the left or a swipe right goes back.
 */
@Composable
private fun Presenter(slides: List<Slide>, start: Int, moreComing: Boolean, onLoadMore: () -> Unit, onClose: (Int) -> Unit) {
    val haptics = rememberEzHaptics()
    var index by rememberSaveable { mutableIntStateOf(start) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    val position = Position(index, step)

    fun next() {
        val slide = slides[index]
        if (step < slide.lastStep || index < slides.lastIndex) haptics.pageTurn()
        when {
            step < slide.lastStep -> step++
            index < slides.lastIndex -> {
                index++
                step = 0
            }
        }
    }

    fun previous() {
        if (step > 0 || index > 0) haptics.pageTurn()
        when {
            step > 0 -> step--
            index > 0 -> {
                index--
                step = slides[index].lastStep
            }
        }
    }

    LaunchedEffect(index, slides.size, moreComing) {
        if (moreComing && index >= slides.size - 3) onLoadMore()
    }

    Dialog(
        onDismissRequest = { onClose(index) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        PresentationMode()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding()
                .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Bottom))
                .pointerInput(slides.size) {
                    detectTapGestures { offset -> if (offset.x > size.width / 3) next() else previous() }
                }
                .pointerInput(slides.size) {
                    var drag = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = {
                            if (drag < -60f) next() else if (drag > 60f) previous()
                        },
                    ) { change, amount ->
                        change.consume()
                        drag += amount
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = position,
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
                contentKey = { it.index },
                transitionSpec = { slideTransition(slides) },
                label = "slide",
            ) { shown ->
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val slide = slides[shown.index.coerceIn(0, slides.lastIndex)]
                    // Letterbox: the widest slide that still fits the screen's height.
                    val aspect = slide.canvas?.let { it.width / it.height } ?: (16f / 9f)
                    val width = minOf(maxWidth, maxHeight * aspect)
                    // The outgoing slide keeps its builds; only the current one advances.
                    SlideFrame(
                        slide,
                        Modifier.width(width),
                        step = if (shown.index == index) step else Int.MAX_VALUE,
                        animate = true,
                        elevated = false,
                    )
                }
            }
            PresenterChrome(index, slides.size, onClose = { onClose(index) })
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PresenterChrome(index: Int, count: Int, onClose: () -> Unit) {
    Row(
        Modifier.align(Alignment.TopEnd).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "${index + 1} / $count",
            color = EzColors.OnScrim.copy(alpha = 0.85f),
            fontSize = 13.sp,
            modifier = Modifier.clip(EzShapes.Pill).background(EzColors.Scrim).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(EzColors.Scrim).clickable(role = Role.Button, onClick = rememberHapticActivation(onClose)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(EzIcons.Close, "Exit presentation", tint = EzColors.OnScrim)
        }
    }
    // Progress through the deck.
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxWidth((index + 1f) / count.coerceAtLeast(1)).height(3.dp).background(PrimaryGradient))
    }
}

/**
 * The slide's own transition (`p:transition`), with PowerPoint's direction:
 * going back plays it mirrored. Slides without one get a short fade.
 */
private fun AnimatedContentTransitionScope<Position>.slideTransition(slides: List<Slide>): ContentTransform {
    val forward = targetState.index >= initialState.index
    val target = slides.getOrNull(if (forward) targetState.index else initialState.index)
    val dir = if (forward) 1 else -1
    val time = 520
    val ease = tween<Float>(time, easing = FastOutSlowInEasing)
    fun <T> t() = tween<T>(time, easing = FastOutSlowInEasing)
    val fade = fadeIn(ease) togetherWith fadeOut(ease)
    return when (target?.canvas?.transition) {
        null -> fadeIn(tween(300)) togetherWith fadeOut(tween(300))
        "cut" -> EnterTransition.None togetherWith ExitTransition.None
        "fade", "dissolve", "morph", "random", "randomBar" -> fade
        "push", "pan", "conveyor", "ferris", "flythrough", "gallery", "switch", "flip" ->
            slideInHorizontally(t()) { it * dir } togetherWith slideOutHorizontally(t()) { -it * dir }
        "cover", "comb", "doors", "window" ->
            slideInHorizontally(t()) { it * dir } togetherWith fadeOut(tween(time, delayMillis = time / 2))
        "pull", "uncover", "reveal", "curtains", "drape", "fallOver" -> (EnterTransition.None togetherWith slideOutHorizontally(t()) { -it * dir })
            .apply { targetContentZIndex = -1f }
        "wipe", "split", "strips", "blinds", "checker", "wedge", "wheel", "wheelReverse", "circle", "diamond", "plus", "shape", "honeycomb" ->
            expandHorizontally(t(), expandFrom = if (forward) Alignment.Start else Alignment.End) togetherWith fadeOut(tween(time, delayMillis = time / 2))
        "newsflash", "vortex", "ripple", "glitter", "shred", "prism", "doors3d" ->
            scaleIn(ease, initialScale = 0.4f) + fadeIn(ease) togetherWith scaleOut(ease, targetScale = 1.2f) + fadeOut(ease)
        "zoom", "warp", "flash" ->
            scaleIn(ease, initialScale = if (forward) 0.7f else 1.3f) + fadeIn(ease) togetherWith fadeOut(ease)
        "cube", "box", "rotate", "orbit" ->
            slideInHorizontally(t()) { it * dir } + scaleIn(ease, initialScale = 0.85f) togetherWith
                slideOutHorizontally(t()) { -it * dir } + scaleOut(ease, targetScale = 0.85f)
        "fly", "airplane", "origami", "fracture", "crush", "peelOff", "pageCurlSingle", "pageCurlDouble", "curl" ->
            slideInVertically(t()) { it / 3 } + fadeIn(ease) togetherWith fadeOut(ease)
        else -> fade
    }
}
