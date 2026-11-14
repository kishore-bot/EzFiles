package com.ezfiles.reader.presentation.viewers

import com.ezfiles.reader.presentation.components.EzDialog
import androidx.compose.foundation.background
import com.ezfiles.reader.presentation.components.EzTextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.rememberPageFolderAccess
import com.ezfiles.reader.presentation.PageFind
import com.ezfiles.reader.presentation.ReaderUiState
import com.ezfiles.reader.presentation.theme.EzColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An embedded resource of the open document, or `null` if it can't be read. */
val LocalDocumentResource = staticCompositionLocalOf<suspend (String) -> ByteArray?> { { null } }

/** Names the page for [com.ezfiles.reader.domain.DocumentSession.resource]: a web page's own bytes. */
private const val HTML_RESOURCE = "html"

/** A Word document as print-layout pages, built by the engine. */
private const val PAGES_RESOURCE = "ezfiles:pages.html"

/** A Markdown file rendered, built by the engine. */
private const val PREVIEW_RESOURCE = "ezfiles:preview.html"

private sealed interface PageLoad {
    data object Loading : PageLoad

    data object Failed : PageLoad

    data class Loaded(val prepared: PreparedHtml) : PageLoad
}

/** A tap on a link the page can't follow by itself. */
internal sealed interface LinkPrompt {
    data class Open(val url: String) : LinkPrompt

    data class Notice(val text: String) : LinkPrompt
}

/** A web page, as an offline browser shows it. */
@Composable
fun HtmlViewer(state: ReaderUiState.Ready, onFind: (PageFind) -> Unit, darkReading: Boolean = false) =
    WebPageViewer(state, PageKind.Web, onFind, darkReading = darkReading)

/**
 * A Word or OpenDocument text laid out as pages, like Word's Print Layout.
 * [fallback] shows the text instead if the pages can't be built or shown.
 */
@Composable
fun WordPagesViewer(state: ReaderUiState.Ready, onFind: (PageFind) -> Unit, darkReading: Boolean = false, fallback: @Composable () -> Unit) =
    WebPageViewer(state, PageKind.Pages, onFind, fallback, darkReading = darkReading)

/** Markdown rendered, like Android Studio's preview. */
@Composable
fun MarkdownPreviewViewer(state: ReaderUiState.Ready, onFind: (PageFind) -> Unit, darkReading: Boolean = false) =
    WebPageViewer(state, PageKind.Preview, onFind, darkReading = darkReading)

/**
 * Shows a page in [HtmlPage]. Its bytes come from the open session, so a file
 * opened with a read grant only, or one that expires after this session,
 * still shows. [onFind] reports find-in-page to the search bar.
 */
@Composable
private fun WebPageViewer(
    state: ReaderUiState.Ready,
    kind: PageKind,
    onFind: (PageFind) -> Unit,
    fallback: (@Composable () -> Unit)? = null,
    darkReading: Boolean = false,
) {
    // A reopened file can be the same ref with new content, after a save.
    key(state.openId) {
        val resource = LocalDocumentResource.current
        val folder = rememberPageFolderAccess(state.ref)
        val load by produceState<PageLoad>(PageLoad.Loading) {
            val name = when (kind) {
                PageKind.Web -> HTML_RESOURCE
                PageKind.Pages -> PAGES_RESOURCE
                PageKind.Preview -> PREVIEW_RESOURCE
                PageKind.Book -> error("books open in BookViewer")
            }
            val bytes = resource(name)
            value = if (bytes == null) PageLoad.Failed else PageLoad.Loaded(withContext(Dispatchers.Default) { prepareHtml(bytes, kind) })
        }
        var failure by remember { mutableStateOf<HtmlPageFailure?>(null) }
        var prompt by remember { mutableStateOf<LinkPrompt?>(null) }

        val current = load
        if (fallback != null && (current is PageLoad.Failed || failure != null)) {
            Column(Modifier.fillMaxSize()) {
                HtmlNote(PAGES_FALLBACK_NOTE)
                Box(Modifier.weight(1f).fillMaxWidth()) { fallback() }
            }
        } else Column(Modifier.fillMaxSize()) {
            if (current is PageLoad.Loaded && failure == null) {
                val prepared = current.prepared
                if (prepared.localFiles && !folder.available) {
                    FolderNote(kind, onAllow = folder::choose)
                } else if (kind == PageKind.Web && prepared.online && !LocalImmersive.current) {
                    HtmlNote(OFFLINE_NOTE)
                }
                if (prepared.media.isNotEmpty() && !folder.available) LinkedMediaBar(state.ref, prepared.media)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shownFailure = failure
                when {
                    current is PageLoad.Loading -> CircularProgressIndicator(color = EzColors.Primary)
                    current is PageLoad.Failed -> HtmlMessage("This page couldn't be read for preview. Its code is under Source.")
                    shownFailure != null -> HtmlMessage(shownFailure.message)
                    current is PageLoad.Loaded -> HtmlPage(
                        page = current.prepared.page,
                        kind = kind,
                        document = state.ref,
                        folderVersion = folder.version,
                        resource = resource,
                        query = state.search?.results?.query.orEmpty(),
                        step = state.search?.pageStep ?: 0,
                        forward = state.search?.pageStepForward ?: true,
                        onFind = onFind,
                        onLink = { url, refused ->
                            prompt = if (refused) LinkPrompt.Notice(REFUSED_LINK) else LinkPrompt.Open(url)
                        },
                        onFailure = { failure = it },
                        modifier = Modifier.fillMaxSize(),
                        darkReading = darkReading,
                    )
                }
            }
        }
        LinkDialog(prompt, onPrompt = { prompt = it })
        folder.problem?.let { problem ->
            EzDialog(
                onDismissRequest = folder::dismissProblem,
                text = { Text(problem) },
                confirmButton = { EzTextButton("OK", folder::dismissProblem) },
            )
        }
    }
}

