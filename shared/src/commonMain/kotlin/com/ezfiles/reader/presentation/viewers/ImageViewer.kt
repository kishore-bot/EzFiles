package com.ezfiles.reader.presentation.viewers

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import com.ezfiles.reader.presentation.theme.LocalReducedMotion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.AnimatedImage
import com.ezfiles.reader.platform.MemoryPressure
import com.ezfiles.reader.platform.rememberPhotoBytes
import com.ezfiles.reader.presentation.ReaderUiState
import com.ezfiles.reader.presentation.components.GlassIconButton
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.EzIcons
import com.ezfiles.reader.presentation.theme.glass
import com.ezfiles.reader.presentation.theme.rememberEzHaptics
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** A single-file grant stays a single photo; a supplied authorized session enables paging. */
@Composable
fun ImageViewer(state: ReaderUiState.Ready) {
    val session = LocalPhotoGallerySession.current?.takeIf { it.indexOf(state.ref.uri) >= 0 }
    val refs = session?.refs ?: listOf(state.ref)
    key(session ?: state.openId) { PhotoGallery(refs, session) }
}

@Composable
private fun PhotoGallery(refs: List<DocumentRef>, session: PhotoGallerySession?) {
    val source = rememberPhotoBytes()
    val loader = remember(source) { PhotoImageLoader(source) }
    DisposableEffect(loader) {
        val unregister = MemoryPressure.register(loader::trimMemory)
        onDispose { unregister(); loader.close() }
    }
    val preview by loader.preview.collectAsState()
    var savedUri by rememberSaveable { mutableStateOf(session?.selected?.value?.uri ?: refs.first().uri) }
    val initial = refs.indexOfFirst { it.uri == savedUri }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = initial, pageCount = { refs.size })
    var restored by remember { mutableStateOf(false) }
    var pagingAllowed by remember { mutableStateOf(true) }
    var previous by remember { mutableIntStateOf(initial) }
    var buttonTarget by remember { mutableStateOf<Int?>(null) }
    var loadedUri by remember { mutableStateOf<String?>(null) }
    var prefetchDirection by remember { mutableIntStateOf(1) }
    val haptics = rememberEzHaptics()
    val scope = rememberCoroutineScope()
    val reducedMotion = LocalReducedMotion.current
    LaunchedEffect(session) {
        // Restore by URI, rather than applying an old integer to a newly refreshed sequence.
        if (pager.currentPage != initial) pager.scrollToPage(initial)
        restored = true
    }
    LaunchedEffect(pager.settledPage, restored) {
        if (!restored) return@LaunchedEffect
        val selected = pager.settledPage
        prefetchDirection = if (selected < previous) -1 else 1
        if (selected != previous && buttonTarget != selected) haptics.pageTurn()
        buttonTarget = null
        previous = selected
        savedUri = refs[selected].uri
        session?.select(selected)
    }
    LaunchedEffect(pager.settledPage, loadedUri, prefetchDirection) {
        val selected = pager.settledPage
        // Give the visible request priority and keep its prefetched bytes until it has consumed them.
        if (loadedUri != refs[selected].uri) return@LaunchedEffect
        val neighbor = refs.getOrNull(selected + prefetchDirection) ?: refs.getOrNull(selected - prefetchDirection)
        if (neighbor != null) loader.prefetch(neighbor)
    }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            key = { refs[it].uri },
            beyondViewportPageCount = 0,
            userScrollEnabled = pagingAllowed && refs.size > 1,
        ) { page ->
            val ref = refs[page]
            val active = page == pager.settledPage
            val image by produceState<ViewedImage?>(null, ref.uri, active) {
                value = null
                if (!active) return@produceState
                var owned: ViewedImage? = null
                try {
                    owned = loader.view(ref)
                    value = owned
                    loadedUri = ref.uri
                    awaitCancellation()
                } finally {
                    (owned as? ViewedImage.Animated)?.animation?.close()
                }
            }
            val adjacentPreview = preview?.takeIf { it.key == PhotoKey(ref) }?.bitmap?.let { ViewedImage.Still(it) }
            val displayed = if (active) image ?: adjacentPreview else adjacentPreview
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (val current = displayed) {
                    null -> CircularProgressIndicator(color = EzColors.Primary)
                    ViewedImage.Unsupported -> ImageMessage("This photo is unavailable or this device can't display it. Choose another photo or open it again.")
                    ViewedImage.TooLarge -> ImageMessage("This photo is too large to display safely.")
                    is ViewedImage.Still -> if (active) {
                        ZoomableImage(current.bitmap.width, current.bitmap.height, { pagingAllowed = it }) { modifier ->
                            Image(current.bitmap, ref.displayName, modifier = modifier, contentScale = ContentScale.Fit)
                        }
                    } else Image(current.bitmap, ref.displayName, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    is ViewedImage.Animated -> ZoomableImage(current.animation.width, current.animation.height, { pagingAllowed = it }) { modifier ->
                        AnimatedPicture(current.animation, ref.displayName, modifier)
                    }
                }
            }
        }
        if (refs.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassIconButton(EzIcons.ChevronLeft, "Previous photo", {
                    val target = (pager.settledPage - 1).coerceAtLeast(0)
                    buttonTarget = target
                    haptics.pageTurn()
                    scope.launch { if (reducedMotion) pager.scrollToPage(target) else pager.animateScrollToPage(target) }
                }, enabled = pager.settledPage > 0 && !pager.isScrollInProgress)
                Text(
                    "${pager.settledPage + 1} / ${refs.size}",
                    color = EzColors.Text,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.glass(EzShapes.Pill, 0.6f).padding(horizontal = 14.dp, vertical = 10.dp)
                        .clearAndSetSemantics { contentDescription = "Photo ${pager.settledPage + 1} of ${refs.size}"; liveRegion = LiveRegionMode.Polite },
                )
                GlassIconButton(EzIcons.ChevronRight, "Next photo", {
                    val target = (pager.settledPage + 1).coerceAtMost(refs.lastIndex)
                    buttonTarget = target
                    haptics.pageTurn()
                    scope.launch { if (reducedMotion) pager.scrollToPage(target) else pager.animateScrollToPage(target) }
                }, enabled = pager.settledPage < refs.lastIndex && !pager.isScrollInProgress)
            }
        }
    }
}

