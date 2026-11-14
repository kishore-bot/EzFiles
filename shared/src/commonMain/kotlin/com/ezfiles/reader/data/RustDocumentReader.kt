package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.EngineSession
import com.ezfiles.engine.ffi.InternalException
import com.ezfiles.engine.ffi.NativeBatch
import com.ezfiles.engine.ffi.NativeBlock
import com.ezfiles.engine.ffi.NativeCellStyle
import com.ezfiles.engine.ffi.NativeDocumentInfo
import com.ezfiles.engine.ffi.NativeDocumentKind
import com.ezfiles.engine.ffi.NativeFormat
import com.ezfiles.engine.ffi.NativeLimits
import com.ezfiles.engine.ffi.NativeShape
import com.ezfiles.engine.ffi.NativeGeometry
import com.ezfiles.engine.ffi.NativeSpan
import com.ezfiles.engine.ffi.ReadException
import com.ezfiles.engine.ffi.openFd
import com.ezfiles.engine.ffi.openPath
import com.ezfiles.reader.domain.CellAlign
import com.ezfiles.reader.domain.CellLook
import com.ezfiles.reader.domain.ContentBatch
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentInfo
import com.ezfiles.reader.domain.DocumentKind
import com.ezfiles.reader.domain.DocumentReader
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentSession
import com.ezfiles.reader.domain.ReadLimits
import com.ezfiles.reader.domain.SlideShape
import com.ezfiles.reader.domain.ShapeParagraph
import com.ezfiles.reader.domain.ShapeGeometry
import com.ezfiles.reader.domain.ShapeBuild
import com.ezfiles.reader.domain.TextSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * [DocumentReader] backed by the Rust engine through the Gobley-generated
 * UniFFI bindings. Every engine call is blocking and runs on [io]; this class
 * is the only place that maps between engine and domain types.
 */
