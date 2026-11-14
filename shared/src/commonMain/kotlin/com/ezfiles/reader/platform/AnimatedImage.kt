package com.ezfiles.reader.platform

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * An animation the platform plays by itself. It starts stopped. Call everything
 * from the main thread, and [close] it when done.
 */
interface AnimatedImage {
    val width: Int
    val height: Int

    fun start()

    fun stop()

    /** Draws the current frame at [width] by [height]; redraws by itself as frames change. */
    fun draw(scope: DrawScope)

    fun close()
}

/**
 * An animated GIF or WebP no larger than [maxDimension] on its longer side, or
 * `null` when it can't be animated here: a still picture, an older system, or
 * any failure. The caller then shows the first frame through [decodeImage].
 * Keeps [bytes], so they must not change.
 */
expect fun decodeAnimatedImage(bytes: ByteArray, maxDimension: Int): AnimatedImage?
