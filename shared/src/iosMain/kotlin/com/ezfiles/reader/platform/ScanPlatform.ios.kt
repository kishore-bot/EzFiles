package com.ezfiles.reader.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.RecognizedText
import com.ezfiles.reader.domain.TextRecognizer
import com.ezfiles.reader.presentation.scan.PageQuad
import com.ezfiles.reader.presentation.scan.ScanFilter

// iOS is parked: these keep the shared module portable until it resumes.
private fun parked() = DocumentException(DocumentError.Unsupported("This isn't available on iOS yet."))

private object IosTextRecognizer : TextRecognizer {
    override suspend fun recognize(imageUri: String): RecognizedText = throw parked()
}

private object IosScanWorkspace : ScanWorkspace {
    override fun captureTarget(id: String): String = throw parked()

    override suspend fun importSource(id: String, uri: String) = throw parked()

    override suspend fun sourceLuma(id: String, maxSide: Int): LumaImage = throw parked()

    override suspend fun render(id: String, quad: PageQuad, filter: ScanFilter) = throw parked()

    override suspend fun exists(id: String) = false

    override suspend fun preview(id: String, kind: ScanPictureKind, maxSide: Int): ImageBitmap = throw parked()

    override fun pictureUri(id: String, kind: ScanPictureKind): String = throw parked()

    override suspend fun readOutputs(ids: List<String>): List<ByteArray> = throw parked()

    override suspend fun deletePage(id: String) = Unit

    override suspend fun readState(): String? = null

    override suspend fun writeState(state: String) = Unit

    override suspend fun clear() = Unit

    override suspend fun writeTextPdf(text: String, target: String) = throw parked()

    override suspend fun deleteDocument(target: String) = Unit
}

@Composable
actual fun rememberScanWorkspace(folder: String): ScanWorkspace = IosScanWorkspace

@Composable
actual fun rememberTextRecognizer(): TextRecognizer = IosTextRecognizer

@Composable
actual fun rememberCameraAccess(): CameraAccess = remember {
    object : CameraAccess {
        override val status = CameraStatus.NoCamera

        override fun request() = Unit

        override fun openSettings() = Unit
    }
}

@Composable
actual fun CameraViewfinder(modifier: Modifier, onReady: (CameraCapturer?) -> Unit, onFailure: (String) -> Unit, onFrame: ((LumaImage) -> Unit)?) = Unit
