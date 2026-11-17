// Media3's FFmpeg audio extension (Apache-2.0 sources from androidx/media 1.9.4, unchanged), linked to
// FFmpeg's audio decoders built as separate LGPL shared libraries by scripts/build-ffmpeg.sh. The
// player uses these only for formats the phone can't decode itself (E-AC3, AC3, DTS, TrueHD…).
// Without a built FFmpeg for an ABI the module still builds: the extension then reports itself
// unavailable and playback falls back to the phone's own decoders.
plugins {
    alias(libs.plugins.android.library)
}

val ffmpegDir: File = rootProject.layout.projectDirectory.dir(".cache/ffmpeg-7.1.2").asFile
val abis = listOf("arm64-v8a", "x86_64")

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.android.ndk.get()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk.abiFilters += abis
        externalNativeBuild {
            cmake {
                arguments += listOf("-DFFMPEG_DIR=${ffmpegDir.path.replace('\\', '/')}", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }
    // FFmpeg's own .so files, packaged next to the JNI library that needs them.
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("ffmpeg-libs"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val copyFfmpegLibs = tasks.register<Copy>("copyFfmpegLibs") {
    for (abi in abis) {
        from(File(ffmpegDir, "$abi/lib")) {
            include("libavutil.so", "libswresample.so", "libavcodec.so")
            into(abi)
        }
    }
    into(layout.buildDirectory.dir("ffmpeg-libs"))
}
tasks.matching { it.name == "preBuild" || (it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")) }
    .configureEach { dependsOn(copyFfmpegLibs) }

dependencies {
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.decoder)
    compileOnly("androidx.annotation:annotation:1.9.1")
    compileOnly("org.checkerframework:checker-qual:3.49.0")
}
