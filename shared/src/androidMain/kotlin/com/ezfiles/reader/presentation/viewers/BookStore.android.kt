package com.ezfiles.reader.presentation.viewers

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Reading settings, and where each book was left (by its name and size). */
private const val PREFS = "books"
private const val SETTINGS = "settings"
private const val POSITION = "pos:"

/** Books remembered; the least recently read are forgotten past this. */
private const val MAX_BOOKS = 500

/** Highlights, notes and bookmarks, one small file per book, private to the app. */
private const val NOTES_DIR = "book-notes"

@Composable
internal actual fun rememberBookStore(): BookStore {
    val context = LocalContext.current.applicationContext
    return remember(context) { PrefsBookStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), File(context.filesDir, NOTES_DIR)) }
}

private class PrefsBookStore(private val prefs: SharedPreferences, private val notes: File) : BookStore {
    override fun settings(): BookSettings? = decodeBookSettings(prefs.getString(SETTINGS, null))

    override fun saveSettings(settings: BookSettings) {
        prefs.edit().putString(SETTINGS, encodeBookSettings(settings)).apply()
    }

    override fun position(book: String): BookPosition? {
        val parts = prefs.getString(POSITION + book, null)?.split(':') ?: return null
        val chapter = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val fraction = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return BookPosition(chapter.coerceAtLeast(0), fraction.coerceIn(0.0, 1.0))
    }

    override fun savePosition(book: String, position: BookPosition) {
        val edit = prefs.edit().putString(POSITION + book, "${position.chapter}:${position.fraction}:${System.currentTimeMillis()}")
        val books = prefs.all.filterKeys { it.startsWith(POSITION) }
        if (books.size > MAX_BOOKS) {
            books.entries
                .sortedBy { (it.value as? String)?.split(':')?.getOrNull(2)?.toLongOrNull() ?: 0L }
                .take(books.size - MAX_BOOKS)
                .forEach { edit.remove(it.key) }
        }
        edit.apply()
    }

    override fun annotations(book: String): BookAnnotations {
        val file = AtomicFile(fileOf(book))
        return try {
            decodeBookAnnotations(file.readFully().decodeToString())
        } catch (_: IOException) {
            BookAnnotations()
        }
    }

    override fun saveAnnotations(book: String, annotations: BookAnnotations) {
        val file = AtomicFile(fileOf(book))
        if (annotations.highlights.isEmpty() && annotations.bookmarks.isEmpty()) {
            file.delete()
            return
        }
        notes.mkdirs()
        // Written whole and swapped in, so a crash mid-write keeps the last good copy.
        val out = try {
            file.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            out.write(encodeBookAnnotations(annotations).encodeToByteArray())
            file.finishWrite(out)
        } catch (_: IOException) {
            file.failWrite(out)
        }
    }

    /** Named by a hash of the book's identity, so file names never carry a book's title. */
    private fun fileOf(book: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(book.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        return File(notes, "$hash.json")
    }
}
