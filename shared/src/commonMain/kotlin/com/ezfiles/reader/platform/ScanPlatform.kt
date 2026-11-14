package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.domain.TextRecognizer
import com.ezfiles.reader.presentation.scan.PageQuad
import com.ezfiles.reader.presentation.scan.ScanFilter

/** A picture's brightness, one unsigned byte per pixel, row by row. */
class LumaImage(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * The app-private working folder of a scan. Nothing here is in shared storage,
 * and it lasts until [clear], the next launch of the app, or the system
 * clearing its cache. Pictures are bounded in size as they come in; failures
 * are [com.ezfiles.reader.domain.DocumentException]s, including running out of memory.
 */
interface ScanWorkspace {
    /** Where the camera should write a new picture for page [id], as a URI. */
    fun captureTarget(id: String): String

    /** Decodes the picture at [uri], upright and downsampled, into page [id]'s source. */
    suspend fun importSource(id: String, uri: String)

    /** The source's brightness, at most [maxSide] pixels on its longer side. */
    suspend fun sourceLuma(id: String, maxSide: Int): LumaImage

    /** Straightens [quad] out of the source, applies [filter] and replaces page [id]'s output. */
    suspend fun render(id: String, quad: PageQuad, filter: ScanFilter)

    /** Whether both of page [id]'s pictures are still there; the system can clear a cache at any time. */
    suspend fun exists(id: String): Boolean

    suspend fun preview(id: String, kind: ScanPictureKind, maxSide: Int): ImageBitmap

    /** A URI for reading page [id]'s picture. */
    fun pictureUri(id: String, kind: ScanPictureKind): String

    /** The encoded output pictures of [ids], in order. */
    suspend fun readOutputs(ids: List<String>): List<ByteArray>

    suspend fun deletePage(id: String)

    /** The saved state of an unfinished job, restored if the process is killed in the background. */
    suspend fun readState(): String?

    suspend fun writeState(state: String)

    /** Deletes everything in this workspace. */
    suspend fun clear()

    /** Writes [text] as a paginated PDF to [target], from the system's save dialog. */
    suspend fun writeTextPdf(text: String, target: String)

    /** Removes a file the save dialog created when saving into it failed or was cancelled. */
    suspend fun deleteDocument(target: String)
}

enum class ScanPictureKind { Source, Output }

/** [folder] keeps the tools' working files apart. */
@Composable
expect fun rememberScanWorkspace(folder: String): ScanWorkspace

@Composable
expect fun rememberTextRecognizer(): TextRecognizer

enum class CameraStatus {
    /** The system hasn't asked the user yet. */
    NotAsked,
    Granted,

    /** Refused; asking again can still show the system's dialog. */
    Refused,

    /** Refused for good: only the system's settings can change it. */
    RefusedForGood,

    /** This device has no camera. */
    NoCamera,
}

interface CameraAccess {
    val status: CameraStatus

    /** Shows the system's permission dialog. */
    fun request()

    fun openSettings()
}

/** The camera permission, requested only through [CameraAccess.request]. */
@Composable
expect fun rememberCameraAccess(): CameraAccess

fun interface CameraCapturer {
    /** Takes a picture into [targetUri], from [ScanWorkspace.captureTarget]. */
    suspend fun capture(targetUri: String)
}

/**
 * The live camera. [onReady] gets the capturer once the camera is running and
 * `null` when it stops; [onFailure] reports a camera that can't be used. Only
 * compose this with the permission granted. [onFrame], when given, gets a
 * small grey copy of the view a few times a second, on a background thread.
 */
@Composable
expect fun CameraViewfinder(
    modifier: Modifier,
    onReady: (CameraCapturer?) -> Unit,
    onFailure: (String) -> Unit,
    onFrame: ((LumaImage) -> Unit)? = null,
)
