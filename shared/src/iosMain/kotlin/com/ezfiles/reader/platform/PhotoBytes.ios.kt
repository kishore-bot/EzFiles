package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.data.IosDocumentAccess
import com.ezfiles.reader.domain.ReadLimits
import com.ezfiles.reader.domain.DocumentRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.close
import platform.posix.read

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberPhotoBytes(): PhotoBytes = remember {
    object : PhotoBytes {
        override suspend fun isReadable(ref: DocumentRef): Boolean = withContext(Dispatchers.IO) {
            try {
                val fd = IosDocumentAccess().openDescriptor(ref, ReadLimits.Default)
                close(fd)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }

        override suspend fun read(ref: DocumentRef, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
            try {
                val fd = IosDocumentAccess().openDescriptor(ref, ReadLimits.Default)
                try {
                    collectPhotoBytes(ref.sizeBytes, maxBytes) { target, offset, count ->
                        val result = target.usePinned { read(fd, it.addressOf(offset), count.toULong()) }
                        check(result >= 0) { "This image could not be read." }
                        if (result == 0L) -1 else result.toInt()
                    }
                } finally {
                    close(fd)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (large: ImageTooLargeException) {
                throw large
            } catch (_: Exception) {
                null
            }
        }
    }
}

actual fun decodePhotoImage(bytes: ByteArray, maxDimension: Int): ImageBitmap? = decodeImage(bytes, maxDimension)
