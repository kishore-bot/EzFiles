package com.ezfiles.reader.presentation.viewers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.graphics.Rect
import android.view.ActionMode
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.ezfiles.reader.presentation.components.LocalReaderScrollActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.platform.pageFolderOf
import com.ezfiles.reader.presentation.PageFind
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.IOException

@Composable
internal actual fun HtmlPage(
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
    darkReading: Boolean,
) {
    val context = LocalContext.current
    val currentOnFind by rememberUpdatedState(onFind)
    val currentOnLink by rememberUpdatedState(onLink)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val currentResource by rememberUpdatedState(resource)
    val currentReaderScroll by rememberUpdatedState(LocalReaderScrollActivity.current)
    // The viewer keys this composable by opened document; a new folder grant makes a new page.
    key(folderVersion) {
    val holder = remember {
        val loader = when (kind) {
            PageKind.Pages -> DocumentPagesLoader(context.applicationContext) { currentResource(it) }
            PageKind.Web, PageKind.Preview -> FolderLoader(context.applicationContext, document)
            PageKind.Book -> BookLoader(context.applicationContext) { currentResource(it) }
        }
        LockedWebView.create(
            context = context,
            page = page,
            kind = kind,
            url = pageUrl(kind, document.displayName),
            loader = loader,
            onFind = { currentOnFind(it) },
            onLink = { url, refused -> currentOnLink(url, refused) },
            onGone = { currentOnFailure(HtmlPageFailure.Stopped) },
        )
    }
    if (holder == null) {
        LaunchedEffect(Unit) { currentOnFailure(HtmlPageFailure.Unavailable) }
    } else {
        DisposableEffect(holder) {
            holder.view.setOnScrollChangeListener { _, x, y, oldX, oldY ->
                if (x != oldX || y != oldY) currentReaderScroll()
            }
            onDispose { holder.view.setOnScrollChangeListener(null) }
        }
        LifecycleStartEffect(holder) {
            holder.resume()
            onStopOrDispose { holder.pause() }
        }
        LaunchedEffect(holder, query) { holder.find(query) }
        LaunchedEffect(holder, step) { if (step > 0) holder.findNext(forward) }
        LaunchedEffect(holder, darkReading) { holder.run(darkReadingScript(darkReading)) }
        AndroidView(factory = { holder.view }, modifier = modifier, onRelease = { holder.destroy() })
    }
    }
}

/** Answers requests to the page's own host; `null` means "not found". Called off the main thread. */
private fun interface PageLoader {
    fun load(path: String): ByteArray?
}

/** Most a single file may be, so a page can't make the app read a huge one into memory. */
private const val MAX_PAGE_FILE_BYTES = 64 * 1024 * 1024

/** Word pages: their pictures from the open document, their fonts from the app. */
private class DocumentPagesLoader(private val context: Context, private val resource: suspend (String) -> ByteArray?) : PageLoader {
    override fun load(path: String): ByteArray? = when {
        path.startsWith("doc/res/") -> runBlocking { resource(path.removePrefix("doc/res/")) }
        path.startsWith("doc/ezfonts/") -> appFont(context, path.removePrefix("doc/ezfonts/"))
        else -> null
    }
}

/** One of the fonts the app carries for documents, by file name. */
private fun appFont(context: Context, name: String): ByteArray? {
    if (name.contains('/') || name.startsWith('.')) return null
    return try {
        context.assets.open("doc-fonts/$name").use { it.readBytes() }
    } catch (_: IOException) {
        null
    }
}

/** An e-book: its own files from the open document, the reader's fonts from the app. */
private class BookLoader(private val context: Context, private val resource: suspend (String) -> ByteArray?) : PageLoader {
    override fun load(path: String): ByteArray? = when {
        path.startsWith("ezbook/") -> runBlocking { resource(BOOK_FILE + path.removePrefix("ezbook/")) }
        path.startsWith("ezbook-html/") -> runBlocking { resource(BOOK_FILE + path.removePrefix("ezbook-html/")) }
        path.startsWith("ezfonts/") -> appFont(context, path.removePrefix("ezfonts/"))
        else -> null
    }
}

/** The engine's name for a file inside an e-book (`epub_book::FILE_PREFIX`). */
private const val BOOK_FILE = "ezfiles:book/"

