package com.ezfiles.reader.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.LocalMediaThumbnails
import com.ezfiles.reader.platform.MediaThumbnail
import com.ezfiles.reader.presentation.library.FileType
import com.ezfiles.reader.presentation.theme.EzIcons

/** The row remains the sole click target; a preview never starts playback. */
@Composable
fun MediaPreviewTile(ref: DocumentRef, type: FileType) {
    if (type != FileType.Image && type != FileType.Video) {
        FileTypeTile(type)
        return
    }
    val loader = LocalMediaThumbnails.current
    val side = with(LocalDensity.current) { 56.dp.roundToPx().coerceIn(64, 256) }
    val video = type == FileType.Video
    val preview by produceState<MediaThumbnail?>(null, loader, ref, side) {
        value = null
        value = loader.load(ref, video, side)
    }
    val current = preview
    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        if (current == null) FileTypeTile(type)
        else {
            Image(current.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            if (video) {
                Icon(EzIcons.Play, null, tint = Color.White, modifier = Modifier.align(Alignment.TopStart)
                    .padding(3.dp).background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(5.dp)).size(16.dp))
                current.durationSeconds?.let { seconds ->
                    val label = if (seconds >= 3600) "${seconds / 3600}:${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
                        else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                    Text(label, color = Color.White, fontSize = 9.sp, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp))
                }
            }
        }
    }
}
