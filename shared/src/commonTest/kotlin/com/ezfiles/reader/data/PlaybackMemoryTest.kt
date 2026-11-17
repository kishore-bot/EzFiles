package com.ezfiles.reader.data

import com.ezfiles.reader.domain.DocumentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackMemoryTest {
    private class Memory : KeyValueStorage {
        val values = HashMap<String, String?>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String?) { values[key] = value }
    }

    private val movie = DocumentRef("content://v/movie.mkv", "movie.mkv", sizeBytes = 7_310_000_000)
    private val hour = 60 * 60_000L
    private val film = 3 * hour

    @Test
    fun aFilmContinuesAFewSecondsBeforeWhereItStopped() {
        val storage = Memory()
        PlaybackMemory(storage, { 1L }).save(movie, hour, film, audio = false, audioLanguage = "hi")
        // A new app session reads it back from storage.
        val point = PlaybackMemory(storage, { 2L }).resumePoint(movie, film, audio = false)
        assertEquals(ResumePoint(hour - 3_000, "hi", null), point)
        assertEquals(2 * hour, PlaybackMemory(storage, { 2L }).remainingMs(movie))
    }

    @Test
    fun finishedBarelyStartedAndShortThingsStartOver() {
        val memory = PlaybackMemory(Memory(), { 1L })
        memory.save(movie, film - 20_000, film, audio = false)
        assertNull(memory.resumePoint(movie, film, audio = false))
        memory.save(movie, 5_000, film, audio = false)
        assertNull(memory.resumePoint(movie, film, audio = false))
        val clip = DocumentRef("content://v/clip.mp4", "clip.mp4", sizeBytes = 10)
        memory.save(clip, 50_000, 90_000, audio = false)
        assertNull(memory.resumePoint(clip, 90_000, audio = false))
        // A four-minute song starts over; a long podcast continues.
        val song = DocumentRef("content://a/song.mp3", "song.mp3", sizeBytes = 10)
        memory.save(song, 120_000, 240_000, audio = true)
        assertNull(memory.resumePoint(song, 240_000, audio = true))
        val podcast = DocumentRef("content://a/talk.mp3", "talk.mp3", sizeBytes = 10)
        memory.save(podcast, 20 * 60_000L, hour, audio = true)
        assertEquals(20 * 60_000L - 3_000, memory.resumePoint(podcast, hour, audio = true)?.positionMs)
    }

    @Test
    fun aReplacedFileStartsOverButTrackChoicesSurviveFinishing() {
        val memory = PlaybackMemory(Memory(), { 1L })
        memory.save(movie, hour, film, audio = false, audioLanguage = "hi", subtitles = "en")
        assertNull(memory.resumePoint(movie.copy(sizeBytes = 1), film, audio = false))
        memory.save(movie, film, film, audio = false)
        assertEquals(ResumePoint(0, "hi", "en"), memory.resumePoint(movie, film, audio = false))
    }

    @Test
    fun onlyTheMostRecentFilesAreKept() {
        val memory = PlaybackMemory(Memory(), { 1L })
        repeat(PlaybackMemory.MAX_ENTRIES + 5) { i ->
            memory.save(DocumentRef("content://v/$i.mkv", "$i.mkv", sizeBytes = 1), hour, film, audio = false)
        }
        assertNull(memory.remainingMs(DocumentRef("content://v/0.mkv", "0.mkv", sizeBytes = 1)))
        assertEquals(2 * hour, memory.remainingMs(DocumentRef("content://v/304.mkv", "304.mkv", sizeBytes = 1)))
    }
}
