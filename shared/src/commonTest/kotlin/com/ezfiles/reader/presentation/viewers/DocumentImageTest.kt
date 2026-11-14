package com.ezfiles.reader.presentation.viewers

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentImageTest {
    @Test
    fun wide_pictures_fit_the_reading_width_keeping_their_aspect_ratio() {
        assertEquals(360.dp to 180.dp, pictureBox(360.dp, 720f, 360f, 0, 0))
        assertEquals(360.dp to 270.dp, pictureBox(360.dp, null, null, 4000, 3000))
    }

    @Test
    fun small_pictures_are_not_stretched() {
        assertEquals(72.dp to 36.dp, pictureBox(360.dp, 72f, 36f, 0, 0))
        assertEquals(100.dp to 50.dp, pictureBox(360.dp, null, null, 100, 50))
    }

    @Test
    fun size_hints_win_over_the_picture_so_the_height_does_not_change_on_load() {
        assertEquals(pictureBox(360.dp, 720f, 360f, 0, 0), pictureBox(360.dp, 720f, 360f, 800, 800))
    }

    @Test
    fun height_is_unknown_without_hints_or_a_loaded_picture() {
        assertEquals(360.dp to null, pictureBox(360.dp, null, null, 0, 0))
        assertEquals(200.dp to null, pictureBox(360.dp, 200f, null, 0, 0))
        assertEquals(200.dp to 100.dp, pictureBox(360.dp, 200f, null, 400, 200))
    }

    @Test
    fun extreme_sizes_stay_within_what_can_be_laid_out() {
        assertEquals(24.dp to 1200.dp, pictureBox(360.dp, 1f, 1e30f, 0, 0))
        assertEquals(360.dp to 24.dp, pictureBox(360.dp, 1e30f, 1f, 0, 0))
        assertEquals(24.dp to 24.dp, pictureBox(360.dp, 0.001f, 0.001f, 0, 0))
    }
}
