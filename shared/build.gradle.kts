import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

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
            // Video and audio: ExoPlayer, with FFmpeg for audio formats the phone can't decode.
            implementation(libs.media3.exoplayer)
            implementation(project(":ffmpeg"))
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
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    // Inference is unusably slow at -O0; debug APKs still debug the Kotlin side.
                    "-DCMAKE_BUILD_TYPE=Release",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Second speech engine: sherpa-onnx (Apache-2.0) runs Moonshine Base offline. The pinned release is
// downloaded once, checked against its SHA-256, and only the Kotlin API and the two native libraries
// its JNI layer needs are unpacked into .cache/ (no binaries in Git). The models are separate files on
// the phone (see SpeechRecognition.android.kt).
val sherpaVersion = "2.0.1"
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaDir: File = rootProject.layout.projectDirectory.dir(".cache/sherpa-onnx-$sherpaVersion").asFile
val fetchSherpaOnnx = tasks.register("fetchSherpaOnnx") {
    description = "Downloads and unpacks the pinned sherpa-onnx Android release."
    val dir = sherpaDir
    val version = sherpaVersion
    val expected = sherpaSha256
    outputs.dir(dir)
    onlyIf { !File(dir, "classes.jar").isFile }
    doLast {
        dir.mkdirs()
        val aar = File(dir, "sherpa-onnx-$version.aar")
        // A copy placed there by hand (e.g. downloaded with curl) is used as is, after the checksum.
        if (!aar.isFile) {
            URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar")
                .toURL().openStream().use { input -> aar.outputStream().use { input.copyTo(it) } }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(aar.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == expected) { "sherpa-onnx $version checksum mismatch: $digest" }
        ZipFile(aar).use { zip ->
            val wanted = listOf("arm64-v8a", "x86_64").flatMap { abi ->
                listOf("libsherpa-onnx-jni.so", "libonnxruntime.so").map { "jni/$abi/$it" }
            } + "classes.jar"
            for (name in wanted) {
                val entry = zip.getEntry(name) ?: error("$name missing from sherpa-onnx $version")
                val target = File(dir, name)
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        aar.delete()
    }
}
kotlin.sourceSets.getByName("androidMain").dependencies {
    implementation(files(File(sherpaDir, "classes.jar")).builtBy(fetchSherpaOnnx))
}
android.sourceSets.getByName("main").jniLibs.srcDir(File(sherpaDir, "jni"))
tasks.matching { it.name == "preBuild" || (it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")) }
    .configureEach { dependsOn(fetchSherpaOnnx) }
