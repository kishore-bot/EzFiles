package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.LibraryFolder
import com.ezfiles.reader.domain.MEDIA_EXTENSIONS
import com.ezfiles.reader.domain.SourceCodeExtensions
import com.ezfiles.reader.domain.SourceCodeFileNames
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One row of a folder listing. [uri] opens it; [id] lists it when it is a directory. */
internal data class TreeEntry(
    val id: String,
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long? = null,
    val modifiedAt: Long? = null,
    /** Provider identity, shared by picker and tree URIs when available. */
    val identity: String = uri,
)

internal sealed interface TreeStatus {
    data class Readable(val rootId: String) : TreeStatus

    /** Access was revoked or the folder was deleted, so it won't come back. */
    data object Lost : TreeStatus

    /** Unreachable now, but it may return (removed SD card, offline provider). */
    data object Unavailable : TreeStatus
}

/** Platform access to folder trees. All calls block; none throw for provider failures. */
internal interface DocumentTrees {
    /** Keeps read access across restarts; `null` if the platform refuses. */
    fun take(uri: String): LibraryFolder?

    fun release(uri: String)

    fun status(uri: String): TreeStatus

    /**
     * Calls [onEntry] per child of [parentId] until it returns false. A listing
     * that fails counts as empty. Exceptions from [onEntry] propagate.
     */
    fun forEachChild(uri: String, parentId: String, onEntry: (TreeEntry) -> Boolean)
}

internal data class TreeLimits(
    val maxDepth: Int = 12,
    val maxDocuments: Int = 5000,
    val maxImages: Int = 2000,
    /** Rows looked at, supported or not, so a huge tree of other files stays bounded. */
    val maxEntries: Int = 100_000,
) {
    /** One folder's part of the limits, so a large folder can't crowd out the others. */
    fun sharedBy(folders: Int): TreeLimits = if (folders <= 1) {
        this
    } else {
        copy(maxDocuments = maxDocuments / folders, maxImages = maxImages / folders, maxEntries = maxEntries / folders)
    }
}

internal data class TreeWalk(val refs: List<DocumentRef>, val limitReached: Boolean)

/**
 * Breadth-first, so files near the top of a folder win when a limit is hit.
 * Hidden files and folders are skipped. Separate caps keep a large photo
 * library from crowding out documents. Cancellation is checked on every row.
 */
internal suspend fun walkDocumentTree(trees: DocumentTrees, uri: String, rootId: String, limits: TreeLimits = TreeLimits()): TreeWalk {
    val context = currentCoroutineContext()
    val refs = ArrayList<DocumentRef>()
    val pending = ArrayDeque(listOf(rootId to 0))
    val seen = hashSetOf(rootId)
    var documents = 0
    var images = 0
    var entries = 0
    var limitReached = false
    while (pending.isNotEmpty() && !limitReached) {
        context.ensureActive()
        val (parent, depth) = pending.removeFirst()
        trees.forEachChild(uri, parent) { entry ->
            context.ensureActive()
            if (++entries > limits.maxEntries) {
                limitReached = true
                return@forEachChild false
            }
            when {
                entry.name.startsWith(".") -> Unit
                entry.isDirectory -> when {
                    depth >= limits.maxDepth -> limitReached = true
                    // A provider's symlink can point back up the tree.
                    seen.add(entry.id) -> pending.addLast(entry.id to depth + 1)
                }
                entry.size == 0L -> Unit
                else -> when (val extension = entry.name.substringAfterLast('.', "").lowercase()) {
                    in DOCUMENT_EXTENSIONS, in MEDIA_EXTENSIONS, in SourceCodeExtensions,
                    // Makefile, Dockerfile and the like have no extension.
                    "" -> if (extension.isEmpty() && entry.name.lowercase() !in SourceCodeFileNames) Unit
                    else if (documents < limits.maxDocuments) {
                        documents++
                        refs += entry.toRef()
                    } else {
                        limitReached = true
                    }
                    in IMAGE_EXTENSIONS -> if (images < limits.maxImages) {
                        images++
                        refs += entry.toRef()
                    } else {
                        limitReached = true
                    }
                }
            }
            true
        }
    }
    return TreeWalk(refs, limitReached)
}

private fun TreeEntry.toRef() = DocumentRef(
    uri = uri,
    displayName = name,
    sizeBytes = size,
    modifiedAt = modifiedAt?.takeIf { it > 0 },
    identity = identity,
)

/** Formats the engine reads; most source code is left to the picker to keep the list focused. */
private val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "doc", "dot", "xls", "xlt", "ppt", "pps", "pot", "docx", "docm", "dotx", "dotm", "xlsx", "xlsm", "xltx", "xltm", "pptx", "pptm", "ppsx", "ppsm",
    "potx", "potm", "odt", "ods", "odp", "epub", "txt", "md", "markdown", "csv", "tsv", "json", "jsonl",
    "xml", "yaml", "yml", "toml", "sql", "log", "html", "htm", "xhtml", "zip",
)
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
