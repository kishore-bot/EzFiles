plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    // Persists the recent-documents list.
    alias(libs.plugins.kotlin.serialization)
    // The generated UniFFI bindings use atomicfu.
    alias(libs.plugins.kotlin.atomicfu)
    // Builds engine/crates/document-ffi for every Kotlin target and links it:
    // .so into the Android library, .a into the iOS framework via cinterop,
    // and the host library for JVM unit tests.
    alias(libs.plugins.gobley.cargo)
    // Generates Kotlin bindings (package com.ezfiles.engine.ffi) from the
    // compiled library's UniFFI metadata.
    alias(libs.plugins.gobley.uniffi)
}

cargo {
    packageDirectory = rootProject.layout.projectDirectory.dir("engine/crates/document-ffi")
}

uniffi {
    generateFromLibrary {
        packageName = "com.ezfiles.engine.ffi"
    }
}

kotlin {
    jvmToolchain(17)

    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // api: androidApp calls the shared App() composable.
            api(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.camera.camera2)
            implementation(libs.camera.lifecycle)
            implementation(libs.camera.view)
            implementation(libs.mlkit.text.recognition)
        }
    }
}

android {
    namespace = "com.ezfiles.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.android.ndk.get()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        // 64-bit devices and the x86_64 emulator. Add armeabi-v7a only if
        // 32-bit devices matter; each ABI adds a full copy of the engine.
        ndk.abiFilters += setOf("arm64-v8a", "x86_64")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
