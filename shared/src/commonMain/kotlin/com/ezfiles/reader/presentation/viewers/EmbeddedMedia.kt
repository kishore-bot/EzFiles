package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.domain.MediaKind
import com.ezfiles.reader.domain.mediaKindOf
import com.ezfiles.reader.platform.MediaSource
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons

/** Plays embedded media by its path; `null` where documents are only previewed, as in thumbnails. */
val LocalPlayMedia = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * Lets the documents in [content] play their embedded audio and video: a tap
 * opens the player full screen, reading the bytes through [LocalSlideImages].
 */
@Composable
fun EmbeddedMediaHost(content: @Composable () -> Unit) {
    var playing by remember { mutableStateOf<String?>(null) }
    CompositionLocalProvider(LocalPlayMedia provides { playing = it }, content = content)
    val path = playing ?: return
    val images = LocalSlideImages.current
    val source = remember(path, images) { MediaSource.Embedded(path) { images.bytes(path) } }
    MediaDialog(source, mediaKindOf(path) ?: MediaKind.Video, path.substringAfterLast('/')) { playing = null }
}

/** A play button over a slide's media shape, sized to the shape. */
@Composable
internal fun MediaBadge(path: String, poster: Boolean, shapeSide: Dp) {
    val play = LocalPlayMedia.current
    val audio = mediaKindOf(path) == MediaKind.Audio
    Box(
        Modifier
            .fillMaxSize()
            .then(if (poster) Modifier else Modifier.background(Color(0xFF111827)))
            .then(if (play != null) Modifier.clickable(role = Role.Button, onClickLabel = if (audio) "Play audio" else "Play video", onClick = rememberHapticActivation { play(path) }) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val side = (shapeSide * 0.38f).coerceIn(14.dp, 72.dp)
        Box(
            Modifier.size(side).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (audio) EzIcons.Music else EzIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(side * 0.55f))
        }
    }
}

/** EPUB audio or video in the reading flow: a card that opens the player. */
@Composable
internal fun MediaCard(path: String, label: String?) {
    val play = LocalPlayMedia.current
    val audio = mediaKindOf(path) == MediaKind.Audio
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(listOf(Color(0xFF1E1B4B), Color(0xFF4C1D95))))
            .then(if (play != null) Modifier.clickable(role = Role.Button, onClick = rememberHapticActivation { play(path) }) else Modifier)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(EzIcons.Play, contentDescription = null, tint = EzColors.Primary, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label ?: if (audio) "Audio" else "Video", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(path.substringAfterLast('/'), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
        }
        Icon(if (audio) EzIcons.Music else EzIcons.Movie, contentDescription = null, tint = Color.White.copy(alpha = 0.8f))
    }
}
