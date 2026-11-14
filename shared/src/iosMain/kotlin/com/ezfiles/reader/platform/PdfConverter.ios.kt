package com.ezfiles.reader.platform

import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException

/** iOS is parked: this keeps the shared module portable until it resumes. */
internal object IosPdfConverter : PdfConverter {
    private fun parked() = DocumentException(DocumentError.Unsupported("This isn't available on iOS yet."))

    override suspend fun encodeAndRelease(image: ImageBitmap, format: PageImageFormat): ByteArray = throw parked()

    override suspend fun newZip(): PdfZipWriter = throw parked()
}
