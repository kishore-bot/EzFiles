package com.ezfiles.reader.data

import com.ezfiles.engine.ffi.imagingBinarize
import com.ezfiles.engine.ffi.imagingSharpness
import com.ezfiles.engine.ffi.imagingSimilarity
import com.ezfiles.engine.ffi.imagingTextAngle
import com.ezfiles.reader.platform.LumaImage

/**
 * Scanning helpers in Rust (`document-imaging`), over grey pictures with one
 * byte per pixel. All are blocking and CPU bound: call them off the main thread.
 */
object RustImaging {
    /** `false` too when the picture has too little detail to judge (a blank page). */
    fun isBlurry(picture: LumaImage): Boolean =
        imagingSharpness(picture.bytes, picture.width.toUInt(), picture.height.toUInt()).blurry

    /** Sauvola black and white: 0 for ink, 255 (as a byte, -1) for paper. */
    fun binarize(luma: ByteArray, width: Int, height: Int): ByteArray =
        imagingBinarize(luma, width.toUInt(), height.toUInt())

    /** Degrees the text slopes down to the right, or `null` when there is nothing to straighten. */
    fun textAngle(picture: LumaImage): Float? =
        imagingTextAngle(picture.bytes, picture.width.toUInt(), picture.height.toUInt())

    /** SSIM of two same-sized pictures, 1.0 when identical. */
    fun similarity(a: ByteArray, b: ByteArray, width: Int, height: Int): Float? =
        imagingSimilarity(a, b, width.toUInt(), height.toUInt())
}
