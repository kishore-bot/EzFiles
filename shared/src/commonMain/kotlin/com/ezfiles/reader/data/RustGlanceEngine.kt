package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.NativeGlanceKind
import com.ezfiles.engine.ffi.glanceText
import com.ezfiles.reader.application.GlanceEngine
import com.ezfiles.reader.domain.DocumentGlance
import com.ezfiles.reader.domain.GlanceItem
import com.ezfiles.reader.domain.GlanceKind

/** [GlanceEngine] over the Gobley-generated bindings to `document-ffi`'s glance module. */
internal object RustGlanceEngine : GlanceEngine {
    override fun glance(text: String): DocumentGlance {
        val g = glanceText(text)
        return DocumentGlance(
            keySentences = g.keySentences,
            keywords = g.keywords,
            items = g.items.map { GlanceItem(it.kind.toDomain(), it.text, it.value, it.label) },
            words = g.words.toInt(),
            readingMinutes = g.readingMinutes.toInt(),
            language = g.language,
        )
    }

    private fun NativeGlanceKind.toDomain() = when (this) {
        NativeGlanceKind.DATE -> GlanceKind.Date
        NativeGlanceKind.AMOUNT -> GlanceKind.Amount
        NativeGlanceKind.PHONE -> GlanceKind.Phone
        NativeGlanceKind.EMAIL -> GlanceKind.Email
        NativeGlanceKind.LINK -> GlanceKind.Link
        NativeGlanceKind.REFERENCE -> GlanceKind.Reference
    }
}
