package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.mediaKindOf
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzPaper
import com.ezfiles.reader.presentation.theme.LocalPaperColors

private val PlaceholderBackground = Color(0xFFFCF8FA)
private val UNKNOWN_HEIGHT = 180.dp
private val MIN_PICTURE_SIDE = 24.dp
private val MAX_PICTURE_HEIGHT = 1200.dp
private val MIN_LABEL_WIDTH = 120.dp
private val MIN_LABEL_HEIGHT = 48.dp
private const val MIN_PICTURE_DIMENSION = 256
private const val MAX_PICTURE_DIMENSION = 1600

private sealed interface Picture {
    data object Loading : Picture

    data object Failed : Picture

    data class Loaded(val bitmap: ImageBitmap) : Picture
}

/**
 * The box a picture is drawn in. Its width is the document's size hint, or
 * else the picture's own (one pixel to one dp, as in a browser), never wider
 * than [available]. The height follows from the hints, or else from the
 * picture, and is `null` until one of them is known. Document points count as
 * dp. Sizes come from the document, so extreme ones are clamped: Compose
 * throws on a layout too large to represent.
 */
internal fun pictureBox(
    available: Dp,
    hintWidth: Float?,
    hintHeight: Float?,
    naturalWidth: Int,
    naturalHeight: Int,
): Pair<Dp, Dp?> {
    val wanted = hintWidth?.dp ?: if (naturalWidth > 0) naturalWidth.dp else available
    val width = minOf(available, maxOf(wanted, MIN_PICTURE_SIDE))
    val ratio = when {
        hintWidth != null && hintHeight != null -> hintHeight / hintWidth
        naturalWidth > 0 && naturalHeight > 0 -> naturalHeight.toFloat() / naturalWidth
        else -> null
    }
    return width to ratio?.let { (width * it).coerceIn(MIN_PICTURE_SIDE, MAX_PICTURE_HEIGHT) }
}

/**
 * A picture in the reading flow, fitted to the reading width. Its height is
 * reserved from the document's size hints, or remembered once the picture has
 * loaded, so scrolling does not jump. A quiet label stands in while it loads,
 * if it cannot be decoded, and for a block with nothing to load.
 */
@Composable
internal fun DocumentImage(image: ContentBlock.Image) {
    val path = image.path
    if (path == null) {
        PictureLabel(image.alt, Modifier.fillMaxWidth())
        return
    }
    // EPUB audio and video arrive as picture blocks naming the media file.
    if (mediaKindOf(path) != null) {
        MediaCard(path, image.alt)
        return
    }
    val images = LocalSlideImages.current
    // Kept while the item scrolls out of the list.
    var naturalWidth by rememberSaveable(path) { mutableIntStateOf(0) }
    var naturalHeight by rememberSaveable(path) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.TopCenter) {
        val available = if (constraints.hasBoundedWidth) maxWidth else ReadingWidth
        val maxDimension = with(LocalDensity.current) { available.roundToPx() }
            .coerceIn(MIN_PICTURE_DIMENSION, MAX_PICTURE_DIMENSION)
        val picture by produceState<Picture>(Picture.Loading, path, maxDimension) {
            val bitmap = images.load(path, maxDimension)
            if (bitmap != null) {
                naturalWidth = bitmap.width
                naturalHeight = bitmap.height
            }
            value = bitmap?.let { Picture.Loaded(it) } ?: Picture.Failed
        }
        val (width, height) = pictureBox(available, image.width, image.height, naturalWidth, naturalHeight)
        val box = Modifier.size(width, height ?: UNKNOWN_HEIGHT)
        val roomy = width >= MIN_LABEL_WIDTH && (height ?: UNKNOWN_HEIGHT) >= MIN_LABEL_HEIGHT
        when (val current = picture) {
            is Picture.Loaded -> Image(
                current.bitmap,
                contentDescription = image.alt,
                modifier = box,
                contentScale = ContentScale.Fit,
            )
            Picture.Loading -> PictureLabel(image.alt, box, showText = roomy)
            // Keep a reserved height, so a failure does not move the text below.
            Picture.Failed ->
                if (height != null && roomy) {
                    PictureLabel(image.alt, box)
                } else {
                    PictureLabel(image.alt, Modifier.fillMaxWidth())
                }
        }
    }
}

@Composable
private fun PictureLabel(alt: String?, modifier: Modifier, showText: Boolean = true) {
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(PlaceholderBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (showText) {
            Text(
                "🖼 ${alt ?: "Image"}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalPaperColors.current.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}
