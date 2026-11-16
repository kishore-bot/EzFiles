package com.ezfiles.app

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.ezfiles.reader.application.assistant.AssistantNavigation
import com.ezfiles.reader.application.assistant.FilyPrompt
import com.ezfiles.reader.application.assistant.FilySession
import com.ezfiles.reader.application.assistant.ReaderControl
import com.ezfiles.reader.application.assistant.ToolExecutor
import com.ezfiles.reader.platform.ModelInference
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug builds only: runs Fily's real request path (prompt, tools, library) on a chosen model file and
 * engine setting, without speech, and logs the timings under the EzBench tag for scripts/phone-bench.sh.
 * Navigation goes to a private sink, so "open it" does not change the screen.
 */
object FilyBench {
    private const val TAG = "EzBench"
    private val requests = listOf("find my latest CV", "open it", "what does it say about the payment", "show my recent files")
    private val running = AtomicBoolean(false)

    /** [spec]: "model=/data/local/tmp/X.gguf;gpu=0;threads=4;ubatch=512;runs=5;label=q8-cpu". */
    @OptIn(DelicateCoroutinesApi::class)
    fun start(dependencies: AppDependencies, spec: String) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "A benchmark is already running")
            return
        }
        GlobalScope.launch(Dispatchers.Default) {
            try {
                run(dependencies, spec.split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { (k, v) -> k.trim() to v.trim() })
            } catch (failure: Throwable) {
                Log.e(TAG, "FAILED spec=$spec", failure)
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun run(dependencies: AppDependencies, options: Map<String, String>) {
        val model = options["model"] ?: error("model= is required")
        val gpu = options["gpu"] == "1"
        val threads = options["threads"]?.toInt() ?: 4
        val ubatch = options["ubatch"]?.toInt() ?: 512
        val runs = options["runs"]?.toInt() ?: 5
        val label = options["label"] ?: model.substringAfterLast('/')
        // Two copies of the model would not fit comfortably next to each other.
        dependencies.modelManager?.unload()

        val inference = ModelInference()
        val loadStart = SystemClock.elapsedRealtime()
        check(inference.load(model, gpu, threads, ubatch, FilyPrompt.STABLE_PREFIX)) { "load failed" }
        val loadMs = SystemClock.elapsedRealtime() - loadStart
        Log.i(TAG, "LOAD label=$label ms=$loadMs gpuFailed=${inference.gpuFailed()}")

        val session = FilySession(
            complete = { prompt, grammar -> inference.infer(prompt, 128, FilyPrompt.STABLE_PREFIX, grammar) },
            tools = ToolExecutor(dependencies.assistantActions, AssistantNavigation()),
            access = dependencies.assistantAccess,
            today = { java.time.LocalDate.now().toString() },
            reader = ReaderControl(),
        )
        val times = List(requests.size) { ArrayList<Long>() }
        repeat(runs) { run ->
            session.clear()
            requests.forEachIndexed { index, request ->
                val start = SystemClock.elapsedRealtime()
                val reply = session.ask(request)
                val ms = SystemClock.elapsedRealtime() - start
                times[index] += ms
                Log.i(TAG, "RUN label=$label run=${run + 1} ms=$ms request=\"$request\" reply=\"${reply.take(80)}\"")
            }
        }
        val first = times.map { it.first() }
        val warm = times.map { list -> list.drop(1).sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] } }
        Log.i(
            TAG,
            "SUMMARY label=$label gpu=${if (gpu && !inference.gpuFailed()) 1 else 0} threads=$threads ubatch=$ubatch loadMs=$loadMs " +
                "firstMs=${first.joinToString(",")} warmMedianMs=${warm.joinToString(",")} warmTotalMs=${warm.sum()} pssMb=${Debug.getPss() / 1024}",
        )
        inference.unload()
    }
}
