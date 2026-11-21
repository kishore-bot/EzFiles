package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import com.ezfiles.reader.presentation.components.EzDialog
import androidx.compose.foundation.background
import com.ezfiles.reader.presentation.components.EzTextButton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.MediaKind
import com.ezfiles.reader.domain.mediaKindOf
import com.ezfiles.reader.platform.MediaSource
import com.ezfiles.reader.platform.rememberMediaFilePicker
import com.ezfiles.reader.platform.rememberSiblingFinder
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzIcons
import kotlinx.coroutines.launch

/**
 * The audio and video a page plays from files beside it, which the locked
 * page can't load. Tapping one plays it in the app's player: found next to the
 * page when it was opened from a library folder, or else chosen by the user,
 * as the app may only open files it is given.
 */
@Composable
internal fun LinkedMediaBar(page: DocumentRef, media: List<LinkedMedia>) {
    val findSibling = rememberSiblingFinder()
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf<LinkedMedia?>(null) }
    var asking by remember { mutableStateOf<LinkedMedia?>(null) }
    var pickingFor by remember { mutableStateOf<LinkedMedia?>(null) }
    var playing by remember { mutableStateOf<Pair<DocumentRef, LinkedMedia>?>(null) }
    val picker = rememberMediaFilePicker { picked ->
        val item = pickingFor
        pickingFor = null
        if (picked != null && item != null) playing = picked to item
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(EzColors.Background)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Media on this page", style = MaterialTheme.typography.labelMedium, color = EzColors.TextSecondary)
        for (item in media) {
            MediaChip(item, busy = searching == item) {
                if (searching != null) return@MediaChip
                searching = item
                scope.launch {
                    val found = findSibling(page, item.path)
                    searching = null
                    if (found != null) playing = found to item else asking = item
                }
            }
        }
    }

    asking?.let { item ->
        EzDialog(
            onDismissRequest = { asking = null },
            title = { Text("Play “${item.name}”?") },
            text = {
                Text(
                    "This page plays a separate file, “${item.name}”. Xfiles only opens files you choose, " +
                        "so pick it to play it here. Adding the page's folder to your library lets Xfiles find it next time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    pickingFor = item
                    picker.launch()
                }) { Text("Choose file", color = EzColors.Accent) }
            },
            dismissButton = { EzTextButton("Cancel", { asking = null }) },
        )
    }
    playing?.let { (file, item) ->
        MediaDialog(MediaSource.File(file), mediaKindOf(file.displayName) ?: item.kind, item.name) { playing = null }
    }
}

@Composable
private fun MediaChip(item: LinkedMedia, busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .clip(shape)
            .background(Color.White)
            .border(0.5.dp, EzColors.PageOutline, shape)
            .clickable(role = Role.Button, onClickLabel = "Play ${item.name}", onClick = rememberHapticActivation(onClick))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = EzColors.Primary)
        } else {
            Icon(
                if (item.kind == MediaKind.Video) EzIcons.Movie else EzIcons.Music,
                contentDescription = null,
                tint = EzColors.Primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            item.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = EzColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 220.dp),
        )
    }
}
