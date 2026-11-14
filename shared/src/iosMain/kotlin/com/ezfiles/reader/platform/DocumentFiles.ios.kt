package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.PdfCreator
import com.ezfiles.reader.domain.PdfImageQuality
import com.ezfiles.reader.domain.PdfPageImage
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.domain.PdfTextLine
import com.ezfiles.reader.domain.TextPagePreview
import com.ezfiles.reader.domain.TextPdfStyle

// iOS is parked: these keep the shared module portable until it resumes.
private fun parked() = DocumentException(DocumentError.Unsupported("This isn't available on iOS yet."))

internal object IosDocumentFiles : DocumentFiles {
    override suspend fun read(uri: String): ByteArray = throw parked()

    override suspend fun write(uri: String, bytes: ByteArray) = throw parked()

    override suspend fun canWrite(uri: String) = false

    override suspend fun refOf(uri: String): DocumentRef = throw parked()
}

internal object IosPdfCreator : PdfCreator {
    override suspend fun imagesToPdf(
        pages: List<PdfPageImage>,
        size: PdfPageSize,
        marginPt: Float,
        target: String,
        textLayers: List<List<PdfTextLine>>,
        quality: PdfImageQuality,
        onProgress: (done: Int, total: Int) -> Unit,
    ) = throw parked()

    override suspend fun imageThumbnail(uri: String, maxSidePx: Int): ByteArray = throw parked()

    override suspend fun textToPdf(
        text: String,
        style: TextPdfStyle,
        target: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int = throw parked()

    override suspend fun textToPng(text: String, style: TextPdfStyle, target: String): Int = throw parked()

    override suspend fun textPreview(text: String, style: TextPdfStyle, pageIndex: Int, widthPx: Int): TextPagePreview = throw parked()
}

@Composable
actual fun rememberDocumentSaver(onResult: (String?) -> Unit): DocumentSaver =
    remember { DocumentSaver { _, _ -> onResult(null) } }

@Composable
actual fun rememberImagePicker(multiple: Boolean, onResult: (List<String>) -> Unit): ImagePicker =
    remember { ImagePicker { onResult(emptyList()) } }
