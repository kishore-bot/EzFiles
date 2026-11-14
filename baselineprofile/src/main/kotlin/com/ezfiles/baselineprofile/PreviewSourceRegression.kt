package com.ezfiles.baselineprofile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Both directions exercise actual rendered Markdown/raw source, not just a changed button label. */
@RunWith(AndroidJUnit4::class)
class PreviewSourceRegression {
    private lateinit var fixtures: BenchmarkFixtures

    @Before
    fun prepareFixtures() { fixtures = BenchmarkFixtures.create() }

    @After
    fun revokeFixtureAccess() { if (::fixtures.isInitialized) fixtures.revoke() }

    @Test
    fun floatingGlassButtonsSwitchBetweenPreviewAndOriginalSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        context.startActivity(fixtures.markdown.launchIntent())
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).text(fixtures.markdown.name)), 10_000))
        check(device.wait(Until.hasObject(By.text("Floating control fixture")), 10_000)) { "Rendered Markdown heading missing" }
        check(device.hasObject(By.pkg(PACKAGE).text("Preview")))
        val web = checkNotNull(device.wait(Until.findObject(By.clazz("android.webkit.WebView")), 5_000))
        val source = checkNotNull(device.findObject(By.pkg(PACKAGE).text("Source")))
        check(web.visibleBounds.bottom >= source.visibleBounds.bottom) { "A reserved footer still removes HTML viewport space" }
        checkNotNull(device.wait(Until.findObject(By.pkg(PACKAGE).text("Source")), 5_000)).click()
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).textContains("# Floating control fixture")), 10_000)) { "Original Markdown source missing" }
        check(device.hasObject(By.pkg(PACKAGE).text("Source")))
        checkNotNull(device.wait(Until.findObject(By.pkg(PACKAGE).text("Preview")), 5_000)).click()
        check(device.wait(Until.hasObject(By.text("Floating control fixture")), 10_000)) { "Markdown preview did not return" }
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).text("Source")), 5_000))
        device.pressBack()
        check(device.wait(Until.hasObject(By.pkg(PACKAGE).text("Tools")), 5_000))
    }
}
