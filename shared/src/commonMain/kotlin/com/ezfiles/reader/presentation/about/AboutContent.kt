package com.ezfiles.reader.presentation.about

import com.ezfiles.reader.presentation.library.FileType

/*
 * The words of the About and Why EzFiles screens, kept apart from the layout
 * so they are easy to edit.
 */

/** One piece of the Why EzFiles text, in reading order. */
sealed interface VisionBlock {
    data class Title(val text: String) : VisionBlock

    data class Paragraph(val text: String) : VisionBlock

    /** A bold line, shown as a pull quote. */
    data class PullQuote(val text: String) : VisionBlock

    data class Beliefs(val items: List<String>) : VisionBlock

    /** The italic closing line. */
    data class SignOff(val text: String) : VisionBlock
}

/** The owner's words, exactly as written. Change the layout, not the text, unless the owner asks. */
val VisionText: List<VisionBlock> = listOf(
    VisionBlock.Title("Why EzFiles Exists"),
    VisionBlock.PullQuote("Free doesn't mean cheap."),
    VisionBlock.Paragraph("Today, even the simplest applications often come with advertisements, subscriptions, and unnecessary restrictions."),
    VisionBlock.Paragraph("Technology has given us extraordinary possibilities, yet access to useful tools increasingly comes with a price."),
    VisionBlock.Paragraph("As artificial intelligence transforms our world, the future of work and the economy remains uncertain."),
    VisionBlock.Paragraph("I believe technology should help people grow, not create more barriers for them."),
    VisionBlock.Paragraph("That's why I started EzFiles."),
    VisionBlock.Paragraph("Not to build another subscription service. Not to compete for people's attention through advertisements."),
    VisionBlock.Paragraph("But to create something useful, accessible, and respectful of its users."),
    VisionBlock.PullQuote("I believe an idea shouldn't end with the person who created it."),
    VisionBlock.Paragraph("When we share our knowledge, tools, and ideas, someone else might take them further than we ever imagined."),
    VisionBlock.Paragraph("One person's small contribution could become the foundation for something extraordinary."),
    VisionBlock.Paragraph("EzFiles is just a file reader today. It won't change the world on its own."),
    VisionBlock.Paragraph("But perhaps it can be one small example of a different approach to building software."),
    VisionBlock.Paragraph("Because I believe:"),
    VisionBlock.Beliefs(
        listOf(
            "Free software can be high-quality software.",
            "Privacy should be a principle, not a premium feature.",
            "Knowledge becomes more powerful when shared.",
            "Technology should empower people rather than exploit them.",
            "Progress should benefit humanity and the world we share.",
        ),
    ),
    VisionBlock.PullQuote("We don't all have to change the world. But we can each contribute something that helps it move forward."),
    VisionBlock.Paragraph("That's the vision behind EzFiles."),
    VisionBlock.SignOff("Built for people. Shared with everyone."),
)

const val TAGLINE = "Built for people. Shared with everyone."

/** A link on a team card; opened in the system browser or mail app, never inside EzFiles. */
data class TeamLink(val label: String, val url: String)

data class TeamMember(val name: String, val role: String, val bio: String = "", val links: List<TeamLink> = emptyList())

/**
 * The team cards on the About screen, in order.
 * Contact details supplied by the team; roles and biographies are omitted until supplied.
 */
val Team: List<TeamMember> = listOf(
    TeamMember(
        name = "KISHORE K", role = "",
        links = listOf(
            TeamLink("kishoreabhi333@gmail.com", "mailto:kishoreabhi333@gmail.com"),
            TeamLink("LinkedIn", "https://www.linkedin.com/in/kishorek1"),
        ),
    ),
    TeamMember(
        name = "Robin Isac", role = "",
        links = listOf(
            TeamLink("robinisacsharon@gmail.com", "mailto:robinisacsharon@gmail.com"),
            TeamLink("LinkedIn", "https://www.linkedin.com/in/robin-isac-07ba01329"),
        ),
    ),
)

