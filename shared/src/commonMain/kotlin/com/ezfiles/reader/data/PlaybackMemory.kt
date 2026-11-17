package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DocumentRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where to pick a video up, and the tracks chosen for it. */
data class ResumePoint(
    val positionMs: Long,
    /** Language of the chosen soundtrack ("hi"), if one was chosen. */
    val audioLanguage: String?,
    /** Subtitle language, "off", or null when never chosen. */
    val subtitles: String?,
)

/**
 * Remembers how far each video (and long audio) was played, so opening it again continues there.
 * Kept on the device in the app's own storage, keyed by the file's identity and size, so a replaced
 * file starts over. Only the most recent [MAX_ENTRIES] are kept.
 *
 * The rules, so it doesn't get in the way:
 * - Clips shorter than [MIN_VIDEO_MS] (audio: [MIN_AUDIO_MS], so songs start over) are not remembered.
 * - Less than [MIN_PROGRESS_MS] watched, or finished (the last 5 % or 30 s): next time starts at 0.
 * - Resuming goes back [REWIND_MS], to give a moment of context.
 */
open class PlaybackMemory internal constructor(private val storage: KeyValueStorage?, private val now: () -> Long) {
    @Serializable
    private data class Entry(
        val key: String,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
        val audioLanguage: String? = null,
        val subtitles: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var entries: MutableList<Entry> = load()

    /** What to resume, or null to start from the beginning. */
    open fun resumePoint(ref: DocumentRef, durationMs: Long, audio: Boolean): ResumePoint? {
        val entry = entries.firstOrNull { it.key == key(ref) } ?: return null
        val tracks = ResumePoint(0, entry.audioLanguage, entry.subtitles)
        val duration = durationMs.takeIf { it > 0 } ?: entry.durationMs
        if (!worthRemembering(duration, audio) || entry.positionMs < MIN_PROGRESS_MS || finished(entry.positionMs, duration)) {
            return tracks.takeIf { it.audioLanguage != null || it.subtitles != null }
        }
        return tracks.copy(positionMs = (entry.positionMs - REWIND_MS).coerceAtLeast(0))
    }

    /** Saves the position (and tracks, when known). Called every few seconds while playing, and on pause. */
    open fun save(ref: DocumentRef, positionMs: Long, durationMs: Long, audio: Boolean, audioLanguage: String? = null, subtitles: String? = null) {
        if (durationMs <= 0) return
        val key = key(ref)
        val old = entries.firstOrNull { it.key == key }
        entries.removeAll { it.key == key }
        // Finished or barely started: keep only the track choices.
        val position = if (!worthRemembering(durationMs, audio) || finished(positionMs, durationMs)) 0 else positionMs
        entries.add(0, Entry(key, position, durationMs, now(), audioLanguage ?: old?.audioLanguage, subtitles ?: old?.subtitles))
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.lastIndex)
        persist()
    }

    /** Milliseconds left to watch, for the library ("1h 12m left"), or null when not part-watched. */
    open fun remainingMs(ref: DocumentRef): Long? {
        val entry = entries.firstOrNull { it.key == key(ref) } ?: return null
        if (entry.positionMs < MIN_PROGRESS_MS || finished(entry.positionMs, entry.durationMs)) return null
        return entry.durationMs - entry.positionMs
    }

    open fun forget(ref: DocumentRef) {
        if (entries.removeAll { it.key == key(ref) }) persist()
    }

    private fun worthRemembering(durationMs: Long, audio: Boolean) = durationMs >= if (audio) MIN_AUDIO_MS else MIN_VIDEO_MS

    private fun finished(positionMs: Long, durationMs: Long) =
        durationMs > 0 && (positionMs >= durationMs * 95 / 100 || durationMs - positionMs <= END_MS)

    private fun key(ref: DocumentRef) = "${ref.identity}|${ref.sizeBytes ?: -1}"

    private fun load(): MutableList<Entry> = try {
        storage?.get(STORAGE_KEY)?.let { json.decodeFromString<List<Entry>>(it).toMutableList() } ?: ArrayList()
    } catch (_: Exception) {
        ArrayList()
    }

    private fun persist() {
        storage?.put(STORAGE_KEY, json.encodeToString(entries.toList()))
    }

    internal companion object {
        const val STORAGE_KEY = "playback_positions_v1"
        const val MAX_ENTRIES = 300
        const val MIN_VIDEO_MS = 2 * 60_000L
        const val MIN_AUDIO_MS = 10 * 60_000L
        const val MIN_PROGRESS_MS = 10_000L
        const val END_MS = 30_000L
        const val REWIND_MS = 3_000L
    }
}

/** For previews and tests: remembers nothing. */
object NoPlaybackMemory : PlaybackMemory(null, { 0L })
