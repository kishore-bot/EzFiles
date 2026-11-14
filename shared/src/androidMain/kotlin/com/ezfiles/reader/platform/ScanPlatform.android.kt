package com.ezfiles.reader.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ezfiles.reader.data.AndroidScanWorkspace
import com.ezfiles.reader.data.AndroidTextRecognizer
import com.ezfiles.reader.domain.DocumentError
import com.ezfiles.reader.domain.DocumentException
import com.ezfiles.reader.domain.TextRecognizer
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine

/** About 5 MP: enough for a page at 200+ dpi, and about what the rest of the pipeline keeps. */
private val CAPTURE_SIZE = Size(2592, 1944)

@Composable
actual fun rememberScanWorkspace(folder: String): ScanWorkspace {
    val context = LocalContext.current
    return remember(folder) { AndroidScanWorkspace(context.applicationContext, folder) }
}

@Composable
actual fun rememberTextRecognizer(): TextRecognizer {
    val context = LocalContext.current
    return remember { AndroidTextRecognizer(context.applicationContext) }
}

@Composable
actual fun rememberCameraAccess(): CameraAccess {
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var refused by rememberSaveable { mutableStateOf(false) }
    var refusedForGood by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        refused = !allowed
        // No rationale after a refusal means the system won't show its dialog again.
        refusedForGood = !allowed && context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
    }
    // The user may have changed it in the system's settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasCameraPermission(context) }
    return remember {
        object : CameraAccess {
            override val status: CameraStatus
                get() = when {
                    !hasCamera -> CameraStatus.NoCamera
                    granted -> CameraStatus.Granted
                    refusedForGood -> CameraStatus.RefusedForGood
                    refused -> CameraStatus.Refused
                    else -> CameraStatus.NotAsked
                }

            override fun request() = launcher.launch(Manifest.permission.CAMERA)

            override fun openSettings() {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    // A device without this settings screen; the gallery still works.
                }
            }
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
actual fun CameraViewfinder(
    modifier: Modifier,
    onReady: (CameraCapturer?) -> Unit,
    onFailure: (String) -> Unit,
    onFrame: ((LumaImage) -> Unit)?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ready by rememberUpdatedState(onReady)
    val failed by rememberUpdatedState(onFailure)
    val frames by rememberUpdatedState(onFrame)
    val analyzing = onFrame != null
    val previewView = remember {
        PreviewView(context).apply {
            // Fit, not fill, so the preview shows everything the picture will contain.
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    AndroidView({ previewView }, modifier)
    LaunchedEffect(lifecycleOwner, analyzing) {
        var provider: ProcessCameraProvider? = null
        var analysisThread: ExecutorService? = null
        try {
            val cameras = ProcessCameraProvider.awaitInstance(context)
            provider = cameras
            val selector = when {
                cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                else -> {
                    failed("No camera could be found. You can still use a picture from your gallery.")
                    return@LaunchedEffect
                }
            }
            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(CAPTURE_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
                )
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(resolution)
                .build()
            cameras.unbindAll()
            val analysis = if (analyzing) {
                val thread = Executors.newSingleThreadExecutor { task -> Thread(task, "scan-frames").apply { isDaemon = true } }
                analysisThread = thread
                frameAnalysis(thread) { frames }
            } else {
                null
            }
            try {
                if (analysis != null) {
                    cameras.bindToLifecycle(lifecycleOwner, selector, preview, capture, analysis)
                } else {
                    cameras.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                }
            } catch (_: IllegalArgumentException) {
                // Some cameras can't run three streams at once: the shutter still works, without auto-capture.
                cameras.unbindAll()
                cameras.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            }
            ready(
                CameraCapturer { target ->
                    previewView.display?.rotation?.let { capture.targetRotation = it }
                    capture.takePictureTo(context, target)
                },
            )
            awaitCancellation()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed("The camera couldn't be started. It may be in use by another app. You can still use a picture from your gallery.")
        } finally {
            ready(null)
            provider?.unbindAll()
            analysisThread?.shutdown()
        }
    }
}

/** A small grey copy of the view, a few times a second, for auto-capture. */
private fun frameAnalysis(thread: Executor, sink: () -> ((LumaImage) -> Unit)?): ImageAnalysis {
    val analysis = ImageAnalysis.Builder()
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build(),
        )
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build()
    var last = 0L
    analysis.setAnalyzer(thread) { image ->
        try {
            val now = SystemClock.uptimeMillis()
            val receiver = sink()
            if (receiver != null && now - last >= FRAME_INTERVAL_MS) {
                last = now
                receiver(image.toLuma(FRAME_SIDE))
            }
        } catch (_: Exception) {
            // A frame that can't be read is skipped; the next one comes along shortly.
        } finally {
            image.close()
        }
    }
    return analysis
}

/** The brightness plane, sampled down so its longer side is about [maxSide]. */
private fun ImageProxy.toLuma(maxSide: Int): LumaImage {
    val plane = planes[0]
    val buffer = plane.buffer
    val step = maxOf(1, (maxOf(width, height) + maxSide - 1) / maxSide)
    val w = width / step
    val h = height / step
    val out = ByteArray(w * h)
    for (y in 0 until h) {
        val row = y * step * plane.rowStride
        for (x in 0 until w) out[y * w + x] = buffer.get(row + x * step * plane.pixelStride)
    }
    return LumaImage(out, w, h)
}

private val ANALYSIS_SIZE = Size(640, 480)
private const val FRAME_SIDE = 360
private const val FRAME_INTERVAL_MS = 200L

private suspend fun ImageCapture.takePictureTo(context: Context, targetUri: String) {
    val file = File(requireNotNull(Uri.parse(targetUri).path))
    suspendCancellableCoroutine { continuation ->
        takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(results: ImageCapture.OutputFileResults) = continuation.resume(Unit)

                override fun onError(exception: ImageCaptureException) = continuation.resumeWithException(
                    DocumentException(DocumentError.FileAccess("The picture couldn't be taken. Please try again.")),
                )
            },
        )
    }
}