/** Up to two initials for an avatar: the first letters of the first and last words. */
fun initialsOf(name: String): String {
    val words = name.split(' ', '-', '\t').map { it.filter(Char::isLetterOrDigit) }.filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/**
 * One group of supported files on the About page: its badge, extensions and,
 * in plain words, what EzFiles does with it. Keep in step with
 * docs/format-support-matrix.md.
 */
data class SupportedGroup(val title: String, val badge: FileType, val extensions: String, val summary: String)

val SupportedFiles: List<SupportedGroup> = listOf(
    SupportedGroup("PDF", FileType.Pdf, "pdf", "Pages, zoom, search, highlight, notes, signatures and PDF tools"),
    SupportedGroup("Word", FileType.Docx, "docx, docm, dotx, dotm", "Pages as designed, with their fonts, tables and pictures"),
    SupportedGroup("OpenDocument text", FileType.Odt, "odt, ott", "Pages as designed"),
    SupportedGroup("Older Word", FileType.Doc, "doc", "The text"),
    SupportedGroup("PowerPoint", FileType.Pptx, "pptx, pptm, ppsx, potx", "Slides as designed, notes and a presenter view"),
    SupportedGroup("OpenDocument slides", FileType.Odp, "odp", "Slides as designed, with notes"),
    SupportedGroup("Older PowerPoint", FileType.Ppt, "ppt", "Slides with their shapes, colours and pictures"),
    SupportedGroup("Excel", FileType.Xlsx, "xlsx, xlsm, xltx", "Sheets with their formatting, as last saved"),
    SupportedGroup("Other spreadsheets", FileType.Ods, "xls, ods, csv, tsv", "Sheets and tables"),
    SupportedGroup("E-books", FileType.Epub, "epub", "Like an e-reader, with highlights, notes and bookmarks"),
    SupportedGroup("Web pages and Markdown", FileType.Html, "html, htm, md", "Rendered like a browser, with the source too"),
    SupportedGroup("Text and logs", FileType.Txt, "txt, log and other text", "Read, search and edit"),
    SupportedGroup("Code and data", FileType.Code, "json, xml, yaml, kt, py, js and more", "Line numbers and search"),
    SupportedGroup("Images", FileType.Image, "jpg, png, webp, gif, bmp, heic", "Zoom, pan and rotate"),
    SupportedGroup("Audio and video", FileType.Video, "mp4, webm, mkv, mp3, m4a, wav, ogg, flac", "Played with the phone's own player"),
    SupportedGroup("ZIP archives", FileType.Zip, "zip", "A list of what's inside"),
)

data class Licence(val name: String, val licence: String)

/** The main components in the app, from the build files; each one's own notice carries the details. */
val Licences: List<Licence> = listOf(
    Licence("Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, kotlinx.collections.immutable, atomicfu", "Apache License 2.0"),
    Licence("Compose Multiplatform (runtime, foundation, UI, Material 3)", "Apache License 2.0"),
    Licence("AndroidX Activity, Lifecycle, CameraX and ProfileInstaller", "Apache License 2.0"),
    Licence("Material Symbols icons", "Apache License 2.0"),
    Licence("UniFFI", "Mozilla Public License 2.0"),
    Licence("JNA (Java Native Access)", "Apache License 2.0 or LGPL 2.1"),
    Licence("quick-xml", "MIT License"),
    Licence("pulldown-cmark", "MIT License"),
    Licence("flate2, miniz_oxide and crc32fast", "MIT or Apache License 2.0"),
    Licence("RustCrypto aes, cbc, sha1, sha2, md-5 and rc4", "MIT or Apache License 2.0"),
    Licence("getrandom and zeroize", "MIT or Apache License 2.0"),
    Licence("Carlito, Caladea, Arimo, Tinos and Cousine fonts", "SIL Open Font License 1.1"),
    Licence("AndroidX Media3 (ExoPlayer and its FFmpeg extension bridge)", "Apache License 2.0"),
    Licence(
        "FFmpeg 7.1.2 audio decoders (libavcodec, libavutil, libswresample), built unmodified as separate shared libraries " +
            "that can be replaced; source: ffmpeg.org/releases/ffmpeg-7.1.2.tar.xz, build script: scripts/build-ffmpeg.sh",
        "GNU Lesser General Public License 2.1 or later",
    ),
    Licence("llama.cpp and ggml (on-device assistant engine)", "MIT License"),
    Licence("Fily model, fine-tuned from Liquid AI LFM2.5-350M", "LFM Open License 1.0"),
    Licence("Google ML Kit Text Recognition (on-device model)", "ML Kit Terms of Service (not open source)"),
    Licence("Firebase Crashlytics (optional Android diagnostics build)", "Apache License 2.0; Firebase service terms apply"),
)
