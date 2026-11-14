package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.NativeLimits
import com.ezfiles.engine.ffi.ReadException
import com.ezfiles.engine.ffi.openPath
import com.ezfiles.reader.domain.ContentBlock
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.DocumentFormat
import com.ezfiles.reader.domain.DocumentKind
import com.ezfiles.reader.domain.DocumentRef
import com.ezfiles.reader.domain.DocumentSession
import com.ezfiles.reader.domain.ReadLimits
import com.ezfiles.reader.domain.TextSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the real Rust engine through the generated bindings on the host JVM
 * (Gobley links the host build of document-ffi for Android unit tests).
 * Descriptor hand-off is Unix-only, so most tests open by path and exercise
 * the same session wrapper and type mapping that [RustDocumentReader] uses.
 */
class RustEngineIntegrationTest {
    private val defaults = NativeLimits(0u, 0u, 0u)

    private fun tempFile(contents: String): File =
        File.createTempFile("ezfiles", ".txt").apply {
            deleteOnExit()
            writeText(contents)
        }

    private fun open(file: File, limits: NativeLimits = defaults, nameHint: String? = null): DocumentSession =
        RustDocumentReader.wrap(openPath(file.absolutePath, nameHint, limits), file.name, Dispatchers.IO)

    @Test
    fun reads_text_through_the_ffi_boundary() = runTest {
        val file = tempFile("Hello\r\nwörld\n")
        open(file).use { session ->
            assertEquals(DocumentFormat.PlainText, session.info.format)
            assertEquals(DocumentKind.Text, session.info.kind)
            assertEquals("utf-8", session.info.encoding)
            assertEquals(file.length(), session.info.byteSize)

            val batch = session.nextBatch()!!
            assertEquals(listOf(ContentBlock.Paragraph("Hello"), ContentBlock.Paragraph("wörld")), batch.blocks)
            assertTrue(batch.isLast)
            assertNull(session.nextBatch())
        }
    }

    @Test
    fun batches_are_bounded() = runTest {
        open(tempFile("line\n".repeat(5_000)), NativeLimits(0u, 1_000u, 0u)).use { session ->
            var batches = 0
            var lines = 0
            while (true) {
                val batch = session.nextBatch() ?: break
                batches++
                lines += batch.blocks.size
            }
            assertEquals(5_000, lines)
            assertTrue(batches >= 25, "expected many small batches, got $batches")
        }
    }

