package com.ezfiles.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val PACKAGE = "com.ezfiles"
private const val WAIT_MS = 5_000L

/** Startup Profile contains only the useful library launch. A missing screen fails collection. */
fun MacrobenchmarkScope.startupJourney() {
    pressHome()
    startActivityAndWait()
    requireObject(By.pkg(PACKAGE).text("Tools"), "library Tools action")
    requireObject(By.pkg(PACKAGE).text("Open file"), "library Open file action")
    device.waitForIdle()
}

/** Frequent tool code belongs in the Baseline Profile, outside the Startup Profile. */
fun MacrobenchmarkScope.coreJourney() {
    startupJourney()
    requireObject(By.pkg(PACKAGE).text("Tools"), "Tools action").click()
    device.waitForIdle()
    requireObject(By.pkg(PACKAGE).text("PDF tools"), "PDF tools entry").click()
    requireObject(By.pkg(PACKAGE).text("Merge PDFs"), "PDF tools hub")
    device.pressBack()
    requireObject(By.pkg(PACKAGE).text("Tools"), "library after closing PDF tools")
    device.waitForIdle()
}

/** A test-owned provider grants the fixture explicitly; production picker permissions stay intact. */
fun MacrobenchmarkScope.openReaderFixture(fixture: ReaderFixture) {
    startActivityAndWait(fixture.launchIntent())
    requireObject(By.pkg(PACKAGE).text(fixture.name), "fixture reader title")
    if (fixture.pdf) {
        requireObject(By.pkg(PACKAGE).text("1 / $PDF_PAGES"), "PDF page count")
        requireObject(By.pkg(PACKAGE).desc("Page 1"), "rendered native PDF page")
    } else {
        requireObject(By.pkg(PACKAGE).textContains(TEXT_MARKER), "parsed native text fixture")
    }
    device.waitForIdle()
}

/** Six finite gestures exercise actual reader content and assert the viewport changed. */
fun MacrobenchmarkScope.scrollReaderFixture(fixture: ReaderFixture) {
    repeat(6) {
        check(device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
            device.displayWidth / 2, device.displayHeight / 3, 18)) { "Reader swipe was not injected" }
        device.waitForIdle()
    }
    val initial = if (fixture.pdf) By.pkg(PACKAGE).text("1 / $PDF_PAGES") else By.pkg(PACKAGE).textContains(TEXT_MARKER)
    check(device.wait(Until.gone(initial), WAIT_MS)) { "Fixture reader did not scroll away from its initial content" }
    check(device.hasObject(By.pkg(PACKAGE).desc("Back to files"))) { "Reader disappeared during scrolling" }
}

fun MacrobenchmarkScope.readerJourney(fixture: ReaderFixture) {
    openReaderFixture(fixture)
    scrollReaderFixture(fixture)
    device.pressBack()
    requireObject(By.pkg(PACKAGE).text("Tools"), "library after closing fixture")
}

private fun MacrobenchmarkScope.requireObject(selector: BySelector, description: String): UiObject2 =
    checkNotNull(device.wait(Until.findObject(selector), WAIT_MS)) { "Missing $description in $PACKAGE" }
