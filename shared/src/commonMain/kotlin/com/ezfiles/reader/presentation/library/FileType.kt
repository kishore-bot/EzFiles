package com.ezfiles.reader.presentation.library

import androidx.compose.ui.graphics.Color
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.SourceCodeExtensions
import com.ezfiles.reader.domain.SourceCodeFileNames
import com.ezfiles.reader.presentation.theme.EzBadgeColors

/** Library filter chips, in display order. */
enum class FileFilter(val label: String) {
    All("All"),
    Recent("Recent"),
    Pdf("PDF"),
    Docs("Docs"),
    Slides("Slides"),
    Sheets("Sheets"),
    Books("Books"),
    Images("Images"),
    Media("Media"),
    Text("Text"),
    Code("Code"),
    Data("Data"),
    Archives("Archives"),
}

/**
 * How a file is shown in the library: tile label, colours and filter groups.
 * Plain-text formats are told apart by extension, because the engine reads
 * all of them as text.
 */
enum class FileType(val label: String, colors: Pair<Color, Color>, val filters: Set<FileFilter>) {
    Pdf("PDF", EzBadgeColors.Red, setOf(FileFilter.Pdf)),
    Docx("DOCX", EzBadgeColors.Blue, setOf(FileFilter.Docs)),
    Doc("DOC", EzBadgeColors.Blue, setOf(FileFilter.Docs)),
    Odt("ODT", EzBadgeColors.Blue, setOf(FileFilter.Docs)),
    Pptx("PPTX", EzBadgeColors.Orange, setOf(FileFilter.Slides)),
    Ppt("PPT", EzBadgeColors.Orange, setOf(FileFilter.Slides)),
    Odp("ODP", EzBadgeColors.Orange, setOf(FileFilter.Slides)),
    Image("IMG", EzBadgeColors.Teal, setOf(FileFilter.Images)),
    Epub("EPUB", EzBadgeColors.Violet, setOf(FileFilter.Books)),
    Xlsx("XLSX", EzBadgeColors.Green, setOf(FileFilter.Sheets)),
    Xls("XLS", EzBadgeColors.Green, setOf(FileFilter.Sheets)),
    Ods("ODS", EzBadgeColors.Green, setOf(FileFilter.Sheets)),
    Csv("CSV", EzBadgeColors.Green, setOf(FileFilter.Sheets, FileFilter.Data)),
    Json("JSON", EzBadgeColors.Purple, setOf(FileFilter.Data)),
    Xml("XML", EzBadgeColors.Orange, setOf(FileFilter.Data, FileFilter.Code)),
    Zip("ZIP", EzBadgeColors.Steel, setOf(FileFilter.Archives)),
    Code("CODE", EzBadgeColors.Indigo, setOf(FileFilter.Code)),
    Html("HTML", EzBadgeColors.Orange, setOf(FileFilter.Text)),
    Markdown("MD", EzBadgeColors.Slate, setOf(FileFilter.Text)),
    Log("LOG", EzBadgeColors.Grey, setOf(FileFilter.Text)),
    Txt("TXT", EzBadgeColors.Grey, setOf(FileFilter.Text)),
    Video("VIDEO", EzBadgeColors.Plum, setOf(FileFilter.Media)),
    Audio("AUDIO", EzBadgeColors.Pink, setOf(FileFilter.Media)),
    Other("FILE", EzBadgeColors.Grey, emptySet());

    /** The badge gradient, top to bottom. */
    val top: Color = colors.first
    val bottom: Color = colors.second

    companion object {
        fun of(name: String, format: DocumentFormat?): FileType {
            val byExtension = when (name.substringAfterLast('.', "").lowercase()) {
                "pdf" -> Pdf
                "docx", "docm", "dotx", "dotm" -> Docx
                "doc", "dot" -> Doc
                "odt", "ott" -> Odt
                "pptx", "pptm", "ppsx", "ppsm", "potx", "potm" -> Pptx
                "ppt", "pps", "pot" -> Ppt
                "odp", "otp" -> Odp
                "epub" -> Epub
                "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif" -> Image
                "xlsx", "xlsm", "xltx", "xltm" -> Xlsx
                "xls", "xlt" -> Xls
                "ods", "ots" -> Ods
                "csv", "tsv" -> Csv
                "json", "geojson" -> Json
                "xml", "svg", "plist" -> Xml
                "zip" -> Zip
                "html", "htm", "xhtml" -> Html
                "md", "markdown" -> Markdown
                "log" -> Log
                "txt", "text" -> Txt
                "mp4", "m4v", "mov", "3gp", "3g2", "webm", "mkv" -> Video
                "mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "mid", "midi" -> Audio
                in SourceCodeExtensions -> Code
                else -> if (name.lowercase() in SourceCodeFileNames) Code else null
            }
            return byExtension ?: when (format) {
                DocumentFormat.Pdf -> Pdf
                DocumentFormat.Docx -> Docx
                DocumentFormat.Xlsx -> Xlsx
                DocumentFormat.Pptx -> Pptx
                DocumentFormat.Odt -> Odt
                DocumentFormat.Ods -> Ods
                DocumentFormat.Odp -> Odp
                DocumentFormat.Epub -> Epub
                DocumentFormat.Doc -> Doc
                DocumentFormat.Xls -> Xls
                DocumentFormat.Ppt -> Ppt
                DocumentFormat.Archive -> Zip
                DocumentFormat.Image -> Image
                DocumentFormat.PlainText -> Txt
                DocumentFormat.Markdown -> Markdown
                DocumentFormat.Csv -> Csv
                DocumentFormat.Json -> Json
                DocumentFormat.SourceCode -> Code
                DocumentFormat.Html -> Html
                null -> Other
            }
        }
    }
}