/** A page's own files, from its folder, when the app may read that folder. */
private class FolderLoader(context: Context, page: DocumentRef) : PageLoader {
    private val folder = pageFolderOf(context, page)

    override fun load(path: String): ByteArray? = folder?.read(path, MAX_PAGE_FILE_BYTES)
}

private fun mimeOf(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "js", "mjs", "cjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "html", "htm" -> "text/html"
        "wasm" -> "application/wasm"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }
}

/** A reply, honouring a byte range so video and audio can seek. */
private fun reply(path: String, bytes: ByteArray, range: String?): WebResourceResponse {
    // A book chapter that isn't well-formed XHTML is asked for again as HTML.
    val mime = if (path.startsWith("ezbook-html/")) "text/html" else mimeOf(path)
    val encoding = if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json")) "utf-8" else null
    val headers = mutableMapOf("Accept-Ranges" to "bytes", "Cache-Control" to "no-store", "Access-Control-Allow-Origin" to PAGE_ORIGIN.trimEnd('/'))
    if (path.startsWith("ezbook")) headers["Content-Security-Policy"] = BOOK_FILE_POLICY
    val match = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it.trim()) }
    if (match != null && bytes.isNotEmpty()) {
        val (from, to) = match.destructured
        val start = from.toLongOrNull() ?: (bytes.size - (to.toLongOrNull() ?: 0L)).coerceAtLeast(0L)
        val end = if (from.isEmpty()) bytes.size - 1L else (to.toLongOrNull() ?: (bytes.size - 1L)).coerceAtMost(bytes.size - 1L)
        if (start in 0..end) {
            headers["Content-Range"] = "bytes $start-$end/${bytes.size}"
            val slice = bytes.copyOfRange(start.toInt(), end.toInt() + 1)
            return WebResourceResponse(mime, encoding, 206, "Partial Content", headers, ByteArrayInputStream(slice))
        }
    }
    return WebResourceResponse(mime, encoding, 200, "OK", headers, ByteArrayInputStream(bytes))
}

