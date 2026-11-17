rootProject.name = "EzFiles"

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
// FFmpeg audio decoders for the video player (LGPL libraries built by scripts/build-ffmpeg.sh).
include(":ffmpeg")
include(":androidApp")
include(":baselineprofile")
