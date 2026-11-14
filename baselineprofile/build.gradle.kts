// Generates the app's Baseline Profile and measures startup with Macrobenchmark.
//
//   ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:generateReleaseBaselineProfile
//
// writes androidApp/src/release/generated/baselineProfiles/baseline-prof.txt,
// which is committed and compiled into release builds (bundle, APKs). Always
// set ANDROID_SERIAL: without it the tests run on every connected device.
// Run again after large UI changes; see docs/architecture/performance.md.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.ezfiles.baselineprofile"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Profile collection without root needs Android 13 (API 33) or newer on the device.
        minSdk = 28
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":androidApp"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

baselineProfile {
    // The connected emulator, never a managed device that would download a system image.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
