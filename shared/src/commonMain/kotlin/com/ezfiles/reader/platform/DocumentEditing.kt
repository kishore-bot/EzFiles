package com.ezfiles.reader.platform

import androidx.compose.runtime.staticCompositionLocalOf

data class ReplaceResult(val matchCount: Int, val outputBytes: ByteArray)

interface DocumentEditor {
    suspend fun replaceAll(
        sourceUri: String,
        displayName: String,
        find: String,
        replace: String,
        caseSensitive: Boolean,
    ): ReplaceResult

    suspend fun pptxSlideCount(sourceUri: String, displayName: String): Int

    suspend fun pptxReorderSlides(
        sourceUri: String,
        displayName: String,
        keepIndices: List<Int>,
    ): ByteArray

    suspend fun xlsxEditCells(
        sourceUri: String,
        displayName: String,
        sheetIndex: Int,
        edits: Map<Pair<Int, Int>, String>,
    ): ByteArray
}

val LocalDocumentEditor = staticCompositionLocalOf<DocumentEditor?> { null }
