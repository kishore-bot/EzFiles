package com.ezfiles.reader.presentation.bookmarks

import com.ezfiles.reader.domain.Bookmark
import com.ezfiles.reader.domain.BookmarkLocation
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.presentation.viewers.BookAnnotations
import com.ezfiles.reader.presentation.viewers.BookBookmark
import com.ezfiles.reader.presentation.viewers.BookManifest

/**
 * E-book bookmarks used to live with the book's highlights, keyed by its name
 * and size. They now live with every other file's bookmarks. [moved] are the
 * book's old bookmarks for the shared list; [left] is what stays in the book's
 * notes. Save [moved] first, then [left], so a crash in between at worst keeps
 * both copies, which the shared list merges by place.
 */
internal data class BookBookmarkMigration(val moved: List<Bookmark>, val left: BookAnnotations)

internal fun migrateBookBookmarks(
    saved: BookAnnotations,
    ref: DocumentRef,
    manifest: BookManifest,
    now: Long,
): BookBookmarkMigration {
    val moved = saved.bookmarks.map { b ->
        Bookmark(
            id = b.id,
            ref = ref,
            format = DocumentFormat.Epub,
            location = BookmarkLocation.BookOffset(b.chapter, b.offset),
            label = chapterLabel(manifest, b.chapter),
            snippet = b.text,
            createdAt = b.created.takeIf { it > 0 } ?: now,
        )
    }
    return BookBookmarkMigration(moved, saved.copy(bookmarks = emptyList()))
}

/** The contents entry a chapter is under, or "Chapter n". */
internal fun chapterLabel(manifest: BookManifest, chapter: Int): String =
    manifest.contents.getOrNull(manifest.entryAt(chapter))?.title?.takeIf { it.isNotBlank() } ?: "Chapter ${chapter + 1}"

/**
 * A book's bookmarks as its reader page knows them. The page puts ids into its
 * script as they are, so only its own kind of id (hex digits) goes through.
 */
internal fun pageBookmarks(list: List<Bookmark>): List<BookBookmark> = list.mapNotNull { b ->
    val at = b.location as? BookmarkLocation.BookOffset ?: return@mapNotNull null
    if (b.id.length !in 8..64 || !b.id.all { it in '0'..'9' || it in 'a'..'f' }) return@mapNotNull null
    BookBookmark(b.id, at.chapter, at.offset, b.snippet, b.createdAt)
}.sortedWith(compareBy({ it.chapter }, { it.offset }))