/** Asks for the page's folder, where its styles, scripts and pictures are. */
@Composable
private fun FolderNote(kind: PageKind, onAllow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(EzColors.Background).padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (kind == PageKind.Web) "Its styles, scripts and pictures are in its folder" else "Its pictures are in its folder",
            style = MaterialTheme.typography.labelMedium,
            color = EzColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        EzTextButton("Allow folder", onAllow)
    }
}

private const val PAGES_FALLBACK_NOTE = "This document couldn't be laid out as pages, so only its text is shown."
private const val OFFLINE_NOTE = "Online content doesn't load: EzFiles works offline"
private const val REFUSED_LINK = "EzFiles doesn't open this kind of link."
private const val NO_APP_FOR_LINK = "No app on this device can open this link."

internal val HtmlPageFailure.message: String
    get() = when (this) {
        HtmlPageFailure.Stopped -> "The page stopped working and was closed. Its code is under Source."
        HtmlPageFailure.Unavailable -> "This device's web viewer isn't available right now. The page's code is under Source."
    }

/** A quiet line above the page, for what it can't do or why its code is showing. */
@Composable
internal fun HtmlNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = EzColors.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(EzColors.Background)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun HtmlMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = EzColors.TextSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
internal fun LinkDialog(prompt: LinkPrompt?, onPrompt: (LinkPrompt?) -> Unit) {
    val uris = LocalUriHandler.current
    when (prompt) {
        null -> {}
        is LinkPrompt.Open -> EzDialog(
            onDismissRequest = { onPrompt(null) },
            title = { Text("Open this link in your browser?") },
            text = { Text(htmlLinkForDisplay(prompt.url)) },
            confirmButton = {
                TextButton(onClick = {
                    // The handler throws if no installed app takes the link.
                    val opened = runCatching { uris.openUri(prompt.url) }.isSuccess
                    onPrompt(if (opened) null else LinkPrompt.Notice(NO_APP_FOR_LINK))
                }) { Text("Open", color = EzColors.Accent) }
            },
            dismissButton = {
                EzTextButton("Cancel", { onPrompt(null) })
            },
        )
        is LinkPrompt.Notice -> EzDialog(
            onDismissRequest = { onPrompt(null) },
            text = { Text(prompt.text) },
            confirmButton = {
                EzTextButton("OK", { onPrompt(null) })
            },
        )
    }
}

/** Why no page is showing. */
internal enum class HtmlPageFailure {
    /** The renderer crashed or was killed. */
    Stopped,

    /** No web viewer can be created, for example while the system updates it. */
    Unavailable,
}

/**
 * Shows [page], with no way out: nothing it asks to load or run gets through.
 * [query] is searched in the page, [step] counts presses of next or previous,
 * and [onLink] gets a tapped link with whether it was refused.
 */
@Composable
internal expect fun HtmlPage(
    page: String,
    kind: PageKind,
    document: DocumentRef,
    folderVersion: Int,
    resource: suspend (String) -> ByteArray?,
    query: String,
    step: Int,
    forward: Boolean,
    onFind: (PageFind) -> Unit,
    onLink: (url: String, refused: Boolean) -> Unit,
    onFailure: (HtmlPageFailure) -> Unit,
    modifier: Modifier,
    darkReading: Boolean = false,
)