private fun noContent() = WebResourceResponse("text/plain", "utf-8", 204, "No Content", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

private fun darkReadingScript(dark: Boolean) = if (dark) """
(function(){
  if(document.getElementById('ez-dark')) return;
  var s=document.createElement('style');
  s.id='ez-dark';
  s.textContent='html{filter:invert(1) hue-rotate(180deg);background:#1a1a1a !important}'
    +'img,video,picture,svg,canvas,[style*=background-image]{filter:invert(1) hue-rotate(180deg)}'
    +'.ez-no-invert{filter:invert(1) hue-rotate(180deg)}';
  document.head.appendChild(s);
})()
""".trimIndent() else """
(function(){
  var s=document.getElementById('ez-dark');
  if(s) s.remove();
})()
""".trimIndent()

/**
 * The WebView and its rules, in one place. Nothing reaches the network: the
 * page's host can't exist and the app answers it, any other request gets an
 * empty reply, network loads are switched off, and the app has no INTERNET
 * permission, which the system enforces on every socket. Scripts run only in
 * web pages (and in Word pages, only the engine's own, by nonce).
 */
private class LockedWebView private constructor(
    val view: WebView,
) {
    private var alive = true

    fun find(query: String) = ifAlive { if (query.isEmpty()) clearMatches() else findAllAsync(query) }

    fun findNext(forward: Boolean) = ifAlive { findNext(forward) }

    /** Audio the user started shouldn't go on playing behind another app. */
    fun pause() = ifAlive { onPause() }

    fun resume() = ifAlive { onResume() }

    fun run(script: String) = ifAlive { evaluateJavascript(script, null) }

    fun destroy() {
        if (!alive) return
        alive = false
        // WebView wants to be out of the hierarchy first, or it keeps resources.
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    private inline fun ifAlive(block: WebView.() -> Unit) {
        if (alive) view.block()
    }

    companion object {
        /** `null` if the system can't make one: WebView may be disabled, missing or mid-update. */
        fun create(
            context: Context,
            page: String,
            kind: PageKind,
            url: String,
            loader: PageLoader,
            onFind: (PageFind) -> Unit,
            onLink: (String, Boolean) -> Unit,
            onGone: () -> Unit,
            onMessage: (String) -> Unit = {},
        ): LockedWebView? = try {
            // Pages asking for a dark scheme follow this, not the app's window theme, so they keep their light colours.
            val light = ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar)
            val view = if (kind == PageKind.Book) BookWebView(light) else WebView(light)
            val holder = LockedWebView(view)
            view.settings.lockDown(kind)
            CookieManager.getInstance().apply {
                setAcceptCookie(false)
                setAcceptThirdPartyCookies(view, false)
            }
            view.setBackgroundColor(if (kind == PageKind.Pages) 0xFFE8E8EC.toInt() else Color.WHITE)
            // A book turns pages itself; the view mustn't scroll or show edge glows.
            view.overScrollMode =
                if (kind == PageKind.Book) View.OVER_SCROLL_NEVER else View.OVER_SCROLL_IF_CONTENT_SCROLLS
            if (kind == PageKind.Book) {
                view.isVerticalScrollBarEnabled = false
                view.isHorizontalScrollBarEnabled = false
            }
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            view.setFindListener { active, total, done -> onFind(PageFind(active, total, done)) }
            view.webViewClient = LockedClient(kind, url, loader, onLink, onMessage = { message -> view.post { onMessage(message) } }) {
                holder.destroy()
                onGone()
            }
            // Without one, `alert()` and friends hang the page's script.
            if (kind == PageKind.Web) view.webChromeClient = WebChromeClient()
            view.loadDataWithBaseURL(url, page, "text/html", "UTF-8", null)
            holder
        } catch (e: RuntimeException) {
            android.util.Log.w("EzFiles", "No web view", e)
            null
        }
    }
}

@SuppressLint("SetJavaScriptEnabled") // Scripts run in web pages, as a browser runs them; never with a bridge to the app.
@Suppress("DEPRECATION")
private fun WebSettings.lockDown(kind: PageKind) {
    javaScriptEnabled = kind != PageKind.Preview
    javaScriptCanOpenWindowsAutomatically = false
    allowFileAccess = false
    allowContentAccess = false
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
    // Required without the INTERNET permission. The page's own host is answered before it applies.
    blockNetworkLoads = true
    // Scripted pages often keep state there; it stays with this page's made-up origin.
    domStorageEnabled = kind == PageKind.Web
    databaseEnabled = false
    setGeolocationEnabled(false)
    mediaPlaybackRequiresUserGesture = true
    saveFormData = false
    savePassword = false
    safeBrowsingEnabled = false
    cacheMode = WebSettings.LOAD_NO_CACHE
    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    // Off, so a target="_blank" link comes to the client like any other instead of asking for a window.
    setSupportMultipleWindows(false)
    // Pinch zoom without the on-screen buttons; a book changes its text size instead.
    setSupportZoom(kind != PageKind.Book)
    builtInZoomControls = kind != PageKind.Book
    displayZoomControls = false
    useWideViewPort = kind != PageKind.Book
    loadWithOverviewMode = kind != PageKind.Book
    textZoom = 100
    // The page keeps its own colours.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        isAlgorithmicDarkeningAllowed = false
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        forceDark = WebSettings.FORCE_DARK_OFF
    }
}

/** Navigation stays on the page (or, for web pages, among pages beside it); requests get only the page's own files. */
private class LockedClient(
    private val kind: PageKind,
    private val url: String,
    private val loader: PageLoader,
    private val onLink: (String, Boolean) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onGone: () -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val link = request.url.toString()
        if (kind == PageKind.Book) {
            // The reader moves its own frame between the book's chapters; nothing else navigates.
            if (!request.isForMainFrame && link.startsWith("${PAGE_ORIGIN}ezbook")) return false
            if (request.isForMainFrame && request.hasGesture()) {
                when (htmlLinkDecision(link, userGesture = true)) {
                    HtmlLinkDecision.AskToOpen -> onLink(link, false)
                    HtmlLinkDecision.Refuse -> onLink(link, true)
                    else -> {}
                }
            }
            return true
        }
        val decision = htmlLinkDecision(link, request.hasGesture(), request.isForMainFrame, url, sameSiteNavigation = kind == PageKind.Web)
        return when (decision) {
            HtmlLinkDecision.SameDocument, HtmlLinkDecision.Navigate -> false
            HtmlLinkDecision.AskToOpen -> {
                onLink(link, false)
                true
            }
            HtmlLinkDecision.Refuse -> {
                onLink(link, true)
                true
            }
            HtmlLinkDecision.Ignore -> true
        }
    }

    /**
     * Runs off the main thread. `data:` passes because WebView asks here about those
     * too: they are already in the page, and an empty answer would blank its pictures.
     */
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val link = request.url.toString()
        if (htmlResourceAllowed(link)) return null
        val path = pageRequestPath(link) ?: return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        // The book reader's messages; the receiver checks the token each carries.
        if (kind == PageKind.Book && path == "ezmsg") {
            request.url.getQueryParameter("m")?.let(onMessage)
            return noContent()
        }
        // The page itself is already loaded; other pages beside it load as files.
        val bytes = try {
            loader.load(path)
        } catch (_: RuntimeException) {
            null
        } ?: return notFound()
        return reply(path, bytes, request.requestHeaders.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value)
    }

    /** Without handling this, losing the renderer would take the app down with it. */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onGone()
        return true
    }
}

