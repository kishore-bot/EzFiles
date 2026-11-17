package com.ezfiles.reader.platform

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Moonshine Base (English) through sherpa-onnx, fully on the device. Silero VAD finds where the user
 * starts and stops speaking; the whole utterance is then transcribed at once (Moonshine is not a
 * streaming model). Loaded on first use and kept for the process: loading takes about a second.
 *
 * Model files (downloaded in EzFiles Info, or pushed with adb) are found through [folder]:
 * `encoder_model.ort`, `decoder_model_merged.ort`, `tokens.txt` (sherpa-onnx-moonshine-base-en-quantized)
 * and `silero_vad.onnx`.
 */
internal object MoonshineSpeech {
    /** Where the installed model is, or null; set by the app (AndroidModelDownloads). */
    var folder: () -> String? = { null }
    private const val TAG = "EzSpeech"
    private const val SAMPLE_RATE = 16_000
    private const val WINDOW = 512

    private val names = listOf("encoder_model.ort", "decoder_model_merged.ort", "tokens.txt", "silero_vad.onnx")
    private val files: List<File> get() = folder()?.let { dir -> names.map { File(dir, it) } }.orEmpty()

    fun available(): Boolean = files.isNotEmpty() && files.all { it.isFile && it.canRead() }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    @Synchronized
    private fun load(): Pair<OfflineRecognizer, Vad> {
        val files = files
        val r = recognizer ?: OfflineRecognizer(
            null,
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    moonshine = OfflineMoonshineModelConfig(encoder = files[0].path, mergedDecoder = files[1].path),
                    tokens = files[2].path,
                    numThreads = 4,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            ),
        ).also { recognizer = it }
        val v = vad ?: Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = files[3].path,
                    threshold = 0.5f,
                    // Room for a short pause mid-sentence ("open the... resume").
                    minSilenceDuration = 0.8f,
                    minSpeechDuration = 0.25f,
                    windowSize = WINDOW,
                    maxSpeechDuration = 20f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            ),
        ).also { vad = it }
        return r to v
    }

    /**
     * Listens for one utterance on the calling (background) thread. Returns the text, "" when the
     * user said nothing before [NO_SPEECH_MS], or null when cancelled.
     */
    @SuppressLint("MissingPermission") // The voice overlay only opens after the microphone is granted.
    fun listenOnce(cancelled: () -> Boolean, finish: () -> Boolean, onListening: () -> Unit, onLevel: (Float) -> Unit): String? {
        val started = SystemClock.elapsedRealtime()
        val (recognizer, vad) = load()
        Log.i(TAG, "Moonshine ready in ${SystemClock.elapsedRealtime() - started} ms")
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, WINDOW * 2 * 8))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("microphone unavailable")
        }
        vad.reset()
        val shorts = ShortArray(WINDOW)
        val window = FloatArray(WINDOW)
        var segment: FloatArray? = null
        var heard = false
        try {
            record.startRecording()
            onListening()
            val since = SystemClock.elapsedRealtime()
            while (!cancelled()) {
                val n = record.read(shorts, 0, WINDOW)
                if (n <= 0) continue
                var energy = 0.0
                for (i in 0 until n) {
                    val sample = shorts[i] / 32768f
                    window[i] = sample
                    energy += sample * sample
                }
                // About -50 dBFS (room) to -15 dBFS (close speech) → 0..1, for the orb.
                val db = 20 * log10(sqrt(energy / n) + 1e-9)
                onLevel(((db + 50) / 35).toFloat().coerceIn(0f, 1f))
                vad.acceptWaveform(if (n == WINDOW) window else window.copyOf(n))
                if (vad.isSpeechDetected()) heard = true
                if (!vad.empty()) {
                    segment = vad.front().samples
                    vad.pop()
                    break
                }
                val elapsed = SystemClock.elapsedRealtime() - since
                if (finish() || elapsed > MAX_LISTEN_MS || (!heard && elapsed > NO_SPEECH_MS)) {
                    vad.flush()
                    if (!vad.empty()) segment = vad.front().samples
                    break
                }
            }
        } finally {
            onLevel(0f)
            runCatching { record.stop() }
            record.release()
            vad.clear()
        }
        if (cancelled()) return null
        val audio = segment ?: return ""
        val decodeStart = SystemClock.elapsedRealtime()
        val stream = recognizer.createStream()
        val text = try {
            stream.acceptWaveform(audio, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
        Log.i(TAG, "Moonshine: ${audio.size * 1000L / SAMPLE_RATE} ms of speech decoded in ${SystemClock.elapsedRealtime() - decodeStart} ms")
        return text
    }

    private const val NO_SPEECH_MS = 7_000L
    private const val MAX_LISTEN_MS = 25_000L
}