    @Test
    fun a_damaged_compound_file_is_a_typed_engine_error() {
        val legacyOffice = File.createTempFile("ezfiles", ".doc").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        }
        val error = assertFailsWith<ReadException> { openPath(legacyOffice.absolutePath, null, defaults) }
        assertIs<ReadException.Malformed>(error)
    }

    /** A version 3 compound file with these root streams, each at least 4096 bytes so no mini stream is needed. */
    private fun compoundFile(vararg streams: Pair<String, ByteArray>): File {
        val sectorsOf = { bytes: ByteArray -> (bytes.size + 511) / 512 }
        val dataSectors = streams.sumOf { sectorsOf(it.second) }
        val buffer = ByteBuffer.allocate(512 * (dataSectors + 3)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        buffer.putShort(26, 3).putShort(28, 0xFFFE.toShort()).putShort(30, 9).putShort(32, 6)
        buffer.putInt(44, 1).putInt(48, dataSectors).putInt(56, 4096).putInt(60, -2).putInt(68, -2)
        for (i in 0 until 109) buffer.putInt(76 + i * 4, -1)
        buffer.putInt(76, dataSectors + 1)

        val fat = IntArray(128) { -1 }
        fat[dataSectors] = -2
        fat[dataSectors + 1] = -3
        val directory = 512 * (dataSectors + 1)
        fun entry(index: Int, name: String, kind: Int, right: Int, child: Int, start: Int, size: Int) {
            val at = directory + index * 128
            name.forEachIndexed { i, c -> buffer.putChar(at + i * 2, c) }
            buffer.putShort(at + 64, ((name.length + 1) * 2).toShort())
            buffer.put(at + 66, kind.toByte())
            buffer.putInt(at + 68, -1).putInt(at + 72, right).putInt(at + 76, child)
            buffer.putInt(at + 116, start).putInt(at + 120, size)
        }
        entry(0, "Root Entry", 5, -1, 1, -2, 0)
        var sector = 0
        streams.forEachIndexed { i, (name, bytes) ->
            buffer.position(512 * (sector + 1))
            buffer.put(bytes)
            for (s in 0 until sectorsOf(bytes)) fat[sector + s] = if (s + 1 == sectorsOf(bytes)) -2 else sector + s + 1
            entry(i + 1, name, 2, if (i + 1 < streams.size) i + 2 else -1, -1, sector, bytes.size)
            sector += sectorsOf(bytes)
        }
        fat.forEachIndexed { i, value -> buffer.putInt(512 * (dataSectors + 2) + i * 4, value) }
        return File.createTempFile("ezfiles", ".docx").apply {
            deleteOnExit()
            writeBytes(buffer.array())
        }
    }

    private fun encryptionInfo(major: Int, minor: Int, body: String = ""): ByteArray {
        val info = ByteArray(4096) { ' '.code.toByte() }
        ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN).putShort(major.toShort()).putShort(minor.toShort()).putInt(0x40)
        body.encodeToByteArray().copyInto(info, 8)
        return info
    }

    @Test
    fun password_errors_cross_the_ffi_boundary_with_their_types() {
        // The verifier decrypts to noise here, which is what any wrong password sees.
        val blocks = "AAAAAAAAAAAAAAAAAAAAAA=="
        val shared = """blockSize="16" keyBits="128" hashSize="20" cipherAlgorithm="AES" cipherChaining="ChainingModeCBC" hashAlgorithm="SHA1" saltSize="16" saltValue="$blocks""""
        val agile = """<encryption><keyData $shared/><keyEncryptors><keyEncryptor uri="http://schemas.microsoft.com/office/2006/keyEncryptor/password"><p:encryptedKey spinCount="10" $shared encryptedVerifierHashInput="$blocks" encryptedVerifierHashValue="${"A".repeat(43)}=" encryptedKeyValue="$blocks"/></keyEncryptor></keyEncryptors></encryption>"""
        val locked = compoundFile("EncryptionInfo" to encryptionInfo(4, 4, agile), "EncryptedPackage" to ByteArray(4096))
        assertIs<ReadException.PasswordRequired>(assertFailsWith<ReadException> { openPath(locked.absolutePath, null, defaults) })
        assertIs<ReadException.WrongPassword>(
            assertFailsWith<ReadException> { openPath(locked.absolutePath, null, defaults, "wrong") },
        )

        val rc4 = compoundFile("EncryptionInfo" to encryptionInfo(1, 1), "EncryptedPackage" to ByteArray(4096))
        assertIs<ReadException.UnsupportedEncryption>(assertFailsWith<ReadException> { openPath(rc4.absolutePath, null, defaults) })
    }

    @Test
    fun an_unknown_compound_file_is_unsupported() {
        val file = compoundFile("Thumbs" to ByteArray(4096))
        assertIs<ReadException.UnsupportedFormat>(assertFailsWith<ReadException> { openPath(file.absolutePath, null, defaults) })
    }

    @Test
    fun pdf_is_validated_by_the_engine_and_left_to_the_platform_renderer() = runTest {
        open(tempFile("%PDF-1.7\n%âã\n")).use { session ->
            assertEquals(DocumentFormat.Pdf, session.info.format)
            assertEquals(DocumentKind.Pdf, session.info.kind)
            assertEquals(emptyList(), session.nextBatch()!!.blocks)
        }
    }

    /** Java's ZipOutputStream writes data descriptors: an independent ZIP writer. */
    private fun zipFile(suffix: String, entries: Map<String, String>): File =
        File.createTempFile("ezfiles", suffix).apply {
            deleteOnExit()
            ZipOutputStream(outputStream()).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.encodeToByteArray())
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun docx_written_by_an_independent_zip_writer() = runTest {
        val docx = zipFile(
            ".docx",
            mapOf(
                "[Content_Types].xml" to "<Types/>",
                "_rels/.rels" to """<Relationships><Relationship Id="r1" Type="http://x/officeDocument" Target="word/document.xml"/></Relationships>""",
                "word/document.xml" to """<w:document xmlns:w="w"><w:body>
                    <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Title</w:t></w:r></w:p>
                    <w:p><w:r><w:t>Body text</w:t></w:r></w:p>
                    <w:tbl><w:tr><w:tc><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>b</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                    </w:body></w:document>""",
            ),
        )
        open(docx).use { session ->
            assertEquals(DocumentFormat.Docx, session.info.format)
            assertEquals(
                listOf(
                    ContentBlock.Heading(2, "Title"),
                    ContentBlock.Paragraph("Body text"),
                    ContentBlock.TableRow(null, listOf("a", "b")),
                ),
                session.nextBatch()!!.blocks,
            )
        }
    }

    @Test
    fun document_pictures_map_to_image_blocks_and_load_between_batches() = runTest {
        val paragraphs = (0 until 2_000).joinToString("") { "<w:p><w:r><w:t>line $it</w:t></w:r></w:p>" }
        val docx = zipFile(
            ".docx",
            mapOf(
                "_rels/.rels" to """<Relationships><Relationship Id="r1" Type="http://x/officeDocument" Target="word/document.xml"/></Relationships>""",
                "word/_rels/document.xml.rels" to """<Relationships><Relationship Id="rId1" Type="http://x/image" Target="media/a.png"/><Relationship Id="rId2" Type="http://x/image" Target="https://evil.example/b.png" TargetMode="External"/></Relationships>""",
                "word/media/a.png" to "picture",
                "word/document.xml" to """<w:document><w:body>
                    <w:p><w:r><w:drawing><wp:inline><wp:extent cx="1270000" cy="635000"/><wp:docPr id="1" descr="A map"/><a:blip r:embed="rId1"/></wp:inline></w:drawing></w:r></w:p>
                    <w:p><w:r><w:drawing><wp:inline><a:blip r:embed="rId2"/></wp:inline></w:drawing></w:r></w:p>
                    $paragraphs
                    </w:body></w:document>""",
            ),
        )
        open(docx, NativeLimits(0u, 1_000u, 0u)).use { session ->
            val first = session.nextBatch()!!
            assertEquals(ContentBlock.Image("word/media/a.png", "A map", 100f, 50f), first.blocks[0])
            assertEquals(ContentBlock.Image(null), first.blocks[1])

            assertEquals("picture", session.resource("word/media/a.png")!!.decodeToString())
            assertNull(session.resource("word/document.xml"))
            assertNull(session.resource("../../etc/passwd"))

            var lines = first.blocks.count { it is ContentBlock.Paragraph }
            while (true) {
                val batch = session.nextBatch() ?: break
                lines += batch.blocks.size
            }
            assertEquals(2_000, lines)
        }
    }

    @Test
    fun markdown_images_have_nothing_to_load() = runTest {
        open(tempFile("![A map](https://evil.example/map.png)\n"), nameHint = "notes.md").use { session ->
            assertEquals(listOf<ContentBlock>(ContentBlock.Image(null, "A map")), session.nextBatch()!!.blocks)
        }
    }

    @Test
    fun plain_archives_are_listed_not_extracted() = runTest {
        open(zipFile(".zip", mapOf("notes/a.txt" to "hello", "b.bin" to "xx"))).use { session ->
            assertEquals(DocumentKind.Archive, session.info.kind)
            assertEquals(
                listOf(ContentBlock.Entry("b.bin", 2, false), ContentBlock.Entry("notes/a.txt", 5, false)),
                session.nextBatch()!!.blocks,
            )
        }
    }

    @Test
    fun markdown_arrives_with_styles_tables_and_code() = runTest {
        val file = tempFile(
            """
            |# Notes
            |
            |Some **bold** [site](https://ezfiles.app)
            |
            || A | B |
            ||---|---|
            || 1 | 2 |
            |
            |```kotlin
            |val x = 1
            |```
            |
            """.trimMargin(),
        )
        open(file, nameHint = "notes.md").use { session ->
            assertEquals(DocumentFormat.Markdown, session.info.format)
            val blocks = session.nextBatch()!!.blocks
            assertEquals(ContentBlock.Heading(1, "Notes"), blocks[0])
            val paragraph = assertIs<ContentBlock.Paragraph>(blocks[1])
            assertEquals("Some bold site", paragraph.text)
            assertEquals(
                listOf(TextSpan(5, 9, bold = true), TextSpan(10, 14, link = "https://ezfiles.app")),
                paragraph.spans,
            )
            assertEquals(ContentBlock.TableRow(null, listOf("A", "B"), header = true), blocks[2])
            assertEquals(ContentBlock.TableRow(null, listOf("1", "2")), blocks[3])
            assertEquals(ContentBlock.Code("val x = 1", "kotlin"), blocks[4])
        }
    }

    @Test
    fun html_serves_its_raw_bytes_or_its_code() = runTest {
        val file = tempFile("<!doctype html>\n<p>caf\u00E9</p>\n")
        open(file, nameHint = "page.xhtml").use { session ->
            assertEquals(DocumentFormat.Html, session.info.format)
            assertEquals(DocumentKind.Text, session.info.kind)
            assertEquals(emptyList(), session.nextBatch()!!.blocks)
            assertEquals("<!doctype html>\n<p>caf\u00E9</p>\n", session.resource("html")?.decodeToString())
        }
        open(file, nameHint = RustDocumentReader.HTML_SOURCE_HINT).use { session ->
            assertEquals(DocumentFormat.SourceCode, session.info.format)
            assertEquals(ContentBlock.Code("<!doctype html>\n<p>caf\u00E9</p>", "html", 1), session.nextBatch()!!.blocks.single())
        }
    }

    @Test
    fun plain_text_keeps_a_reading_view_and_offers_numbered_unicode_source() = runTest {
        val file = tempFile("caf\u00E9\n\n  \u0D28\u0D2E\u0D38\u0D4D\u0D15\u0D3E\u0D30\u0D02\n")
        open(file, nameHint = "notes.txt").use { session ->
            assertEquals(DocumentFormat.PlainText, session.info.format)
            assertIs<ContentBlock.Paragraph>(session.nextBatch()!!.blocks.first())
        }
        open(file, nameHint = RustDocumentReader.TEXT_SOURCE_HINT).use { session ->
            assertEquals(DocumentFormat.SourceCode, session.info.format)
            assertEquals(
                ContentBlock.Code("caf\u00E9\n\n  \u0D28\u0D2E\u0D38\u0D4D\u0D15\u0D3E\u0D30\u0D02", "text", 1),
                session.nextBatch()!!.blocks.single(),
            )
        }
    }

    @Test
    fun use_after_close_fails_safely_instead_of_touching_freed_memory() = runTest {
        val session = open(tempFile("x"))
        session.close()
        assertFailsWith<IllegalStateException> { session.nextBatch() }
    }

    @Test
    fun engine_errors_from_open_map_to_domain_errors() = runTest {
        // On Windows hosts descriptors are unsupported, so any fd is rejected
        // with Io; on Unix hosts fd -1 is rejected the same way.
        val reader = RustDocumentReader(
            access = object : DocumentAccess {
                override fun openDescriptor(ref: DocumentRef, limits: ReadLimits) = -1
            },
        )
        val failure = assertFailsWith<DocumentException> {
            reader.open(DocumentRef("test://x", "x"), ReadLimits.Default)
        }
        assertIs<DocumentError.FileAccess>(failure.error)
    }
}
