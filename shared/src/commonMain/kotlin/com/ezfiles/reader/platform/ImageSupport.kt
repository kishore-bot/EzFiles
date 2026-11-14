package com.ezfiles.reader.platform

/**
 * Pictures declaring more pixels than this are refused before decoding: 16384
 * by 16384, comfortably above a 200 MP phone photo. Downsampling bounds the
 * result, but a decoder still has to walk (and for interlaced PNG, buffer) what
 * the file declares.
 */
internal const val MAX_DECLARED_PIXELS = 1L shl 28

/** Sampled decodes above this are sampled further: 128 MiB at 4 bytes a pixel. */
internal const val MAX_SAMPLED_PIXELS = 32_000_000L

/** A picture too big to decode safely, whether by its declared size or by running out of memory. */
class ImageTooLargeException : Exception("The image is too large to decode")

/** @throws ImageTooLargeException if the declared size is over [MAX_DECLARED_PIXELS] */
internal fun checkDeclaredSize(width: Int, height: Int) {
    if (width.toLong() * height > MAX_DECLARED_PIXELS) throw ImageTooLargeException()
}

/**
 * The power of two to decode at: the coarsest that is still at least
 * [maxDimension] on the longer side, so the final scale down stays sharp, and
 * then coarser if that many pixels would exceed [MAX_SAMPLED_PIXELS].
 */
internal fun imageSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    val longer = maxOf(width, height)
    var sample = 1
    while (longer / (sample * 2) >= maxDimension) sample *= 2
    while (width.toLong() / sample * (height.toLong() / sample) > MAX_SAMPLED_PIXELS) sample *= 2
    return sample
}

/** The size to show a picture at: unchanged if it fits [maxDimension], never below one pixel. */
internal fun fitWithin(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
    val longer = maxOf(width, height)
    if (longer <= maxDimension) return width to height
    val scale = maxDimension.toDouble() / longer
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/** What the first bytes of an image say about how to decode it. */
internal enum class ImageKind(val mayAnimate: Boolean = false) {
    Gif(mayAnimate = true),
    AnimatedWebp(mayAnimate = true),
    Heif,
    Avif,
    Other,
}

private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis")
private val AVIF_BRANDS = setOf("avif", "avis")
private val GENERIC_BRANDS = setOf("mif1", "msf1")
private const val WEBP_ANIMATION_FLAG = 0x02

internal fun sniffImage(bytes: ByteArray): ImageKind = when {
    bytes.ascii(0, 6) == "GIF87a" || bytes.ascii(0, 6) == "GIF89a" -> ImageKind.Gif
    bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP" && bytes.ascii(12, 4) == "VP8X" &&
        bytes.size > 20 && bytes[20].toInt() and WEBP_ANIMATION_FLAG != 0 -> ImageKind.AnimatedWebp
    bytes.ascii(4, 4) == "ftyp" -> sniffIsoBrands(bytes)
    else -> ImageKind.Other
}

/** HEIF and AVIF share a container: the major brand decides, or the compatible ones if it is generic. */
private fun sniffIsoBrands(bytes: ByteArray): ImageKind {
    val major = bytes.ascii(8, 4)
    if (major in AVIF_BRANDS) return ImageKind.Avif
    if (major in HEIF_BRANDS) return ImageKind.Heif
    if (major !in GENERIC_BRANDS) return ImageKind.Other
    val boxSize = bytes.take(4).fold(0L) { size, byte -> size shl 8 or (byte.toLong() and 0xFF) }
    val end = minOf(boxSize, bytes.size.toLong()).toInt()
    val compatible = (16 until end - 3 step 4).map { bytes.ascii(it, 4) }
    return if (compatible.any { it in AVIF_BRANDS }) ImageKind.Avif else ImageKind.Heif
}

private fun ByteArray.ascii(start: Int, length: Int): String? =
    if (size < start + length) null else decodeToString(start, start + length)

/** A rotation or mirror as x' = xx*x + xy*y, y' = yx*x + yy*y, around the top-left corner. */
internal data class PixelTransform(val xx: Int, val xy: Int, val yx: Int, val yy: Int)

/**
 * How to turn stored pixels upright, for an EXIF orientation value (1 to 8);
 * `null` when there is nothing to do: orientation 1 or a value outside the
 * standard. y points down, so a positive quarter turn is clockwise.
 */
internal fun exifTransform(orientation: Int): PixelTransform? = when (orientation) {
    2 -> PixelTransform(-1, 0, 0, 1) // Mirrored left to right.
    3 -> PixelTransform(-1, 0, 0, -1) // Half turn.
    4 -> PixelTransform(1, 0, 0, -1) // Mirrored top to bottom.
    5 -> PixelTransform(0, 1, 1, 0) // Transpose.
    6 -> PixelTransform(0, -1, 1, 0) // Quarter turn clockwise.
    7 -> PixelTransform(0, -1, -1, 0) // Transverse.
    8 -> PixelTransform(0, 1, -1, 0) // Quarter turn anticlockwise.
    else -> null
}
