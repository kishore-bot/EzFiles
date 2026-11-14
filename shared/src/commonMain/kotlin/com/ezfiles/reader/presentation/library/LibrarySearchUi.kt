package com.ezfiles.reader.presentation.library

import com.ezfiles.reader.presentation.theme.rememberHapticActivation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ezfiles.reader.domain.IndexProgress
import com.ezfiles.reader.presentation.components.MediaPreviewTile
import com.ezfiles.reader.presentation.theme.EzColors
import com.ezfiles.reader.presentation.theme.EzShapes
import com.ezfiles.reader.presentation.theme.FileNameStyle
import com.ezfiles.reader.presentation.theme.glassCard
import com.ezfiles.reader.presentation.theme.pressScale

/**
 * A file found by its text: the name, where the match is, and the passage with
 * the matched words in bold accent. Opens the file (at the page, for a PDF).
 */
@Composable
internal fun SnippetCard(hit: ContentItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val accent = EzColors.Accent
    val snippet = remember(hit.snippet, hit.highlights, accent) { highlighted(hit.snippet, hit.highlights, accent) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressScale(interaction, 0.985f)
            .glassCard(EzShapes.Card, 0.5f)
            .clickable(interaction, indication = null, role = Role.Button, onClick = rememberHapticActivation(onClick))
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MediaPreviewTile(hit.item.ref, hit.item.type)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(hit.item.ref.displayName, style = FileNameStyle, color = EzColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = listOfNotNull(hit.page?.let { "Page ${it + 1}" }, hit.item.meta.takeIf { it.isNotEmpty() }).joinToString("  •  ")
            if (where.isNotEmpty()) {
                Text(where, fontSize = 12.sp, color = EzColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (snippet.isNotEmpty()) {
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = EzColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** "Indexing 12 of 340…": shown under Inside files only while the index is incomplete. */
internal fun indexingLabel(progress: IndexProgress) = "Indexing ${progress.indexed} of ${progress.total}…"

/** [text] with [ranges] (from the engine; any out of bounds are skipped) in bold [color]. */
internal fun highlighted(text: String, ranges: List<IntRange>, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val style = SpanStyle(fontWeight = FontWeight.SemiBold, color = color)
    for (range in ranges) {
        if (range.first in text.indices && range.last in text.indices && range.first <= range.last) {
            addStyle(style, range.first, range.last + 1)
        }
    }
}
