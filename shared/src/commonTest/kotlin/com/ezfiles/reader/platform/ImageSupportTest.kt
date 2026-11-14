package com.ezfiles.reader.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageSupportTest {
    /** Where a stored pixel lands, after moving the result back so its top-left corner is (0, 0). */
    private fun upright(orientation: Int, x: Int, y: Int, width: Int, height: Int): Pair<Int, Int> {
        val t = exifTransform(orientation) ?: return x to y
        val mapped = listOf(0 to 0, width - 1 to 0, 0 to height - 1, width - 1 to height - 1)
            .map { (cx, cy) -> t.xx * cx + t.xy * cy to t.yx * cx + t.yy * cy }
        val left = mapped.minOf { it.first }
        val top = mapped.minOf { it.second }
        return t.xx * x + t.xy * y - left to t.yx * x + t.yy * y - top
    }

    @Test
    fun all_eight_orientations_have_the_standard_transform() {
        assertNull(exifTransform(1))
        assertEquals(PixelTransform(-1, 0, 0, 1), exifTransform(2))
        assertEquals(PixelTransform(-1, 0, 0, -1), exifTransform(3))
        assertEquals(PixelTransform(1, 0, 0, -1), exifTransform(4))
        assertEquals(PixelTransform(0, 1, 1, 0), exifTransform(5))
        assertEquals(PixelTransform(0, -1, 1, 0), exifTransform(6))
        assertEquals(PixelTransform(0, -1, -1, 0), exifTransform(7))
        assertEquals(PixelTransform(0, 1, -1, 0), exifTransform(8))
    }

    /**
     * From the EXIF definition of each value: which corner of the shown picture
     * the stored top-left pixel belongs in, for a stored 4 by 3 picture.
     */
    @Test
    fun the_stored_top_left_pixel_lands_where_exif_says() {
        val width = 4
        val height = 3
        val wide = width - 1
        val tall = height - 1
        assertEquals(0 to 0, upright(1, 0, 0, width, height))
        assertEquals(wide to 0, upright(2, 0, 0, width, height))
        assertEquals(wide to tall, upright(3, 0, 0, width, height))
        assertEquals(0 to tall, upright(4, 0, 0, width, height))
        assertEquals(0 to 0, upright(5, 0, 0, width, height))
        // Sideways: the shown picture is 3 by 4.
        assertEquals(tall to 0, upright(6, 0, 0, width, height))
        assertEquals(tall to wide, upright(7, 0, 0, width, height))
        assertEquals(0 to wide, upright(8, 0, 0, width, height))
    }

    @Test
    fun the_stored_top_row_becomes_the_side_exif_says_for_rotations() {
        // Orientation 6 means the stored top row is the right side when shown.
        assertEquals(2 to 3, upright(6, 3, 0, 4, 3))
        // Orientation 8 means it is the left side.
        assertEquals(0 to 0, upright(8, 3, 0, 4, 3))
    }

    @Test
    fun values_outside_the_standard_do_nothing() {
        for (value in listOf(-1, 0, 9, 65535)) assertNull(exifTransform(value))
    }

    @Test
    fun declared_size_limit_allows_real_photos_and_refuses_bombs() {
        checkDeclaredSize(16320, 12240) // A 200 MP phone photo.
        checkDeclaredSize(16384, 16384)
        assertFailsWith<ImageTooLargeException> { checkDeclaredSize(16385, 16384) }
        // Would overflow if multiplied as Int.
        assertFailsWith<ImageTooLargeException> { checkDeclaredSize(65535, 65535) }
        assertFailsWith<ImageTooLargeException> { checkDeclaredSize(Int.MAX_VALUE, Int.MAX_VALUE) }
    }

    @Test
    fun sampling_stays_sharp_without_decoding_too_many_pixels() {
        assertEquals(1, imageSampleSize(1000, 800, 4096))
        assertEquals(1, imageSampleSize(4096, 3000, 4096))
        // Sampling by two would drop below what the screen wants.
        assertEquals(1, imageSampleSize(6000, 4000, 4096))
        assertEquals(2, imageSampleSize(8192, 2000, 4096))
        // 67 MP at full size would be over the limit, so it goes coarser than the rule above.
        assertEquals(2, imageSampleSize(8191, 8191, 4096))
        assertEquals(8, imageSampleSize(16384, 16384, 1600))
    }

    @Test
    fun the_sampled_size_never_exceeds_the_pixel_limit() {
        for ((width, height) in listOf(16384 to 16384, 8191 to 8191, 100_000 to 2000, 5657 to 5657)) {
            for (maxDimension in listOf(256, 1600, 4096)) {
                val sample = imageSampleSize(width, height, maxDimension)
                assertTrue(width.toLong() / sample * (height.toLong() / sample) <= MAX_SAMPLED_PIXELS)
            }
        }
    }

    @Test
    fun fitting_keeps_the_shape_and_never_reaches_zero() {
        assertEquals(800 to 600, fitWithin(800, 600, 4096))
        assertEquals(4096 to 2048, fitWithin(8192, 4096, 4096))
        assertEquals(2048 to 4096, fitWithin(4096, 8192, 4096))
        assertEquals(4096 to 1, fitWithin(30000, 1, 4096))
    }

    private fun ascii(text: String) = text.encodeToByteArray()

    private fun iso(major: String, vararg compatible: String): ByteArray {
        val brands = compatible.joinToString("")
        val size = 16 + brands.length
        return byteArrayOf(0, 0, 0, size.toByte()) + ascii("ftyp$major\u0000\u0000\u0000\u0000$brands") + ascii("meta")
    }

    private fun webp(chunk: String, flags: Int): ByteArray =
        ascii("RIFF\u0000\u0000\u0000\u0000WEBP$chunk\u0000\u0000\u0000\u0000") + flags.toByte() + ByteArray(9)

    @Test
    fun sniffing_tells_the_kinds_apart() {
        assertEquals(ImageKind.Gif, sniffImage(ascii("GIF89a....")))
        assertEquals(ImageKind.Gif, sniffImage(ascii("GIF87a....")))
        assertEquals(ImageKind.AnimatedWebp, sniffImage(webp("VP8X", 0x02)))
        assertEquals(ImageKind.AnimatedWebp, sniffImage(webp("VP8X", 0x12)))
        assertEquals(ImageKind.Other, sniffImage(webp("VP8X", 0x10)))
        assertEquals(ImageKind.Other, sniffImage(webp("VP8L", 0x02)))
        assertEquals(ImageKind.Heif, sniffImage(iso("heic", "mif1", "heic")))
        assertEquals(ImageKind.Heif, sniffImage(iso("mif1", "heic")))
        assertEquals(ImageKind.Heif, sniffImage(iso("mif1")))
        assertEquals(ImageKind.Avif, sniffImage(iso("avif", "mif1")))
        assertEquals(ImageKind.Avif, sniffImage(iso("mif1", "miaf", "avif")))
        // Video in the same container, and other pictures.
        assertEquals(ImageKind.Other, sniffImage(iso("mp42", "avif")))
        assertEquals(ImageKind.Other, sniffImage(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
    }

    @Test
    fun sniffing_survives_truncated_and_hostile_headers() {
        assertEquals(ImageKind.Other, sniffImage(ByteArray(0)))
        assertEquals(ImageKind.Other, sniffImage(ascii("GIF")))
        assertEquals(ImageKind.Other, sniffImage(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8X")))
        assertEquals(ImageKind.Other, sniffImage(ascii("\u0000\u0000\u0000\u0000ftyp")))
        // The box claims far more than the file holds, or less than its own header.
        val huge = byteArrayOf(-1, -1, -1, -1) + ascii("ftypmif1\u0000\u0000\u0000\u0000avif")
        assertEquals(ImageKind.Avif, sniffImage(huge))
        val tiny = byteArrayOf(0, 0, 0, 1) + ascii("ftypmif1\u0000\u0000\u0000\u0000avif")
        assertEquals(ImageKind.Heif, sniffImage(tiny))
    }

    @Test
    fun only_gif_and_animated_webp_try_to_animate() {
        assertEquals(setOf(ImageKind.Gif, ImageKind.AnimatedWebp), ImageKind.entries.filter { it.mayAnimate }.toSet())
    }
}
