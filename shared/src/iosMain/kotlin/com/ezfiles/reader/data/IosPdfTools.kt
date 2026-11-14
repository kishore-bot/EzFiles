package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.PdfFileInfo
import com.ezfiles.reader.domain.PdfPageEdit
import com.ezfiles.reader.domain.PdfPageSize
import com.ezfiles.reader.domain.PdfSource
import com.ezfiles.reader.domain.PdfTools

// iOS is parked: this keeps the shared module portable until it resumes.
internal object IosPdfTools : PdfTools {
    private fun unavailable() = DocumentException(DocumentError.Unsupported("This isn't available on iOS yet."))

    override suspend fun inspect(source: PdfSource): PdfFileInfo = throw unavailable()

    override suspend fun merge(sources: List<PdfSource>, target: String, onProgress: (Int, Int) -> Unit) = throw unavailable()

    override suspend fun rebuild(source: PdfSource, pages: List<PdfPageEdit>, target: String, onProgress: (Int, Int) -> Unit) = throw unavailable()

    override suspend fun resizePages(source: PdfSource, size: PdfPageSize, target: String, onProgress: (Int, Int) -> Unit) = throw unavailable()

    override suspend fun insertImages(source: PdfSource, images: List<String>, atPage: Int, target: String, onProgress: (Int, Int) -> Unit) = throw unavailable()

    override suspend fun addPassword(source: PdfSource, password: String, target: String, onProgress: (Int, Int) -> Unit) = throw unavailable()

    override suspend fun removePassword(source: PdfSource, target: String, onProgress: (Int, Int) -> Unit) = throw unavailable()
}