internal class RustDocumentReader(
    private val access: DocumentAccess,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DocumentReader {

    /**
     * Loads the engine's native library now, off the main thread, so the
     * first document doesn't wait for it (most of a second on a cold start).
     * Asking it to open no file is the cheapest call that loads it.
     */
    fun warmUp(scope: CoroutineScope) {
        scope.launch(io) {
            try {
                openPath("", null, ReadLimits.Default.toNative(), null).close()
            } catch (_: Throwable) {
                // Expected: there is no such file. The library is loaded either way.
            }
        }
    }

    override suspend fun open(
        ref: DocumentRef,
        limits: ReadLimits,
        asSource: Boolean,
        password: String?,
    ): DocumentSession {
        var native: EngineSession? = null
        try {
            return withContext(io) {
                // The engine consumes the descriptor even when it refuses a password,
                // so every try gets a new one from the ref.
                val fd = access.openDescriptor(ref, limits)
                // The name only selects how text is presented; without it, text is plain lines.
                val nameHint = if (asSource) sourceNameHint(ref.displayName) else ref.displayName
                native = engineCall { openFd(fd, nameHint, limits.toNative(), password) }
                ensureActive()
                wrap(checkNotNull(native), ref.displayName, io)
            }
        } catch (e: Throwable) {
            // Covers cancellation after the engine call returned, whose result
            // withContext would otherwise drop, leaking the session.
            native?.close()
            throw e
        }
    }

    companion object {
        /** Asks the engine for line-numbered code: HTML has no other way to say it wants no rendering. */
        internal const val HTML_SOURCE_HINT = "source.html-source"

        /** Markdown as line-numbered, coloured source. */
        internal const val MARKDOWN_SOURCE_HINT = "source.md-source"

        /** Plain text as streamed, line-numbered source. */
        internal const val TEXT_SOURCE_HINT = "source.txt-source"

        /** What the engine is told to show the raw text of [name], in place of its rendered view. */
        private fun sourceNameHint(name: String): String? =
            when (name.substringAfterLast('.', "").lowercase()) {
                "html", "htm", "xhtml" -> HTML_SOURCE_HINT
                "md", "markdown", "mdown", "mkd", "mdx" -> MARKDOWN_SOURCE_HINT
                "txt", "text" -> TEXT_SOURCE_HINT
                else -> null
            }

        /** Adopts an open engine session; closes it if it cannot be mapped. */
        fun wrap(native: EngineSession, name: String, io: CoroutineDispatcher): DocumentSession {
            val info = try {
                native.info().toDomain(name)
            } catch (e: Throwable) {
                native.close()
                throw e
            }
            return RustDocumentSession(native, info, io)
        }
    }
}

private class RustDocumentSession(
    private val native: EngineSession,
    override val info: DocumentInfo,
    private val io: CoroutineDispatcher,
) : DocumentSession {

    override suspend fun nextBatch(): ContentBatch? = withContext(io) {
        val batch = engineCall { native.nextBatch() }
        ensureActive()
        // Mapping styled blocks can allocate many spans/shapes; keep it off the UI caller too.
        batch?.toDomain()
    }

    override fun cancel() = native.cancel()

    override suspend fun resource(name: String): ByteArray? = withContext(io) {
        try {
            engineCall { native.resource(name) }
        } catch (e: DocumentException) {
            null
        }
    }

    override fun close() = native.close()
}

private inline fun <T> engineCall(block: () -> T): T =
    try {
        block()
    } catch (e: ReadException) {
        throw e.toDomain()
    } catch (e: InternalException) {
        // A Rust panic caught by UniFFI.
        throw DocumentException(DocumentError.Internal(e.message ?: "engine failure"))
    }

private fun ReadException.toDomain(): Exception = when (this) {
    is ReadException.Io -> DocumentException(DocumentError.FileAccess(detail))
    is ReadException.UnsupportedFormat -> DocumentException(DocumentError.Unsupported(detail))
    is ReadException.Malformed -> DocumentException(DocumentError.Malformed(detail))
    is ReadException.LimitExceeded -> DocumentException(DocumentError.LimitExceeded(detail))
    is ReadException.PasswordRequired -> DocumentException(DocumentError.PasswordRequired(detail))
    is ReadException.WrongPassword -> DocumentException(DocumentError.WrongPassword(detail))
    is ReadException.UnsupportedEncryption -> DocumentException(DocumentError.UnsupportedEncryption(detail))
    is ReadException.Internal -> DocumentException(DocumentError.Internal(detail))
    // Only produced after DocumentSession.cancel(), which callers invoke when
    // the coroutine is being cancelled anyway.
    is ReadException.Cancelled -> CancellationException(detail)
}

private fun ReadLimits.toNative() = NativeLimits(
    maxInputBytes = maxInputBytes.toULong(),
    maxBatchBytes = maxBatchBytes.toULong(),
    maxTotalOutputBytes = maxTotalOutputBytes.toULong(),
)

private fun NativeDocumentInfo.toDomain(name: String) = DocumentInfo(
    name = name,
    format = when (format) {
        NativeFormat.PLAIN_TEXT -> DocumentFormat.PlainText
        NativeFormat.MARKDOWN -> DocumentFormat.Markdown
        NativeFormat.CSV -> DocumentFormat.Csv
        NativeFormat.JSON -> DocumentFormat.Json
        NativeFormat.SOURCE_CODE -> DocumentFormat.SourceCode
        NativeFormat.HTML -> DocumentFormat.Html
        NativeFormat.PDF -> DocumentFormat.Pdf
        NativeFormat.DOCX -> DocumentFormat.Docx
        NativeFormat.XLSX -> DocumentFormat.Xlsx
        NativeFormat.PPTX -> DocumentFormat.Pptx
        NativeFormat.ODT -> DocumentFormat.Odt
        NativeFormat.ODS -> DocumentFormat.Ods
        NativeFormat.ODP -> DocumentFormat.Odp
        NativeFormat.EPUB -> DocumentFormat.Epub
        NativeFormat.DOC -> DocumentFormat.Doc
        NativeFormat.XLS -> DocumentFormat.Xls
        NativeFormat.PPT -> DocumentFormat.Ppt
        NativeFormat.ZIP -> DocumentFormat.Archive
        NativeFormat.IMAGE -> DocumentFormat.Image
        // Detection-only formats: the engine refines or refuses them before opening.
        NativeFormat.OLE_COMPOUND, NativeFormat.UNKNOWN ->
            throw DocumentException(DocumentError.Internal("engine opened unexpected format $format"))
    },
    kind = when (kind) {
        NativeDocumentKind.TEXT -> DocumentKind.Text
        NativeDocumentKind.SPREADSHEET -> DocumentKind.Spreadsheet
        NativeDocumentKind.PRESENTATION -> DocumentKind.Presentation
        NativeDocumentKind.PDF -> DocumentKind.Pdf
        NativeDocumentKind.ARCHIVE -> DocumentKind.Archive
        NativeDocumentKind.IMAGE -> DocumentKind.Image
    },
    byteSize = byteSize.toLong(),
    encoding = encoding,
)

private fun NativeBatch.toDomain() = ContentBatch(
    blocks = blocks.map { block ->
        when (block) {
            is NativeBlock.Heading -> ContentBlock.Heading(block.level.toInt(), block.text, block.spans.toDomain())
            is NativeBlock.Paragraph -> ContentBlock.Paragraph(block.text, block.spans.toDomain())
            is NativeBlock.ListItem ->
                ContentBlock.ListItem(block.depth.toInt(), block.text, block.marker, block.spans.toDomain())
            is NativeBlock.Quote -> ContentBlock.Quote(block.text, block.spans.toDomain())
            is NativeBlock.Code -> ContentBlock.Code(block.text, block.language, block.firstLine?.toInt())
            NativeBlock.Divider -> ContentBlock.Divider
            is NativeBlock.TableRow ->
                ContentBlock.TableRow(block.row?.toInt(), block.cells, block.header, block.styles.map { it.toInt() })
            is NativeBlock.CellStyle -> ContentBlock.CellStyle(block.index.toInt(), block.style.toDomain())
            is NativeBlock.Merge -> ContentBlock.Merge(
                block.firstRow.toInt(),
                block.firstColumn.toInt(),
                block.lastRow.toInt(),
                block.lastColumn.toInt(),
            )
            is NativeBlock.CellRuns ->
                ContentBlock.CellRuns(block.row.toInt(), block.column.toInt(), block.spans.toDomain())
            is NativeBlock.Count -> ContentBlock.Count(block.total.toInt())
            is NativeBlock.Section -> ContentBlock.Section(block.title)
            is NativeBlock.Entry -> ContentBlock.Entry(block.path, block.size.toLong(), block.isDir)
            is NativeBlock.Image -> ContentBlock.Image(block.path, block.alt, block.width, block.height)
            is NativeBlock.Slide -> ContentBlock.Slide(
                block.width,
                block.height,
                block.background?.toInt(),
                block.backgroundImage,
                block.transition,
            )
            is NativeBlock.Shape -> ContentBlock.Shape(block.shape.toDomain())
        }
    },
    isLast = isLast,
    truncated = truncated,
)

private fun NativeCellStyle.toDomain(): CellLook {
    val style = flags.toInt()
    return CellLook(
        bold = style and 1 != 0,
        italic = style and 2 != 0,
        underline = style and 4 != 0,
        strikethrough = style and 8 != 0,
        color = color?.toInt(),
        fill = fill?.toInt(),
        size = size,
        align = when (align.toInt()) {
            1 -> CellAlign.Left
            2 -> CellAlign.Center
            3 -> CellAlign.Right
            else -> CellAlign.Auto
        },
        wrap = wrap,
    )
}

private fun List<NativeSpan>.toDomain(): List<TextSpan> = map { span ->
    val style = span.style.toInt()
    TextSpan(
        start = span.start.toInt(),
        end = span.end.toInt(),
        bold = style and 1 != 0,
        italic = style and 2 != 0,
        underline = style and 4 != 0,
        strikethrough = style and 8 != 0,
        code = style and 16 != 0,
        link = span.link,
        color = span.color?.toInt(),
    )
}

private fun NativeShape.toDomain() = SlideShape(
    x = x,
    y = y,
    width = width,
    height = height,
    rotation = rotation,
    geometry = when (geometry) {
        NativeGeometry.RECT -> ShapeGeometry.Rect
        NativeGeometry.ROUND_RECT -> ShapeGeometry.RoundRect
        NativeGeometry.ELLIPSE -> ShapeGeometry.Ellipse
        NativeGeometry.LINE -> ShapeGeometry.Line
    },
    fill = fill?.toInt(),
    line = line?.toInt(),
    lineWidth = lineWidth,
    image = image,
    paragraphs = paragraphs.map { p ->
        ShapeParagraph(
            text = p.text,
            spans = p.spans.toDomain(),
            size = p.size,
            color = p.color?.toInt(),
            bold = p.bold,
            italic = p.italic,
            align = p.align.toInt(),
            bullet = p.bullet,
            level = p.level.toInt(),
        )
    },
    anchor = anchor.toInt(),
    insets = insets,
    table = table,
    tableHeader = tableHeader,
    build = build?.let { ShapeBuild(it.step.toInt(), it.effect.toInt()) },
    media = media,
)
