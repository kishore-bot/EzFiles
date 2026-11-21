package com.ezfiles.reader.platform

import android.content.Context
import android.net.Uri
import com.ezfiles.engine.ffi.InternalException
import com.ezfiles.engine.ffi.ReadException
import com.ezfiles.engine.ffi.pptxReorderSlides as ffiPptxReorderSlides
import com.ezfiles.engine.ffi.pptxSlideCount as ffiPptxSlideCount
import com.ezfiles.engine.ffi.replaceInDocument
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AndroidDocumentEditor(context: Context) : DocumentEditor {
    private val app = context.applicationContext
    private val workDir = File(app.cacheDir, "doc-edit")

    override suspend fun replaceAll(
        sourceUri: String,
        displayName: String,
        find: String,
        replace: String,
        caseSensitive: Boolean,
    ): ReplaceResult = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val ext = extensionFor(displayName)
        val source = File.createTempFile("src", ext, workDir)
        val output = File.createTempFile("out", ext, workDir)
        try {
            app.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                source.outputStream().use { input.copyTo(it) }
            } ?: throw DocumentException(
                DocumentError.FileAccess("The file could not be read."),
            )
            val count = engineCall {
                replaceInDocument(source.path, output.path, find, replace, caseSensitive)
            }
            ReplaceResult(count.toInt(), output.readBytes())
        } finally {
            source.delete()
            output.delete()
        }
    }

    override suspend fun pptxSlideCount(
        sourceUri: String,
        displayName: String,
    ): Int = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val source = File.createTempFile("src", ".pptx", workDir)
        try {
            app.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                source.outputStream().use { input.copyTo(it) }
            } ?: throw DocumentException(
                DocumentError.FileAccess("The file could not be read."),
            )
            engineCall { ffiPptxSlideCount(source.path).toInt() }
        } finally {
            source.delete()
        }
    }

    override suspend fun pptxReorderSlides(
        sourceUri: String,
        displayName: String,
        keepIndices: List<Int>,
    ): ByteArray = withContext(Dispatchers.IO) {
        workDir.mkdirs()
        val source = File.createTempFile("src", ".pptx", workDir)
        val output = File.createTempFile("out", ".pptx", workDir)
        try {
            app.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                source.outputStream().use { input.copyTo(it) }
            } ?: throw DocumentException(
                DocumentError.FileAccess("The file could not be read."),
            )
            engineCall {
                ffiPptxReorderSlides(
                    source.path,
                    output.path,
                    keepIndices.map { it.toUInt() },
                )
            }
            output.readBytes()
        } finally {
            source.delete()
            output.delete()
        }
    }

    override suspend fun xlsxEditCells(
        sourceUri: String,
        displayName: String,
        sheetIndex: Int,
        edits: Map<Pair<Int, Int>, String>,
    ): ByteArray = withContext(Dispatchers.IO) {
        if (edits.isEmpty()) throw DocumentException(DocumentError.Internal("No edits to apply."))
        workDir.mkdirs()
        val source = File.createTempFile("src", ".xlsx", workDir)
        try {
            app.contentResolver.openInputStream(Uri.parse(sourceUri))?.use { input ->
                source.outputStream().use { input.copyTo(it) }
            } ?: throw DocumentException(DocumentError.FileAccess("The file could not be read."))

            val sheetPaths = mutableListOf<String>()
            // First pass: find sheet XML paths from workbook relationships
            ZipInputStream(source.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))) {
                        sheetPaths.add(entry.name)
                    }
                    entry = zip.nextEntry
                }
            }
            sheetPaths.sort()
            if (sheetIndex >= sheetPaths.size) throw DocumentException(DocumentError.Internal("Sheet index out of range."))
            val targetSheet = sheetPaths[sheetIndex]

            val output = ByteArrayOutputStream()
            ZipInputStream(source.inputStream()).use { zin ->
                ZipOutputStream(output).use { zout ->
                    var entry = zin.nextEntry
                    while (entry != null) {
                        zout.putNextEntry(ZipEntry(entry.name))
                        if (entry.name == targetSheet) {
                            val xml = zin.readBytes().toString(Charsets.UTF_8)
                            val modified = applyXlsxEdits(xml, edits)
                            zout.write(modified.toByteArray(Charsets.UTF_8))
                        } else {
                            zin.copyTo(zout)
                        }
                        zout.closeEntry()
                        entry = zin.nextEntry
                    }
                }
            }
            output.toByteArray()
        } finally {
            source.delete()
        }
    }

    private fun extensionFor(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.contains(".docx") -> ".docx"
            lower.contains(".pptx") -> ".pptx"
            lower.contains(".odt") -> ".odt"
            lower.contains(".odp") -> ".odp"
            else -> ".tmp"
        }
    }
}

