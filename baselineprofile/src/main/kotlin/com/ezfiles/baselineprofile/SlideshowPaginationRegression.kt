package com.ezfiles.baselineprofile

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val SLIDES = 60
private const val SLIDE_MARKER = "Controlled pagination fixture"
private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

/** Exercises the real parser/pager as later batches grow past the original item provider. */
@RunWith(AndroidJUnit4::class)
class SlideshowPaginationRegression {
    private lateinit var fixture: ReaderFixture

    @Before
    fun createFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.ezfiles.baselineprofile")
        val directory = File(context.filesDir, "benchmark-fixtures").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, "regression-slides.pptx")
        writeDeck(file)
        fixture = ReaderFixture(context,
            Uri.Builder().scheme("content").authority("${context.packageName}.fixtures").appendPath(file.name).build(),
            file.name, false, PPTX_MIME)
    }

    @After
    fun revokeFixtureAccess() { if (::fixture.isInitialized) fixture.revoke() }

    @Test
    fun opensEverySlideAcrossNativeLoadingBatches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(fixture.launchIntent())
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).text(fixture.name)), 10_000)) { "Native presentation reader did not open" }
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).textContains(SLIDE_MARKER)), 10_000)) { "Native slide content was not rendered" }
        for (page in 1..SLIDES) {
            check(device.wait(Until.hasObject(By.pkg(PACKAGE).text("$page / $SLIDES")), 10_000)) {
                "Presentation failed to reach slide $page across a loading batch"
            }
            if (page != SLIDES) {
                checkNotNull(device.wait(Until.findObject(By.pkg(PACKAGE).desc("Next slide")), 5_000)).click()
                device.waitForIdle()
            }
        }
        checkNotNull(device.findObject(By.pkg(PACKAGE).desc("Previous slide"))).click()
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).text("${SLIDES - 1} / $SLIDES")), 5_000))
        device.pressBack()
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).text("Tools")), 5_000)) { "Reader did not return to the library" }
    }
}

/** About 2.5 KiB of text per slide exceeds the engine's 64 KiB native batch several times. */
private fun writeDeck(file: File) {
    val presentationNs = "http://schemas.openxmlformats.org/presentationml/2006/main"
    val drawingNs = "http://schemas.openxmlformats.org/drawingml/2006/main"
    val relationshipNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    val packageNs = "http://schemas.openxmlformats.org/package/2006/relationships"
    ZipOutputStream(file.outputStream()).use { zip ->
        fun entry(path: String, xml: String) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        entry("[Content_Types].xml", """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>${(1..SLIDES).joinToString("") { """<Override PartName="/ppt/slides/slide$it.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""" }}</Types>""")
        entry("_rels/.rels", """<Relationships xmlns="$packageNs"><Relationship Id="rId1" Type="$relationshipNs/officeDocument" Target="ppt/presentation.xml"/></Relationships>""")
        entry("ppt/presentation.xml", """<p:presentation xmlns:p="$presentationNs" xmlns:r="$relationshipNs"><p:sldIdLst>${(1..SLIDES).joinToString("") { """<p:sldId id="${255 + it}" r:id="rId$it"/>""" }}</p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""")
        entry("ppt/_rels/presentation.xml.rels", """<Relationships xmlns="$packageNs">${(1..SLIDES).joinToString("") { """<Relationship Id="rId$it" Type="$relationshipNs/slide" Target="slides/slide$it.xml"/>""" }}</Relationships>""")
        repeat(SLIDES) { index ->
            val text = "$SLIDE_MARKER ${index + 1}. " + "Controlled text across native batch boundaries. ".repeat(54)
            entry("ppt/slides/slide${index + 1}.xml", """<p:sld xmlns:p="$presentationNs" xmlns:a="$drawingNs"><p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="2" name="Body"/><p:cNvSpPr/><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr><p:spPr><a:xfrm><a:off x="635000" y="635000"/><a:ext cx="10922000" cy="5588000"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr sz="1400"/><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld></p:sld>""")
        }
    }
}
