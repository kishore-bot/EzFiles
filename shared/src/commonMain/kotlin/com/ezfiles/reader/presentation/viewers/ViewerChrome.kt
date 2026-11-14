package com.ezfiles.reader.presentation.viewers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.DocumentInfo
import com.ezfiles.reader.presentation.ReaderUiState
import com.ezfiles.reader.presentation.components.EzTextButton
import com.ezfiles.reader.presentation.title

/** Request the next batch when this many items remain below the viewport. */
private const val PREFETCH_DISTANCE = 30

/** Asks for the next batch whenever the list nears the end of what is loaded. */
/** Whether the reader is full screen, so viewers can put away their own controls too. */
val LocalImmersive = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
internal fun LoadMoreWhenNearEnd(listState: LazyListState, state: ReaderUiState.Ready, onLoadMore: () -> Unit) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layout.totalItemsCount - PREFETCH_DISTANCE
        }
    }
    val canLoadMore = !state.endReached && !state.loadingMore && state.loadMoreError == null
    LaunchedEffect(nearEnd, canLoadMore) {
        if (nearEnd && canLoadMore) onLoadMore()
    }
}

@Composable
internal fun DocumentFooter(state: ReaderUiState.Ready, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val error = state.loadMoreError
        when {
            error != null -> {
                Text("${error.title()}: ${error.detail}", color = MaterialTheme.colorScheme.error)
                EzTextButton("Retry", onRetry)
            }
            state.loadingMore -> CircularProgressIndicator()
            state.truncated -> Text(
                "Preview limit reached; the rest of the file is not shown.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.endReached && state.pages.all { it.isEmpty() } -> Text(
                "This document has no readable text.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes * 10 / 1024) / 10.0} KB"
    else -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
}