@Composable
private fun ImageMessage(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = EzColors.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
}

@Composable
private fun AnimatedPicture(animation: AnimatedImage, description: String, modifier: Modifier) {
    DisposableEffect(animation) { onDispose { animation.close() } }
    LifecycleStartEffect(animation) {
        animation.start()
        onStopOrDispose { animation.stop() }
    }
    Canvas(modifier, contentDescription = description) {
        val fit = minOf(size.width / animation.width, size.height / animation.height)
        translate((size.width - animation.width * fit) / 2, (size.height - animation.height * fit) / 2) {
            scale(fit, fit, pivot = Offset.Zero) { animation.draw(this) }
        }
    }
}

@Composable
private fun ZoomableImage(width: Int, height: Int, onPagingAllowed: (Boolean) -> Unit, content: @Composable (Modifier) -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var turns by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var multiplePointers by remember { mutableStateOf(false) }
    val reducedMotion = LocalReducedMotion.current
    val rotation by animateFloatAsState(turns * 90f, animationSpec = if (reducedMotion) snap() else spring(), label = "photoRotation")
    val haptics = rememberEzHaptics()
    fun clamp(offset: Offset): Offset {
        val bounds = photoBounds(viewport.width.toFloat(), viewport.height.toFloat(), width, height, zoom, turns)
        return Offset(offset.x.coerceIn(-bounds.panX, bounds.panX), offset.y.coerceIn(-bounds.panY, bounds.panY))
    }
    val transform = rememberTransformableState { change, move, _ ->
        zoom = (zoom * change).coerceIn(1f, 6f)
        pan = clamp(pan + move)
        onPagingAllowed(zoom <= 1.001f && !multiplePointers)
    }
    DisposableEffect(Unit) { onDispose { onPagingAllowed(true) } }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it; pan = clamp(pan) }) {
        content(
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var multipleSeen = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed > 1) multipleSeen = true
                            if (pressed == 0) multipleSeen = false
                            multiplePointers = multipleSeen
                            onPagingAllowed(zoom <= 1.001f && !multiplePointers)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { tapped ->
                        zoom = if (zoom > 1.001f) 1f else 2.5f
                        pan = clamp(Offset((viewport.width / 2f - tapped.x) * (zoom - 1), (viewport.height / 2f - tapped.y) * (zoom - 1)))
                        onPagingAllowed(zoom <= 1.001f)
                        haptics.toggle(zoom > 1.001f)
                    })
                }
                .transformable(transform, canPan = { zoom > 1.001f }, lockRotationOnZoomPan = true)
                .graphicsLayer {
                    val bounds = photoBounds(viewport.width.toFloat(), viewport.height.toFloat(), width, height, zoom, turns)
                    scaleX = zoom * bounds.fitScale
                    scaleY = zoom * bounds.fitScale
                    translationX = pan.x
                    translationY = pan.y
                    rotationZ = rotation
                },
        )
        GlassIconButton(EzIcons.Rotate, "Rotate image", {
            turns += 1
            pan = clamp(pan)
            haptics.select()
        }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}
