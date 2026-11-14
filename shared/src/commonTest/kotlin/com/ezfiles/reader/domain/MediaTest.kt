package com.ezfiles.reader.domain

import com.ezfiles.reader.presentation.viewers.clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaTest {
    @Test
    fun media_is_told_apart_by_extension() {
        assertEquals(MediaKind.Video, mediaKindOf("Clip.MP4"))
        assertEquals(MediaKind.Video, mediaKindOf("a.b.webm"))
        assertEquals(MediaKind.Audio, mediaKindOf("song.m4a"))
        assertEquals(MediaKind.Audio, mediaKindOf("tone.wav"))
        assertNull(mediaKindOf("report.pdf"))
        assertNull(mediaKindOf("mp4"))
    }

    @Test
    fun clock_shows_minutes_and_seconds_and_hours_when_needed() {
        assertEquals("0:00", clock(0))
        assertEquals("1:05", clock(65_000))
        assertEquals("1:01:05", clock(3_665_000))
        assertEquals("0:00", clock(-5))
    }
}
