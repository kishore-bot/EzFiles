package com.ezfiles.reader.domain

/** Audio and video, which the platform's player plays instead of the engine reading them. */
enum class MediaKind { Audio, Video }

private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "3g2", "webm", "mkv")
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "ogg", "oga", "opus", "flac", "amr", "mid", "midi")

/** The kind of media a file name says it is, or `null` for anything else. */
fun mediaKindOf(name: String): MediaKind? = when (name.substringAfterLast('.', "").lowercase()) {
    in VIDEO_EXTENSIONS -> MediaKind.Video
    in AUDIO_EXTENSIONS -> MediaKind.Audio
    else -> null
}

/** Every extension [mediaKindOf] recognises, for folder listings. */
val MEDIA_EXTENSIONS: Set<String> = VIDEO_EXTENSIONS + AUDIO_EXTENSIONS
