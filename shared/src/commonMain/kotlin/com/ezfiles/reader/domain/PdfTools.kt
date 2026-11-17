package com.ezfiles.reader.domain

/** A PDF to read, with its password if it has one. The password is held only for the call. */
data class PdfSource(val uri: String, val password: String? = null) {
    override fun toString() = "PdfSource($uri)"
}

/**
 * What a tool needs to know before it starts. [PdfTools.inspect] only returns
 * this once the file is open: a file that needs a password and has none throws
 * [DocumentError.PasswordRequired] instead.
 */
data class PdfFileInfo(
    val pageCount: Int,
    /**
     * True for any encrypted file that opened. With no password given that means
     * it is owner-restricted: it opens for anyone, but it sets permission limits,
     * so changing or unprotecting it needs the owner password.
     */
    val encrypted: Boolean,
    /** Changing it invalidates a digital signature, so the UI warns first. */
    val signed: Boolean,
    /** PDF/A: changing it can break archival conformance, so the UI warns first. */
    val archival: Boolean,
    /** False only when the file forbids printing to someone without the owner password. */
    val printable: Boolean = true,
)

/** One page of a rebuilt document: [sourcePage] is zero-based; extra clockwise rotation in multiples of 90. */
data class PdfPageEdit(val sourcePage: Int, val rotationDegrees: Int = 0)

/**
 * Whole-document PDF operations. Each writes a new file to [target], a URI from
 * [com.ezfiles.reader.platform.DocumentSaver], and never changes its sources.
 * Nothing touches the target until the output is complete and verified.
 * [onProgress] gets (done, total) from a background thread. Cancelling the
 * coroutine stops the work and leaves the target as it was.
 *
 * Every output is a fresh file. When all pages of one document stay (reorder,
 * rotate, resize, passwords, inserting pictures) its outline, form fields,
 * named destinations, tags and attachments stay too. Merging, deleting or
 * extracting pages drops those, keeps links between kept pages, and turns form
 * fields into their printed look. Only [addPassword]'s output is encrypted:
 * the others write an unprotected copy, even from a protected source.
 *
 * Everything throws [DocumentException]:
 * - [DocumentError.PasswordRequired]: encrypted and no password given, or the
 *   file's owner restricts what the copy would lift (printing, copying, page
 *   changes...). A refusal for owner limits always contains "owner password" in
 *   its detail, and only the owner password goes on, also when the user password
 *   was given. Nothing is bypassed, guessed or stripped silently.
 * - [DocumentError.WrongPassword]: the password given doesn't open it.
 * - [DocumentError.UnsupportedEncryption]: certificate or unknown encryption.
 * - [DocumentError.Malformed], [DocumentError.LimitExceeded]: damaged or too large.
 */
interface PdfTools {
    suspend fun inspect(source: PdfSource): PdfFileInfo

    /** All pages of [sources], in order. */
    suspend fun merge(sources: List<PdfSource>, target: String, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /** Exactly [pages], in that order: this is reorder, rotate, delete, extract and split. A page may appear twice. */
    suspend fun rebuild(source: PdfSource, pages: List<PdfPageEdit>, target: String, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /**
     * Every page, unprotected, for the print spooler only. Needs just the print permission, so a
     * watermarked PDF that opens for anyone prints without its owner password.
     */
    suspend fun printCopy(source: PdfSource, pageCount: Int, target: String) =
        rebuild(source, List(pageCount) { PdfPageEdit(it) }, target)

    /**
     * Scales the content of every page to fit [size] as displayed, centred and
     * keeping its proportions; annotations move with it. [PdfPageSize.FitImage] isn't valid here.
     */
    suspend fun resizePages(source: PdfSource, size: PdfPageSize, target: String, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /**
     * Adds [images] (picture URIs) as new pages, each shaped to its picture and
     * upright per EXIF, before zero-based page [atPage], or at the end if it equals the page count.
     */
    suspend fun insertImages(source: PdfSource, images: List<String>, atPage: Int, target: String, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /**
     * A copy that needs [password] to open: AES-256 (PDF 2.0, revision 6), with
     * [password] as both user and owner password and nothing restricted. A
     * protected [source] needs its own password. At most 127 bytes in UTF-8.
     */
    suspend fun addPassword(source: PdfSource, password: String, target: String, onProgress: (Int, Int) -> Unit = { _, _ -> })

    /** A copy with no password. [source] must carry the correct password: nothing is bypassed or guessed. */
    suspend fun removePassword(source: PdfSource, target: String, onProgress: (Int, Int) -> Unit = { _, _ -> })
}