private fun cellRef(row: Int, col: Int): String {
    val sb = StringBuilder()
    var c = col
    do {
        sb.insert(0, ('A' + c % 26).toChar())
        c = c / 26 - 1
    } while (c >= 0)
    sb.append(row + 1)
    return sb.toString()
}

private fun applyXlsxEdits(xml: String, edits: Map<Pair<Int, Int>, String>): String {
    val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
    }
    val doc = factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))

    val sheetData = doc.getElementsByTagName("sheetData").item(0) ?: return xml

    val rowMap = mutableMapOf<Int, org.w3c.dom.Element>()
    val rows = sheetData.childNodes
    for (i in 0 until rows.length) {
        val node = rows.item(i) as? org.w3c.dom.Element ?: continue
        if (node.tagName == "row") {
            val r = node.getAttribute("r").toIntOrNull() ?: continue
            rowMap[r] = node
        }
    }

    for ((key, value) in edits) {
        val (row, col) = key
        val ref = cellRef(row, col)
        val rowNum = row + 1

        var rowEl = rowMap[rowNum]
        if (rowEl == null) {
            rowEl = doc.createElement("row")
            rowEl.setAttribute("r", rowNum.toString())
            sheetData.appendChild(rowEl)
            rowMap[rowNum] = rowEl
        }

        var cellEl: org.w3c.dom.Element? = null
        val cells = rowEl.childNodes
        for (i in 0 until cells.length) {
            val node = cells.item(i) as? org.w3c.dom.Element ?: continue
            if (node.getAttribute("r") == ref) {
                cellEl = node
                break
            }
        }

        if (cellEl == null) {
            cellEl = doc.createElement("c")
            cellEl.setAttribute("r", ref)
            rowEl.appendChild(cellEl)
        }

        while (cellEl.firstChild != null) cellEl.removeChild(cellEl.firstChild)

        val numValue = value.toDoubleOrNull()
        if (numValue != null) {
            cellEl.removeAttribute("t")
            val v = doc.createElement("v")
            v.textContent = if (numValue == numValue.toLong().toDouble()) numValue.toLong().toString() else value
            cellEl.appendChild(v)
        } else {
            cellEl.setAttribute("t", "inlineStr")
            val isEl = doc.createElement("is")
            val t = doc.createElement("t")
            t.textContent = value
            isEl.appendChild(t)
            cellEl.appendChild(isEl)
        }
    }

    val transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer()
    transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "no")
    transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8")
    transformer.setOutputProperty(javax.xml.transform.OutputKeys.STANDALONE, "yes")
    val writer = java.io.StringWriter()
    transformer.transform(javax.xml.transform.dom.DOMSource(doc), javax.xml.transform.stream.StreamResult(writer))
    return writer.toString()
}

private inline fun <T> engineCall(block: () -> T): T = try {
    block()
} catch (e: ReadException) {
    throw DocumentException(
        when (e) {
            is ReadException.Io -> DocumentError.FileAccess(e.detail)
            is ReadException.UnsupportedFormat -> DocumentError.Unsupported(e.detail)
            is ReadException.Malformed -> DocumentError.Malformed(e.detail)
            is ReadException.LimitExceeded -> DocumentError.LimitExceeded(e.detail)
            is ReadException.Cancelled -> DocumentError.Internal(e.detail)
            is ReadException.Internal -> DocumentError.Internal(e.detail)
            is ReadException.PasswordRequired -> DocumentError.PasswordRequired(e.detail)
            is ReadException.WrongPassword -> DocumentError.WrongPassword(e.detail)
            is ReadException.UnsupportedEncryption -> DocumentError.UnsupportedEncryption(e.detail)
        },
    )
} catch (e: InternalException) {
    throw DocumentException(DocumentError.Internal(e.message ?: "engine failure"))
} catch (e: IOException) {
    throw DocumentException(DocumentError.FileAccess(e.message ?: "The file could not be accessed."))
} catch (e: SecurityException) {
    throw DocumentException(DocumentError.FileAccess("Xfiles no longer has access to this file."))
}
