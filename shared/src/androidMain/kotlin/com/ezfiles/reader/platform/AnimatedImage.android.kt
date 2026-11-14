package com.ezfiles.reader.platform

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import java.nio.ByteBuffer

actual fun decodeAnimatedImage(bytes: ByteArray, maxDimension: Int): AnimatedImage? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || !sniffImage(bytes).mayAnimate) return null
    return try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            limitImageDecoderSize(decoder, info, maxDimension)
        }
        // A one-frame GIF comes back as a plain drawable.
        (drawable as? AnimatedImageDrawable)?.let(::PlatformAnimatedImage)
    } catch (_: Exception) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private class PlatformAnimatedImage(private val drawable: AnimatedImageDrawable) : AnimatedImage {
    private val handler = Handler(Looper.getMainLooper())

    /** Read while drawing, so a change redraws. The hardware renderer advances frames itself. */
    private var invalidations by mutableIntStateOf(0)

    // The drawable holds its callback weakly, so this field keeps it alive.
    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            invalidations++
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, whenMillis: Long) {
            handler.postAtTime(what, whenMillis)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what)
        }
    }

    override val width = drawable.intrinsicWidth.coerceAtLeast(1)
    override val height = drawable.intrinsicHeight.coerceAtLeast(1)

    init {
        drawable.setBounds(0, 0, width, height)
        drawable.callback = callback
    }

    override fun start() = drawable.start()

    override fun stop() = drawable.stop()

    override fun draw(scope: DrawScope) {
        invalidations
        scope.drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }

    override fun close() {
        drawable.stop()
        drawable.callback = null
        handler.removeCallbacksAndMessages(null)
    }
}