@Composable
internal actual fun BookPage(
    page: String,
    resource: suspend (String) -> ByteArray?,
    textZoom: Int,
    background: Int,
    commands: BookCommands,
    query: String,
    step: Int,
    forward: Boolean,
    onFind: (PageFind) -> Unit,
    onMessage: (String) -> Unit,
    onFailure: (HtmlPageFailure) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val currentOnFind by rememberUpdatedState(onFind)
    val currentOnMessage by rememberUpdatedState(onMessage)
    val currentOnFailure by rememberUpdatedState(onFailure)
    val currentResource by rememberUpdatedState(resource)
    val holder = remember {
        LockedWebView.create(
            context = context,
            page = page,
            kind = PageKind.Book,
            url = pageUrl(PageKind.Book, ""),
            loader = BookLoader(context.applicationContext) { currentResource(it) },
            onFind = { currentOnFind(it) },
            // Links leave through the reader's own message, which asks first.
            onLink = { _, _ -> },
            onGone = { currentOnFailure(HtmlPageFailure.Stopped) },
            onMessage = { currentOnMessage(it) },
        )
    }
    if (holder == null) {
        LaunchedEffect(Unit) { currentOnFailure(HtmlPageFailure.Unavailable) }
        return
    }
    LifecycleStartEffect(holder) {
        holder.resume()
        onStopOrDispose { holder.pause() }
    }
    LaunchedEffect(holder) { commands.scripts.collect { holder.run(it) } }
    LaunchedEffect(holder, textZoom) { holder.view.settings.textZoom = textZoom }
    LaunchedEffect(holder, background) { holder.view.setBackgroundColor(background) }
    LaunchedEffect(holder, query) { holder.find(query) }
    LaunchedEffect(holder, step) { if (step > 0) holder.findNext(forward) }
    AndroidView(factory = { holder.view }, modifier = modifier, onRelease = { holder.destroy() })
}

/** A book's web view: choosing text offers Highlight and Note beside the usual Copy and Share. */
@SuppressLint("ViewConstructor")
private class BookWebView(context: Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(callback?.let { BookSelectionActions(it, this) }, type)

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(callback?.let { BookSelectionActions(it, this) })
}

private const val HIGHLIGHT_ITEM = 0x6268
private const val NOTE_ITEM = 0x626e

/** Wraps the web view's own selection menu, adding the reader's two actions first. */
private class BookSelectionActions(private val inner: ActionMode.Callback, private val view: WebView) : ActionMode.Callback2() {
    // Also in the menu, beside Copy; phones order and trim this menu their own way,
    // so the reader shows its own highlight bar while text is chosen as well.
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        val shown = inner.onCreateActionMode(mode, menu)
        menu.add(Menu.NONE, HIGHLIGHT_ITEM, 0, "Highlight")
        menu.add(Menu.NONE, NOTE_ITEM, 1, "Note")
        return shown
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = inner.onPrepareActionMode(mode, menu)

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = when (item.itemId) {
        HIGHLIGHT_ITEM, NOTE_ITEM -> {
            // The page remembers the chosen text, so it doesn't matter that closing the menu clears it.
            view.evaluateJavascript(highlightSelectionScript(withNote = item.itemId == NOTE_ITEM), null)
            mode.finish()
            true
        }
        else -> inner.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode) = inner.onDestroyActionMode(mode)

    override fun onGetContentRect(mode: ActionMode, view: android.view.View, outRect: Rect) {
        if (inner is ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
    }
}
